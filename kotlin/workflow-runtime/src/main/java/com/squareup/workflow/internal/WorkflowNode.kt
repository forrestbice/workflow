/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow.internal

import com.squareup.workflow.Snapshot
import com.squareup.workflow.StatefulWorkflow
import com.squareup.workflow.Worker.Emitter
import com.squareup.workflow.Worker.OutputOrFinished
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowAction
import com.squareup.workflow.internal.Behavior.WorkerCase
import com.squareup.workflow.parse
import com.squareup.workflow.readByteStringWithLength
import com.squareup.workflow.writeByteStringWithLength
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.selects.SelectBuilder
import kotlin.coroutines.CoroutineContext

/**
 * A node in a state machine tree. Manages the actual state for a given [Workflow].
 *
 * @param initialState Allows unit tests to start the node from a given state, instead of calling
 * [StatefulWorkflow.initialState].
 */
internal class WorkflowNode<InputT : Any, StateT : Any, OutputT : Any, RenderingT : Any>(
  val id: WorkflowId<InputT, OutputT, RenderingT>,
  workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>,
  initialInput: InputT,
  snapshot: Snapshot?,
  baseContext: CoroutineContext,
  initialState: StateT? = null
) : CoroutineScope {

  /**
   * Context that has a job that will live as long as this node.
   * Also adds a debug name to this coroutine based on its ID.
   */
  override val coroutineContext = baseContext + Job(baseContext[Job]) + CoroutineName(id.toString())

  init {
    // This will get invoked whenever we are cancelled (via our cancel() method), or whenever
    // any of our ancestor workflows are cancelled, anywhere up the tree, including whenever an
    // exception is thrown that cancels a workflow.
    coroutineContext[Job]!!.invokeOnCompletion {
      behavior?.teardownHooks?.forEach { it.invoke() }
    }
  }

  private val subtreeManager = SubtreeManager<StateT, OutputT>(coroutineContext)
  private val workerTracker =
    LifetimeTracker<WorkerCase<*, StateT, OutputT>, Any, ReceiveChannel<*>>(
        getKey = { case -> case },
        start = { case -> case.launchWorker() },
        dispose = { _, channel -> channel.cancel() }
    )

  private var state: StateT = initialState
      ?: snapshot?.restoreState(initialInput, workflow)
      ?: workflow.initialState(initialInput, snapshot = null, scope = this)

  private var lastInput: InputT = initialInput

  private var behavior: Behavior<StateT, OutputT>? = null

  /**
   * Walk the tree of workflows, rendering each one and using
   * [RenderContext][com.squareup.workflow.RenderContext] to give its children a chance to
   * render themselves and aggregate those child renderings.
   */
  @Suppress("UNCHECKED_CAST")
  fun render(
    workflow: StatefulWorkflow<InputT, *, OutputT, RenderingT>,
    input: InputT
  ): RenderingT =
    renderWithStateType(workflow as StatefulWorkflow<InputT, StateT, OutputT, RenderingT>, input)

  /**
   * Walk the tree of state machines again, this time gathering snapshots and aggregating them
   * automatically.
   */
  fun snapshot(workflow: StatefulWorkflow<*, *, *, *>): Snapshot {
    val childrenSnapshot = subtreeManager.createChildrenSnapshot()
    @Suppress("UNCHECKED_CAST")
    return childrenSnapshot.withState(
        workflow as StatefulWorkflow<InputT, StateT, OutputT, RenderingT>
    )
  }

  /**
   * Gets the next [output][OutputT] from the state machine.
   *
   * Walk the tree of state machines, asking each one to wait for its next event. If something happen
   * that results in an output, that output is returned. Null means something happened that requires
   * a re-render, e.g. my state changed or a child state changed.
   *
   * It is an error to call this method after calling [cancel].
   */
  fun <T : Any> tick(
    selector: SelectBuilder<T?>,
    handler: (OutputT) -> T?
  ) {
    fun acceptUpdate(action: WorkflowAction<StateT, OutputT>): T? {
      val (newState, output) = action(state)
      state = newState
      return output?.let(handler)
    }

    // Listen for any child workflow updates.
    subtreeManager.tickChildren(selector, ::acceptUpdate)

    // Listen for any subscription updates.
    workerTracker.lifetimes
        .forEach { (case, channel) ->
          selector.receiveOutputOrFinished(channel) { outputOrFinished ->
            val update = case.acceptUpdate(outputOrFinished)
            acceptUpdate(update)
          }
        }

    // Listen for any events.
    with(selector) {
      behavior!!.nextActionFromEvent.onAwait { update ->
        acceptUpdate(update)
      }
    }
  }

  /**
   * Cancels this state machine host, and any coroutines started as children of it.
   *
   * This must be called when the caller will no longer call [tick]. It is an error to call [tick]
   * after calling this method.
   */
  fun cancel() {
    // No other cleanup work should be done in this function, since it will only be invoked when
    // this workflow is *directly* discarded by its parent (or the host).
    // If you need to do something whenever this workflow is torn down, add it to the
    // invokeOnCompletion handler for the Job above.
    coroutineContext.cancel()
  }

  /**
   * Contains the actual logic for [render], after we've casted the passed-in [Workflow]'s
   * state type to our `StateT`.
   */
  private fun renderWithStateType(
    workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>,
    input: InputT
  ): RenderingT {
    updateInputAndState(workflow, input)

    val context = RealRenderContext(subtreeManager)
    val rendering = workflow.render(input, state, context)

    behavior = context.buildBehavior()
        .apply {
          // Start new children/workers, and drop old ones.
          subtreeManager.track(childCases)
          workerTracker.track(workerCases)
        }

    return rendering
  }

  private fun updateInputAndState(
    workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>,
    newInput: InputT
  ) {
    state = workflow.onInputChanged(lastInput, newInput, state)
    lastInput = newInput
  }

  /** @see Snapshot.parsePartial */
  private fun Snapshot.withState(
    workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>
  ): Snapshot {
    val stateSnapshot = workflow.snapshotState(state)
    return Snapshot.write { sink ->
      sink.writeByteStringWithLength(stateSnapshot.bytes)
      sink.write(bytes)
    }
  }

  private fun Snapshot.restoreState(
    input: InputT,
    workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>
  ): StateT {
    val (state, childrenSnapshot) = parsePartial(input, workflow)
    subtreeManager.restoreChildrenFromSnapshot(childrenSnapshot)
    return state
  }

  /** @see Snapshot.withState */
  private fun Snapshot.parsePartial(
    input: InputT,
    workflow: StatefulWorkflow<InputT, StateT, OutputT, RenderingT>
  ): Pair<StateT, Snapshot> =
    bytes.parse { source ->
      val stateSnapshot = source.readByteStringWithLength()
      val childrenSnapshot = source.readByteString()
      val state = workflow.initialState(input, Snapshot.of(stateSnapshot), this@WorkflowNode)
      return Pair(state, Snapshot.of(childrenSnapshot))
    }

  /**
   * Launches a new coroutine that is a child of this node's scope, and calls
   * [com.squareup.workflow.Worker.performWork] from that coroutine. Returns a [ReceiveChannel] that
   * will be used to send anything emitted by [com.squareup.workflow.Worker.Emitter]. The channel
   * will be closed when `performWork` returns.
   */
  @Suppress("EXPERIMENTAL_API_USAGE")
  private fun <T> WorkerCase<T, StateT, OutputT>.launchWorker(): ReceiveChannel<T> =
    produce {
      val emitter = object : Emitter<T> {
        override suspend fun emitOutput(output: T) = send(output)
      }
      worker.performWork(emitter)
    }
}

/**
 * Wraps [ReceiveChannel.onReceiveOrNull] to detect if the channel is actually closed vs just
 * emitting a null value. Once `receiveOrClosed` support lands in the coroutines library, we should
 * use that instead.
 */
@Suppress("EXPERIMENTAL_API_USAGE")
private inline fun <R> SelectBuilder<R>.receiveOutputOrFinished(
  channel: ReceiveChannel<*>,
  crossinline handler: (OutputOrFinished<*>) -> R
) {
  channel.onReceiveOrNull { maybeOutput ->
    val outputOrFinished = if (maybeOutput == null && channel.isClosedForReceive) {
      OutputOrFinished.Finished
    } else {
      OutputOrFinished.Output(maybeOutput)
    }
    return@onReceiveOrNull handler(outputOrFinished)
  }
}

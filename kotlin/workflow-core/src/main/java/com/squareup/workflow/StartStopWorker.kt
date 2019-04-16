package com.squareup.workflow

import com.squareup.workflow.Worker.Emitter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [Worker] that performs some action when started and when stopped.
 */
abstract class StartStopWorker : Worker<Nothing> {

  /**
   * Called when this worker is started.
   *
   * It will be invoked on the dispatcher running the workflow, inside a [NonCancellable] block.
   */
  abstract suspend fun onStart()

  /**
   * Called when this worker has been torn down.
   *
   * It will be invoked on the dispatcher running the workflow, inside a [NonCancellable] block.
   */
  abstract suspend fun onStop()

  final override suspend fun performWork(emitter: Emitter<Nothing>) {
    withContext(NonCancellable) {
      onStart()
    }

    try {
      suspendCancellableCoroutine<Nothing> { }
    } finally {
      withContext(NonCancellable) {
        onStop()
      }
    }
  }

  /**
   * Equates [StartStopWorker]s that have the same concrete class.
   */
  override fun doesSameWorkAs(otherWorker: Worker<Nothing>): Boolean =
    this::class == otherWorker::class
}

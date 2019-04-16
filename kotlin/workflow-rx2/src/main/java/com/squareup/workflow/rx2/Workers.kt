package com.squareup.workflow.rx2

import com.squareup.workflow.Worker
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.reactive.openSubscription
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.openSubscription

inline fun <reified T : Any> Observable<T>.asWorker(key: String = ""): Worker<T> =
  Worker.fromChannel(key) {
    @Suppress("EXPERIMENTAL_API_USAGE")
    openSubscription()
  }

inline fun <reified T : Any> Flowable<T>.asWorker(key: String = ""): Worker<T> =
  Worker.fromChannel(key) {
    @Suppress("EXPERIMENTAL_API_USAGE")
    openSubscription()
  }

inline fun <reified T : Any> Maybe<T>.asWorker(key: String = ""): Worker<T> =
  Worker.fromNullable(key) { await() }

inline fun <reified T : Any> Single<T>.asWorker(key: String = ""): Worker<T> =
  Worker.from(key) { await() }

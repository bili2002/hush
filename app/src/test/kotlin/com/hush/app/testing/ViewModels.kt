package com.hush.app.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * Cancels the ViewModel's scope and waits until every coroutine in it has really finished.
 *
 * Cancelling alone is not enough in a test's teardown: a query can still be running on an IO
 * thread, and when it resumes after [MainDispatcherRule] has reset `Dispatchers.Main` the failed
 * dispatch is reported to the next test as `UncaughtExceptionsBeforeTest`. Call this from `@After`,
 * which runs before the rule resets the dispatcher.
 */
fun ViewModel.cancelAndJoin() = runBlocking { viewModelScope.coroutineContext.job.cancelAndJoin() }

package com.example.aria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` for unit tests.
 *
 * WHY: AssistantViewModel collects speech events on `viewModelScope`, which is
 * bound to `Dispatchers.Main.immediate`. Without this rule every Phase 2 test
 * would fail with "Module with the Main dispatcher had failed to initialise",
 * because plain JVM tests have no Android main looper.
 *
 * Unconfined (not Standard) so that the event collector is subscribed the moment
 * the ViewModel is created — tests can then assert immediately after emitting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/*
 * Copyright 2026 Talaria contributors
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
package com.hermesgadget.talaria.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher around a test so ViewModels
 * that launch on the main dispatcher can be driven deterministically.
 *
 * Q14: the rule now owns explicit cleanup. Tests register ViewModel scopes (or any
 * CoroutineScope) via [track] as they create them; [finished] cancels each one while
 * the test Main dispatcher is still installed and its scheduler can absorb late
 * continuations. Cancelling at teardown means no ViewModel work is left parked on a
 * dead scheduler after the class ends — pending coroutines fail fast with silent
 * CancellationExceptions instead of surfacing later as order-dependent flake
 * (UncaughtExceptionsBeforeTest).
 *
 * Main is still NOT reset in `finished`: a coroutine resumed on a real dispatcher
 * (e.g. an in-flight `withContext(Dispatchers.IO)` hop) can reach its Main-continuation
 * after the test ends; with Main unset that resume throws
 * "Dispatchers.Main was accessed when the platform dispatcher was absent" on a real
 * thread, which the global kotlinx ExceptionCollector attributes to the NEXT runTest
 * class. Keeping the dispatcher installed makes such stragglers dispatch into the
 * already-cancelled scope — harmless. Scope-switch tests install their own Main, which
 * replaces this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    private val trackedScopes = mutableListOf<CoroutineScope>()

    /** Register a ViewModel/worker scope for teardown cancellation (Q14). */
    fun track(scope: CoroutineScope) {
        trackedScopes += scope
    }

    override fun starting(description: Description) {
        trackedScopes.clear()
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        // Cancel tracked scopes FIRST (while Main is installed) so parked work fails
        // here, deterministically, instead of after the scheduler dies.
        for (scope in trackedScopes) {
            runCatching { scope.cancel() }
        }
        trackedScopes.clear()
    }
}

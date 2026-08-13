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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher around a test so ViewModels
 * that launch on the main dispatcher can be driven deterministically.
 *
 * Deliberately does NOT reset Main after the test: a ViewModel coroutine that is
 * still suspended on a real dispatcher (e.g. an in-flight `withContext(Dispatchers.IO)`
 * hop) can resume after the test ends. If Main were unset at that moment, the resume
 * would throw the "Dispatchers.Main was accessed when the platform dispatcher was
 * absent" IllegalStateException on a real thread, which the global kotlinx
 * ExceptionCollector attributes to the NEXT runTest class as
 * `UncaughtExceptionsBeforeTest` (intermittent CI flake, hit twice on
 * LearningScopeSwitchTest). Keeping the dispatcher installed makes such late resumes
 * dispatch into a dead scheduler — harmless. Tests that need their own Main (e.g.
 * the scope-switch tests) call `setMain` themselves, which replaces this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Unit
}

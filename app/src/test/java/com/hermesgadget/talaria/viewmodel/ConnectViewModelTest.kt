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


package com.hermesgadget.talaria.viewmodel

import com.hermesgadget.talaria.domain.model.AuthMode
import com.hermesgadget.talaria.feature.connection.ConnectUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectViewModelTest {
    @Test
    fun defaultStatePointsAtLocalDashboard() {
        val state = ConnectUiState()
        // 10.0.2.2 is the emulator alias for the host loopback (see ConnectUiState).
        assertEquals("http://10.0.2.2:9119", state.baseUrl)
        assertEquals(AuthMode.SESSION_TOKEN, state.authMode)
    }
}

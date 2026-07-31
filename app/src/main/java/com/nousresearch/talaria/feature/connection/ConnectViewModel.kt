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


package com.nousresearch.talaria.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.data.repo.ConnectionRepository
import com.nousresearch.talaria.domain.model.AuthMode
import com.nousresearch.talaria.domain.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectUiState(
    val name: String = "Home Hermes",
    val baseUrl: String = "http://127.0.0.1:9119",
    val authMode: AuthMode = AuthMode.SESSION_TOKEN,
    val username: String = "",
    val password: String = "",
    val sessionToken: String = "",
    val bearerToken: String = "",
    val managementProfile: String = "",
    val pinSha256: String = "",
    val testing: Boolean = false,
    val error: String? = null,
    val statusLine: String? = null,
)

class ConnectViewModel(
    private val repo: ConnectionRepository = TalariaApp.instance.container.connectionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()
    val profiles: StateFlow<List<ConnectionProfile>> = repo.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (ConnectUiState) -> ConnectUiState) {
        _ui.value = transform(_ui.value)
    }

    fun saveAndTest(onSuccess: () -> Unit) {
        val s = _ui.value
        viewModelScope.launch {
            _ui.value = s.copy(testing = true, error = null)
            try {
                val profile = repo.save(
                    name = s.name,
                    baseUrl = s.baseUrl,
                    authMode = s.authMode,
                    username = s.username.ifBlank { null },
                    sessionToken = s.sessionToken.ifBlank { null },
                    password = s.password.ifBlank { null },
                    bearerToken = s.bearerToken.ifBlank { null },
                    managementProfile = s.managementProfile,
                    pinSha256 = s.pinSha256.ifBlank { null },
                )
                repo.setActive(profile.id)
                val result = repo.testConnection()
                result.fold(
                    onSuccess = {
                        _ui.value = _ui.value.copy(
                            testing = false,
                            statusLine = "Connected · Hermes ${it.version ?: "unknown"}",
                        )
                        onSuccess()
                    },
                    onFailure = {
                        _ui.value = _ui.value.copy(testing = false, error = it.message ?: "Connection failed")
                    },
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(testing = false, error = t.message)
            }
        }
    }

    fun select(id: String) = repo.setActive(id)
    fun delete(id: String) = repo.delete(id)

    companion object {
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel() as T
        }
    }
}

/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionKind
import com.hermesgadget.talaria.core.data.repo.SavedSessionFilter
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationRepository
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationSnapshot
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationStore
import com.hermesgadget.talaria.core.network.ConnectionScope
import com.hermesgadget.talaria.core.network.ConnectionScopeObserver
import com.hermesgadget.talaria.core.util.suspendResult
import com.hermesgadget.talaria.domain.model.scopeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionOrganizationUiState(
    val organization: SessionOrganizationSnapshot = SessionOrganizationSnapshot(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Owns only Room-backed local organization. No Hermes capability discovery or
 * server archive/move calls belong here.
 */
class SessionOrganizationViewModel(
    private val store: SessionOrganizationStore,
    connectionIdProvider: () -> String? = { null },
    private val scopeFlow: StateFlow<ConnectionScope?>? = null,
) : ViewModel() {
    private var boundScope: ConnectionScope? = scopeFlow?.value
    private var connectionId: String? = if (scopeFlow != null) {
        scopeFlow.value?.scopeId
    } else {
        connectionIdProvider()?.trim()?.takeIf { it.isNotEmpty() }
    }
    private var scopeObserver: ConnectionScopeObserver? = null
    private var observeJob: Job? = null
    private var mutationJob: Job? = null
    private val _ui = MutableStateFlow(SessionOrganizationUiState())
    val ui: StateFlow<SessionOrganizationUiState> = _ui.asStateFlow()

    init {
        scopeObserver = scopeFlow?.let { flow ->
            ConnectionScopeObserver(flow, viewModelScope) { next -> rebind(next) }
        }
        connectionId?.let { observeConnection(it) }
    }

    private fun rebind(next: ConnectionScope?) {
        boundScope = next
        connectionId = next?.scopeId
        mutationJob?.cancel()
        observeJob?.cancel()
        _ui.value = SessionOrganizationUiState()
        next?.scopeId?.let { observeConnection(it) }
    }

    private fun observeConnection(scope: String) {
        val expectedScope = boundScope
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            store.observe(scope).collect { organization ->
                if (scopeObserver?.isCurrent(expectedScope) != false) {
                    _ui.update { current -> current.copy(organization = organization, busy = false) }
                }
            }
        }
    }

    private fun isCurrentScope(expected: ConnectionScope?): Boolean =
        scopeObserver?.isCurrent(expected) != false

    fun toggleFavorite(sessionId: String) {
        val scope = requireScope() ?: return
        val favorite = sessionId !in _ui.value.organization.favoriteSessionIds
        launchMutation { store.setFavorite(scope, sessionId, favorite) }
    }

    fun setCollectionMembership(sessionId: String, collectionId: Long, assigned: Boolean) {
        val scope = requireScope() ?: return
        launchMutation {
            store.setCollectionMembership(scope, sessionId, collectionId, assigned)
        }
    }

    fun createCollection(name: String, kind: LocalSessionCollectionKind) {
        val scope = requireScope() ?: return
        launchMutation { store.createCollection(scope, name, kind) }
    }

    fun deleteCollection(collectionId: Long) {
        val scope = requireScope() ?: return
        launchMutation { store.deleteCollection(scope, collectionId) }
    }

    fun saveFilter(filter: SavedSessionFilter) {
        val scope = requireScope() ?: return
        launchMutation { store.saveFilter(filter.copy(connectionId = scope)) }
    }

    fun deleteFilter(filterId: Long) {
        val scope = requireScope() ?: return
        launchMutation { store.deleteFilter(scope, filterId) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    private fun requireScope(): String? {
        connectionId ?: _ui.update {
            it.copy(error = "Local session organization needs an active connection")
        }
        return connectionId
    }

    private fun launchMutation(block: suspend () -> Unit) {
        val expectedScope = boundScope
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            suspendResult { block() }
                .onSuccess {
                    if (isCurrentScope(expectedScope)) _ui.update { it.copy(busy = false) }
                }
                .onFailure { error ->
                    if (isCurrentScope(expectedScope)) {
                        _ui.update {
                            it.copy(busy = false, error = error.message ?: "Local update failed")
                        }
                    }
                }
        }
    }

    companion object {
        fun factory(
            store: SessionOrganizationRepository =
                TalariaApp.instance.container.sessionOrganizationRepository,
            connectionIdProvider: () -> String? = {
                TalariaApp.instance.container.connectionStore.activeProfile()?.scopeId()
            },
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionOrganizationViewModel(
                    store = store,
                    connectionIdProvider = connectionIdProvider,
                    scopeFlow = TalariaApp.instance.container.connectionStore.scope,
                ) as T
        }
    }
}

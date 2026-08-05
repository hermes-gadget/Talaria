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
import com.hermesgadget.talaria.domain.model.scopeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
) : ViewModel() {
    private val connectionId = connectionIdProvider()?.trim()?.takeIf { it.isNotEmpty() }
    private val _ui = MutableStateFlow(SessionOrganizationUiState())
    val ui: StateFlow<SessionOrganizationUiState> = _ui.asStateFlow()

    init {
        if (connectionId != null) {
            viewModelScope.launch {
                store.observe(connectionId).collect { organization ->
                    _ui.update { current -> current.copy(organization = organization, busy = false) }
                }
            }
        }
    }

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
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _ui.update { it.copy(busy = false) } }
                .onFailure { error ->
                    _ui.update {
                        it.copy(busy = false, error = error.message ?: "Local update failed")
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
                SessionOrganizationViewModel(store, connectionIdProvider) as T
        }
    }
}

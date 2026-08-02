/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.manage.commandcenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private sealed interface CommandCenterFetch<out T> {
    data class Success<T>(val value: T) : CommandCenterFetch<T>

    data class Failure(val message: String) : CommandCenterFetch<Nothing>
}

class CommandCenterViewModel(
    private val repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow<CommandCenterUiState>(CommandCenterUiState.Loading)
    val ui: StateFlow<CommandCenterUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val current = _ui.value
        if (current is CommandCenterUiState.Content && current.data.refreshing) return

        _ui.value = when (current) {
            is CommandCenterUiState.Content -> current.copy(
                data = current.data.copy(refreshing = true),
            )
            else -> CommandCenterUiState.Loading
        }

        viewModelScope.launch {
            val loaded = coroutineScope {
                val gateway = async { loadGateway() }
                val logs = async { loadLogs() }
                val usage = async { loadUsage() }
                Triple(gateway.await(), logs.await(), usage.await())
            }

            val (gateway, logs, usage) = loaded
            if (gateway is CommandCenterSection.Unavailable &&
                logs is CommandCenterSection.Unavailable &&
                usage is CommandCenterSection.Unavailable
            ) {
                _ui.value = CommandCenterUiState.Failure(
                    listOf(gateway.reason, logs.reason, usage.reason).joinToString("\n"),
                )
            } else {
                _ui.value = CommandCenterUiState.Content(
                    CommandCenterContent(
                        gateway = gateway,
                        logs = logs,
                        usage = usage,
                        lastUpdatedMs = now(),
                    ),
                )
            }
        }
    }

    private suspend fun loadGateway(): CommandCenterSection<CommandCenterGateway> = coroutineScope {
        val status = async { fetch { repo.refreshStatus() } }.await()
        val stats = async { fetch { repo.getSystemStats() } }.await()
        val statusValue = (status as? CommandCenterFetch.Success<com.hermesgadget.talaria.domain.model.StatusResponse>)?.value
        val statsValue = (stats as? CommandCenterFetch.Success<com.hermesgadget.talaria.domain.model.SystemStats>)?.value
        val warnings = buildList {
            if (status is CommandCenterFetch.Failure) {
                add("Gateway status unavailable: ${status.message}")
            }
            if (stats is CommandCenterFetch.Failure) {
                add("Host stats unavailable: ${stats.message}")
            }
        }

        if (statusValue == null && statsValue == null) {
            CommandCenterSection.Unavailable(warnings.joinToString(" ").ifBlank { "Gateway data is unavailable." })
        } else {
            CommandCenterSection.Available(
                CommandCenterGateway(
                    status = statusValue,
                    stats = statsValue,
                    warnings = warnings,
                ),
            )
        }
    }

    private suspend fun loadLogs(): CommandCenterSection<CommandCenterLogs> = coroutineScope {
        val fetched = COMMAND_CENTER_LOG_SOURCES.map { source ->
            async { source to fetch { repo.getLogs(source, COMMAND_CENTER_LOG_LINES) } }
        }.awaitAll()

        val unavailable = fetched.mapNotNull { (source, result) ->
            if (result is CommandCenterFetch.Failure) "$source: ${result.message}" else null
        }
        val lines = fetched.flatMap { (source, result) ->
            val available = (result as? CommandCenterFetch.Success<List<String>>)?.value
                ?: return@flatMap emptyList()
            parseLogLines(source, available)
        }.sortedWith(compareByDescending { it.timestamp.orEmpty() })

        if (lines.isEmpty() && unavailable.size == COMMAND_CENTER_LOG_SOURCES.size) {
            CommandCenterSection.Unavailable("Logs unavailable: ${unavailable.joinToString("; ")}")
        } else {
            CommandCenterSection.Available(
                CommandCenterLogs(
                    lines = lines,
                    unavailableSources = unavailable,
                ),
            )
        }
    }

    private suspend fun loadUsage(): CommandCenterSection<CommandCenterUsageSummary> {
        return when (val result = fetch { repo.getAnalytics(COMMAND_CENTER_USAGE_DAYS) }) {
            is CommandCenterFetch.Success -> CommandCenterSection.Available(parseUsageSummary(result.value))
            is CommandCenterFetch.Failure -> CommandCenterSection.Unavailable(
                "Usage unavailable: ${result.message}",
            )
        }
    }

    private suspend fun <T> fetch(block: suspend () -> Result<T>): CommandCenterFetch<T> =
        runCatching { block() }.fold(
            onSuccess = { result ->
                result.fold(
                    onSuccess = { CommandCenterFetch.Success(it) },
                    onFailure = { CommandCenterFetch.Failure(it.userMessage()) },
                )
            },
            onFailure = { CommandCenterFetch.Failure(it.userMessage()) },
        )

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        fun factory(
            repo: HermesRepository = TalariaApp.instance.container.hermesRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CommandCenterViewModel(repo) as T
        }
    }
}

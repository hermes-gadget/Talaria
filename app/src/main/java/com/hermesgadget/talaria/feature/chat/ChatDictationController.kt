/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.feature.chat

/**
 * Q07: dictation bookkeeping extracted out of ChatViewModel so the ownership
 * boundaries are explicit and the scope guard is independently testable.
 *
 * Owns: which tab/scope generation an active dictation session belongs to, the
 * server-STT capability cache, and the scope-staleness guard used by every
 * dictation callback. Does NOT own sockets, transcripts, or UI state — those
 * stay in ChatViewModel and interact through the guard + cache.
 */
internal class ChatDictationController(
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal data class CachedServerSttCapability(
        val supported: Boolean,
        val checkedAtMillis: Long,
    )

    /** Active server-dictation session, if any. */
    var serverTabId: String? = null
        private set

    var serverScopeGeneration: Long? = null
        private set

    val isServerDictationActive: Boolean
        get() = serverTabId != null

    // One-shot capability probe bookkeeping per scope.
    var probeGeneration: Long = 0L
        private set

    private val capabilities = mutableMapOf<String, CachedServerSttCapability>()
    private val probedScopes = mutableSetOf<String>()

    fun beginServerDictation(tabId: String, scopeGeneration: Long) {
        serverTabId = tabId
        serverScopeGeneration = scopeGeneration
    }

    fun endServerDictation() {
        serverTabId = null
        serverScopeGeneration = null
    }

    /** True when a probe for [scopeId] already ran (regardless of outcome). */
    fun isProbed(scopeId: String): Boolean = scopeId in probedScopes

    /** Mark [scopeId] as probed; bumps the probe generation for cancellation. */
    fun beginProbe(scopeId: String) {
        probedScopes += scopeId
        probeGeneration++
    }

    fun clearProbes() = probedScopes.clear()

    fun cachedCapability(scopeId: String): CachedServerSttCapability? {
        val cached = capabilities[scopeId]
            ?.takeIf { now() - it.checkedAtMillis < SERVER_STT_CAPABILITY_TTL_MS }
        if (cached == null) capabilities.remove(scopeId)
        return cached
    }

    fun cacheCapability(scopeId: String, supported: Boolean) {
        capabilities[scopeId] = CachedServerSttCapability(supported, now())
    }

    fun invalidateCapability(scopeId: String) {
        capabilities.remove(scopeId)
    }

    /**
     * The dictation-scope guard shared by every callback: results may only land
     * while the same scope is active, the connection generation is unchanged
     * ([generation] captured at dictation start vs. [liveGeneration] now), and
     * the tab still exists.
     */
    fun isCurrentVoiceScope(
        scopeId: String?,
        generation: Long,
        tabId: String,
        activeScopeId: String?,
        liveGeneration: Long,
        activeTabIds: Set<String>,
    ): Boolean =
        scopeId != null && scopeId == activeScopeId &&
            generation == liveGeneration &&
            tabId in activeTabIds

    private companion object {
        const val SERVER_STT_CAPABILITY_TTL_MS = 5 * 60_000L
    }
}

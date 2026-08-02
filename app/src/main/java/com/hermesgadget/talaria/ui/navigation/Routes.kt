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


package com.hermesgadget.talaria.ui.navigation

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class TopDest(val route: String, val label: String) {
    data object Chats : TopDest("chats", "Chats")
    data object Activity : TopDest("activity", "Activity")
    data object Manage : TopDest("manage", "Manage")
    data object You : TopDest("you", "You")
}

object Routes {
    const val CONNECT = "connect"
    const val CHAT = "chat?resume={resume}"
    const val SESSION_DETAIL = "session/{id}"
    const val MANAGE_HOME = "manage_home"
    const val STATUS = "status"
    const val CONFIG = "config"
    const val API_KEYS = "api_keys"
    const val SESSIONS = "sessions"
    const val ARTIFACTS = "artifacts"
    const val LOGS = "logs"
    const val ANALYTICS = "analytics"
    const val CRON = "cron"
    const val PROFILES = "profiles"
    const val SKILLS = "skills"
    const val MCP = "mcp"
    const val WEBHOOKS = "webhooks"
    const val PAIRING = "pairing"
    const val CHANNELS = "channels"
    const val SYSTEM = "system"
    const val MEMORY = "memory"
    const val CURATOR = "curator"
    const val FILES = "files"
    const val REVIEW = "review"
    const val MODELS = "models"
    const val LEARNING = "learning"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"

    fun chat(resume: String? = null) =
        if (resume.isNullOrBlank()) "chat?resume=" else "chat?resume=${encode(resume)}"

    fun sessionDetail(id: String) = "session/${encode(id)}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

sealed interface TalariaDeepLink {
    data object Chat : TalariaDeepLink
    data class Pairing(val connectionId: String? = null, val profile: String? = null) : TalariaDeepLink
    data class Connect(val profile: String?) : TalariaDeepLink
    data class Session(
        val id: String,
        val connectionId: String? = null,
        val profile: String? = null,
    ) : TalariaDeepLink
    data object Status : TalariaDeepLink
    data object Activity : TalariaDeepLink
    data object Manage : TalariaDeepLink
}

/** Strict parser for the app's `talaria://` notification and shortcut links. */
object TalariaDeepLinkParser {
    fun parse(raw: String): TalariaDeepLink? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("talaria", ignoreCase = true)) return null
        return when (uri.host?.lowercase()) {
            "chat" -> TalariaDeepLink.Chat
            "pairing" -> TalariaDeepLink.Pairing(
                connectionId = queryValue(uri.rawQuery, "connection"),
                profile = queryValue(uri.rawQuery, "profile"),
            )
            "connect" -> TalariaDeepLink.Connect(queryValue(uri.rawQuery, "profile"))
            "session" -> uri.rawPath
                ?.removePrefix("/")
                ?.takeIf { it.isNotBlank() && !it.contains('/') }
                ?.let(::decode)
                ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
                ?.let { id ->
                    TalariaDeepLink.Session(
                        id = id,
                        connectionId = queryValue(uri.rawQuery, "connection"),
                        profile = queryValue(uri.rawQuery, "profile"),
                    )
                }
            "status" -> TalariaDeepLink.Status
            "activity" -> TalariaDeepLink.Activity
            "manage" -> TalariaDeepLink.Manage
            else -> null
        }
    }

    private fun queryValue(query: String?, wanted: String): String? = query
        ?.split('&')
        ?.asSequence()
        ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
        ?.firstOrNull { (name, _) -> decode(name) == wanted }
        ?.second
        ?.let(::decode)
        ?.takeIf { it.isNotBlank() }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}

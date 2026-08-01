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


package com.nousresearch.talaria.ui.navigation

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
    const val MODELS = "models"
    const val LEARNING = "learning"
    const val SETTINGS = "settings"

    fun chat(resume: String? = null) =
        if (resume.isNullOrBlank()) "chat?resume=" else "chat?resume=$resume"
}

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

package com.hermesgadget.talaria.feature.manage

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.ui.navigation.Routes

internal data class ManageItem(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val route: String,
    val icon: ImageVector,
)

/**
 * One drill-down group on the Manage home screen. [id] is the stable key used in
 * the `manage_section/{id}` route, so it must not change without a matching
 * update to any persisted navigation state.
 */
internal data class ManageSection(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val items: List<ManageItem>,
)

/**
 * The Manage destination tree.
 *
 * Manage used to be a single 25-row list whose "System" group had grown into an
 * 11-item catch-all. It is now two levels: a short list of categories, each
 * opening a focused sub-list. Every destination stays one search away via the
 * command palette on the home screen, so the extra tap only affects browsing.
 *
 * Device-level preferences (themes, voice) deliberately live under You instead;
 * Manage is for the connected Hermes host.
 */
internal val manageSections = listOf(
    ManageSection(
        id = "agents",
        titleRes = R.string.manage_agents,
        icon = Icons.Filled.SmartToy,
        items = listOf(
            ManageItem(R.string.manage_status_title, R.string.manage_status_subtitle, Routes.STATUS, Icons.Filled.MonitorHeart),
            ManageItem(R.string.manage_command_center_title, R.string.manage_command_center_subtitle, Routes.COMMAND_CENTER, Icons.Filled.Dashboard),
            ManageItem(R.string.manage_sessions_title, R.string.manage_sessions_subtitle, Routes.SESSIONS, Icons.Filled.Forum),
            ManageItem(R.string.manage_artifacts_title, R.string.manage_artifacts_subtitle, Routes.ARTIFACTS, Icons.Filled.Folder),
            ManageItem(R.string.manage_cron_title, R.string.manage_cron_subtitle, Routes.CRON, Icons.Filled.Schedule),
            ManageItem(R.string.manage_analytics_title, R.string.manage_analytics_subtitle, Routes.ANALYTICS, Icons.Filled.BarChart),
        ),
    ),
    ManageSection(
        id = "capabilities",
        titleRes = R.string.manage_capabilities,
        icon = Icons.Filled.AutoAwesome,
        items = listOf(
            ManageItem(R.string.manage_config_title, R.string.manage_config_subtitle, Routes.CONFIG, Icons.Filled.Tune),
            ManageItem(R.string.manage_api_keys_title, R.string.manage_api_keys_subtitle, Routes.API_KEYS, Icons.Filled.Key),
            ManageItem(R.string.manage_models_title, R.string.manage_models_subtitle, Routes.MODELS, Icons.Filled.SmartToy),
            ManageItem(R.string.manage_skills_title, R.string.manage_skills_subtitle, Routes.SKILLS, Icons.Filled.AutoAwesome),
            ManageItem(R.string.manage_mcp_title, R.string.manage_mcp_subtitle, Routes.MCP, Icons.Filled.Hub),
        ),
    ),
    ManageSection(
        id = "workspace",
        titleRes = R.string.manage_workspace,
        icon = Icons.Filled.Workspaces,
        items = listOf(
            ManageItem(R.string.manage_files_title, R.string.manage_files_subtitle, Routes.FILES, Icons.Filled.Folder),
            ManageItem(R.string.manage_review_title, R.string.manage_review_subtitle, Routes.REVIEW, Icons.Filled.Code),
            ManageItem(R.string.manage_terminal_title, R.string.manage_terminal_subtitle, Routes.TERMINAL, Icons.Filled.Terminal),
            ManageItem(R.string.manage_memory_title, R.string.manage_memory_subtitle, Routes.MEMORY, Icons.Filled.Psychology),
            ManageItem(R.string.manage_learning_title, R.string.manage_learning_subtitle, Routes.LEARNING, Icons.Filled.Insights),
            ManageItem(R.string.manage_curator_title, R.string.manage_curator_subtitle, Routes.CURATOR, Icons.Filled.CleaningServices),
        ),
    ),
    ManageSection(
        id = "messaging",
        titleRes = R.string.manage_messaging,
        icon = Icons.Filled.Campaign,
        items = listOf(
            ManageItem(R.string.manage_channels_title, R.string.manage_channels_subtitle, Routes.CHANNELS, Icons.Filled.Campaign),
            ManageItem(R.string.manage_pairing_title, R.string.manage_pairing_subtitle, Routes.PAIRING, Icons.Filled.Link),
            ManageItem(R.string.manage_webhooks_title, R.string.manage_webhooks_subtitle, Routes.WEBHOOKS, Icons.Filled.Webhook),
        ),
    ),
    ManageSection(
        id = "system",
        titleRes = R.string.manage_system_section,
        icon = Icons.Filled.Dns,
        items = listOf(
            ManageItem(R.string.manage_system_title, R.string.manage_system_subtitle, Routes.SYSTEM, Icons.Filled.Dns),
            ManageItem(R.string.manage_profiles_title, R.string.manage_profiles_subtitle, Routes.PROFILES, Icons.Filled.SwitchAccount),
            ManageItem(R.string.manage_logs_title, R.string.manage_logs_subtitle, Routes.LOGS, Icons.AutoMirrored.Filled.Article),
        ),
    ),
)

internal fun manageSection(id: String?): ManageSection? =
    manageSections.firstOrNull { it.id == id }

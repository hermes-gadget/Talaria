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

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.feature.activity.ActivityScreen
import com.hermesgadget.talaria.feature.chat.ChatScreen
import com.hermesgadget.talaria.feature.connection.ConnectScreen
import com.hermesgadget.talaria.feature.manage.ManageHomeScreen
import com.hermesgadget.talaria.feature.manage.analytics.AnalyticsScreen
import com.hermesgadget.talaria.feature.manage.apikeys.ApiKeysScreen
import com.hermesgadget.talaria.feature.manage.artifacts.ArtifactsScreen
import com.hermesgadget.talaria.feature.manage.channels.ChannelsScreen
import com.hermesgadget.talaria.feature.manage.commandcenter.CommandCenterScreen
import com.hermesgadget.talaria.feature.manage.config.ConfigScreen
import com.hermesgadget.talaria.feature.manage.cron.CronScreen
import com.hermesgadget.talaria.feature.manage.logs.LogsScreen
import com.hermesgadget.talaria.feature.manage.mcp.McpScreen
import com.hermesgadget.talaria.feature.manage.pairing.PairingScreen
import com.hermesgadget.talaria.feature.manage.profiles.ProfilesScreen
import com.hermesgadget.talaria.feature.manage.review.ReviewScreen
import com.hermesgadget.talaria.feature.manage.sessions.SessionDetailScreen
import com.hermesgadget.talaria.feature.manage.sessions.SessionsScreen
import com.hermesgadget.talaria.feature.manage.skills.SkillsScreen
import com.hermesgadget.talaria.feature.manage.status.StatusScreen
import com.hermesgadget.talaria.feature.manage.curator.CuratorScreen
import com.hermesgadget.talaria.feature.manage.files.FilesScreen
import com.hermesgadget.talaria.feature.manage.learning.LearningScreen
import com.hermesgadget.talaria.feature.manage.memory.MemoryScreen
import com.hermesgadget.talaria.feature.manage.models.ModelsScreen
import com.hermesgadget.talaria.feature.manage.system.SystemScreen
import com.hermesgadget.talaria.feature.manage.webhooks.WebhooksScreen
import com.hermesgadget.talaria.feature.settings.ThemeScreen
import com.hermesgadget.talaria.feature.settings.NotificationSettingsScreen
import com.hermesgadget.talaria.feature.terminal.TerminalScreen
import com.hermesgadget.talaria.feature.you.YouScreen
import com.hermesgadget.talaria.domain.model.scopeId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TalariaNavRoot(
    shareText: String?,
    shareImage: Uri?,
    deepLink: String?,
    onShareConsumed: () -> Unit,
    onDeepLinkConsumed: () -> Unit,
) {
    val profiles by TalariaApp.instance.container.connectionStore.profiles.collectAsState()
    val activeId by TalariaApp.instance.container.connectionStore.activeId.collectAsState()
    val activeScope = (profiles.find { it.id == activeId } ?: profiles.firstOrNull())?.scopeId()

    LaunchedEffect(activeScope) {
        val container = TalariaApp.instance.container
        container.eventClient.stop()
        container.hermesRepository.clearCache()
        container.clientFactory.invalidate()
        container.wsAuthHelper.invalidate()
        if (activeScope != null) container.eventClient.start()
    }

    // A connection/profile switch is a hard data boundary. Recreate navigation
    // and every destination ViewModel so no screen keeps an old repository load
    // or live socket while presenting the newly selected scope.
    key(activeScope) {
        val navController = rememberNavController()
        val start = if (profiles.isEmpty()) Routes.CONNECT else TopDest.Chats.route
        var currentTop by remember { mutableStateOf(TopDest.Chats.route) }

    LaunchedEffect(shareText, shareImage) {
        if (!shareText.isNullOrBlank() || shareImage != null) {
            navController.navigate(Routes.chat()) { launchSingleTop = true }
        }
    }
    var connectProfile by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        suspend fun applyScope(connectionId: String?, profile: String?) {
            val container = TalariaApp.instance.container
            val validConnectionId = connectionId?.takeIf { candidate ->
                candidate.isNotBlank() && container.connectionStore.profiles.value.any { it.id == candidate }
            }
            if (validConnectionId != null) {
                container.connectionRepository.setActive(validConnectionId)
                if (profile != null) container.connectionStore.setManagementProfile(profile)
            }
            container.hermesRepository.clearCache()
            container.wsAuthHelper.invalidate()
        }
        when (val target = TalariaDeepLinkParser.parse(link)) {
            is TalariaDeepLink.Pairing -> {
                applyScope(target.connectionId, target.profile)
                navController.navigate(Routes.PAIRING)
            }
            is TalariaDeepLink.Connect -> {
                connectProfile = target.profile
                navController.navigate(Routes.CONNECT)
            }
            is TalariaDeepLink.Session -> {
                applyScope(target.connectionId, target.profile)
                navController.navigate(Routes.sessionDetail(target.id))
            }
            // Launcher long-press shortcuts (res/xml/shortcuts.xml).
            TalariaDeepLink.Status -> {
                currentTop = TopDest.Manage.route
                navController.navigate(Routes.STATUS)
            }
            TalariaDeepLink.Activity -> {
                currentTop = TopDest.Activity.route
                navController.navigate(TopDest.Activity.route) { launchSingleTop = true }
            }
            TalariaDeepLink.Manage -> {
                currentTop = TopDest.Manage.route
                navController.navigate(TopDest.Manage.route) { launchSingleTop = true }
            }
            TalariaDeepLink.Chat -> {
                currentTop = TopDest.Chats.route
                navController.navigate(Routes.chat()) { launchSingleTop = true }
            }
            null -> Unit
        }
        onDeepLinkConsumed()
    }

    // Keep the scaffold type stable while the IME opens. Switching between a
    // navigation bar and NavigationSuiteType.None during recomposition can
    // invalidate NavigationSuiteScaffold's remembered slot structure and crash
    // with a ComposableLambdaImpl ClassCastException on real Android devices.
    // Instead, hide the nav suite through its official state API: the suite
    // animates the bar to zero height, so content (and the chat composer with
    // its imePadding) reaches the keyboard instead of floating above the
    // reserved nav-bar slot.
    val navSuiteState = rememberNavigationSuiteScaffoldState()
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) navSuiteState.hide() else navSuiteState.show()
    }
    val navSuiteType =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    NavigationSuiteScaffold(
        layoutType = navSuiteType,
        state = navSuiteState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationDrawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        navigationSuiteItems = {
            item(
                selected = currentTop == TopDest.Chats.route,
                onClick = {
                    currentTop = TopDest.Chats.route
                    navController.navigate(TopDest.Chats.route) { launchSingleTop = true }
                },
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = TopDest.Chats.label) },
                label = { Text(TopDest.Chats.label) },
            )
            item(
                selected = currentTop == TopDest.Activity.route,
                onClick = {
                    currentTop = TopDest.Activity.route
                    navController.navigate(TopDest.Activity.route) { launchSingleTop = true }
                },
                icon = { Icon(Icons.Outlined.NotificationsNone, contentDescription = TopDest.Activity.label) },
                label = { Text(TopDest.Activity.label) },
            )
            item(
                selected = currentTop == TopDest.Manage.route,
                onClick = {
                    currentTop = TopDest.Manage.route
                    navController.navigate(TopDest.Manage.route) { launchSingleTop = true }
                },
                icon = { Icon(Icons.Outlined.ManageAccounts, contentDescription = TopDest.Manage.label) },
                label = { Text(TopDest.Manage.label) },
            )
            item(
                selected = currentTop == TopDest.You.route,
                onClick = {
                    currentTop = TopDest.You.route
                    navController.navigate(TopDest.You.route) { launchSingleTop = true }
                },
                icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = TopDest.You.label) },
                label = { Text(TopDest.You.label) },
            )
        },
    ) {
        // Each screen's TopAppBar owns status-bar/cutout insets. Applying them
        // here too creates a full extra status-bar band on every destination.
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            NavHost(
                navController = navController,
                startDestination = start,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                composable(Routes.CONNECT) {
                    ConnectScreen(
                        initialProfile = connectProfile,
                        onConnected = {
                            connectProfile = null
                            navController.navigate(TopDest.Chats.route) {
                                popUpTo(Routes.CONNECT) { inclusive = true }
                            }
                        },
                    )
                }
                composable(TopDest.Chats.route) {
                    ChatScreen(
                        initialShare = shareText,
                        initialShareImage = shareImage,
                        onInitialShareConsumed = onShareConsumed,
                        onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                        onNeedConnection = { navController.navigate(Routes.CONNECT) },
                    )
                }
                composable(
                    route = "chat?resume={resume}",
                    arguments = listOf(navArgument("resume") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    ChatScreen(
                        resumeSessionId = entry.arguments?.getString("resume")?.ifBlank { null },
                        initialShare = shareText,
                        initialShareImage = shareImage,
                        onInitialShareConsumed = onShareConsumed,
                        onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                        onNeedConnection = { navController.navigate(Routes.CONNECT) },
                    )
                }
                composable(TopDest.Activity.route) {
                    ActivityScreen(
                        onOpen = { type ->
                            when {
                                type.contains("pair", ignoreCase = true) ->
                                    navController.navigate(Routes.PAIRING)
                                type.contains("cron", ignoreCase = true) ->
                                    navController.navigate(Routes.CRON)
                                type.contains("gateway", ignoreCase = true) ->
                                    navController.navigate(Routes.SYSTEM)
                                type.contains("chat", ignoreCase = true) ||
                                    type.contains("pty", ignoreCase = true) -> {
                                    currentTop = TopDest.Chats.route
                                    navController.navigate(TopDest.Chats.route)
                                }
                                else -> navController.navigate(TopDest.Manage.route)
                            }
                        },
                    )
                }
                composable(TopDest.Manage.route) {
                    ManageHomeScreen(onOpen = { navController.navigate(it) })
                }
                composable(TopDest.You.route) {
                    YouScreen(
                        onConnect = { navController.navigate(Routes.CONNECT) },
                        onOpenNotificationSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETTINGS) { NotificationSettingsScreen() }
                composable(Routes.STATUS) {
                    StatusScreen(onOpenSession = { navController.navigate(Routes.sessionDetail(it)) })
                }
                composable(Routes.COMMAND_CENTER) { CommandCenterScreen { navController.navigate(Routes.SYSTEM) } }
                composable(Routes.CONFIG) { ConfigScreen() }
                composable(Routes.API_KEYS) { ApiKeysScreen() }
                composable(Routes.SESSIONS) {
                    SessionsScreen(
                        onOpen = { navController.navigate(Routes.sessionDetail(it)) },
                        onResume = { navController.navigate(Routes.chat(it)) },
                    )
                }
                composable(
                    Routes.SESSION_DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    SessionDetailScreen(
                        sessionId = entry.arguments!!.getString("id")!!,
                        onDeleted = { navController.popBackStack() },
                    )
                }
                composable(Routes.LOGS) { LogsScreen() }
                composable(Routes.TERMINAL) { TerminalScreen(onNeedConnection = { navController.navigate(Routes.CONNECT) }) }
                composable(Routes.THEMES) { ThemeScreen() }
                composable(Routes.ANALYTICS) { AnalyticsScreen() }
                composable(Routes.CRON) { CronScreen() }
                composable(Routes.PROFILES) {
                    ProfilesScreen(onShortcut = { navController.navigate(it) })
                }
                composable(Routes.SKILLS) { SkillsScreen() }
                composable(Routes.MCP) { McpScreen() }
                composable(Routes.WEBHOOKS) { WebhooksScreen() }
                composable(Routes.PAIRING) { PairingScreen() }
                composable(Routes.CHANNELS) { ChannelsScreen() }
                composable(Routes.SYSTEM) { SystemScreen() }
                composable(Routes.MEMORY) { MemoryScreen() }
                composable(Routes.CURATOR) { CuratorScreen() }
                composable(Routes.FILES) { FilesScreen() }
                composable(Routes.REVIEW) { ReviewScreen(onOpenFile = { _ -> navController.navigate(Routes.FILES) }) }
                composable(Routes.MODELS) { ModelsScreen() }
                composable(Routes.LEARNING) { LearningScreen() }
                composable(Routes.ARTIFACTS) {
                    ArtifactsScreen(onOpenSession = { id ->
                        navController.navigate(Routes.sessionDetail(id)) { launchSingleTop = true }
                    })
                }
            }
        }
    }
    }
    }
}

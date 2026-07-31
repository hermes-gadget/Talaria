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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.feature.activity.ActivityScreen
import com.nousresearch.talaria.feature.chat.ChatScreen
import com.nousresearch.talaria.feature.connection.ConnectScreen
import com.nousresearch.talaria.feature.manage.ManageHomeScreen
import com.nousresearch.talaria.feature.manage.analytics.AnalyticsScreen
import com.nousresearch.talaria.feature.manage.apikeys.ApiKeysScreen
import com.nousresearch.talaria.feature.manage.channels.ChannelsScreen
import com.nousresearch.talaria.feature.manage.config.ConfigScreen
import com.nousresearch.talaria.feature.manage.cron.CronScreen
import com.nousresearch.talaria.feature.manage.logs.LogsScreen
import com.nousresearch.talaria.feature.manage.mcp.McpScreen
import com.nousresearch.talaria.feature.manage.pairing.PairingScreen
import com.nousresearch.talaria.feature.manage.profiles.ProfilesScreen
import com.nousresearch.talaria.feature.manage.sessions.SessionDetailScreen
import com.nousresearch.talaria.feature.manage.sessions.SessionsScreen
import com.nousresearch.talaria.feature.manage.skills.SkillsScreen
import com.nousresearch.talaria.feature.manage.status.StatusScreen
import com.nousresearch.talaria.feature.manage.system.SystemScreen
import com.nousresearch.talaria.feature.manage.webhooks.WebhooksScreen
import com.nousresearch.talaria.feature.you.PrivacyScreen
import com.nousresearch.talaria.feature.you.YouScreen
import com.nousresearch.talaria.ui.components.ProfileSwitcherBar

@Composable
fun TalariaNavRoot(
    shareText: String?,
    deepLink: String?,
    onShareConsumed: () -> Unit,
    onDeepLinkConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val profiles by TalariaApp.instance.container.connectionStore.profiles.collectAsState()
    val start = if (profiles.isEmpty()) Routes.CONNECT else TopDest.Chats.route
    var currentTop by remember { mutableStateOf(TopDest.Chats.route) }

    LaunchedEffect(shareText) {
        if (!shareText.isNullOrBlank()) {
            navController.navigate(Routes.chat()) { launchSingleTop = true }
            onShareConsumed()
        }
    }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        when {
            link.contains("pairing") -> navController.navigate(Routes.PAIRING)
            link.contains("connect") -> navController.navigate(Routes.CONNECT)
            link.contains("session/") -> {
                val id = link.substringAfter("session/").substringBefore('?')
                navController.navigate("session/$id")
            }
            else -> navController.navigate(Routes.chat())
        }
        onDeepLinkConsumed()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    NavigationSuiteScaffold(
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
        // Screens apply status/cutout insets via TopAppBar; suite handles bottom system bars.
        Column(modifier = Modifier.fillMaxSize()) {
            if (profiles.isNotEmpty() && currentTop != TopDest.Chats.route) {
                // Global management-profile switcher (web sidebar parity). Hidden on Chat
                // top-level to keep composer chrome clean; Manage/You/Activity still show it.
                // Chat reconnects pick up SecureConnectionStore.managementProfile on next connect.
                ProfileSwitcherBar()
            }
            NavHost(
                navController = navController,
                startDestination = start,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                composable(Routes.CONNECT) {
                    ConnectScreen(
                        onConnected = {
                            navController.navigate(TopDest.Chats.route) {
                                popUpTo(Routes.CONNECT) { inclusive = true }
                            }
                        },
                    )
                }
                composable(TopDest.Chats.route) {
                    ChatScreen(
                        initialShare = shareText,
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
                        onOpenSessions = { navController.navigate(Routes.SESSIONS) },
                        onNeedConnection = { navController.navigate(Routes.CONNECT) },
                    )
                }
                composable(TopDest.Activity.route) { ActivityScreen() }
                composable(TopDest.Manage.route) {
                    ManageHomeScreen(onOpen = { navController.navigate(it) })
                }
                composable(TopDest.You.route) {
                    YouScreen(
                        onConnect = { navController.navigate(Routes.CONNECT) },
                        onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    )
                }
                composable(Routes.STATUS) {
                    StatusScreen(onOpenSession = { navController.navigate("session/$it") })
                }
                composable(Routes.CONFIG) { ConfigScreen() }
                composable(Routes.API_KEYS) { ApiKeysScreen() }
                composable(Routes.SESSIONS) {
                    SessionsScreen(
                        onOpen = { navController.navigate("session/$it") },
                        onResume = { navController.navigate(Routes.chat(it)) },
                    )
                }
                composable(
                    Routes.SESSION_DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    SessionDetailScreen(sessionId = entry.arguments!!.getString("id")!!)
                }
                composable(Routes.LOGS) { LogsScreen() }
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
                composable(Routes.PRIVACY) { PrivacyScreen() }
            }
        }
    }
    }
}

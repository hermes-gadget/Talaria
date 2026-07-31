package com.nousresearch.talaria.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.nousresearch.talaria.feature.manage.sessions.SessionDetailScreen
import com.nousresearch.talaria.feature.manage.sessions.SessionsScreen
import com.nousresearch.talaria.feature.you.PrivacyScreen
import com.nousresearch.talaria.feature.you.YouScreen
import com.nousresearch.talaria.ui.components.ScreenScaffold

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
            link.contains("connect") -> navController.navigate(Routes.CONNECT)
            link.contains("session/") -> {
                val id = link.substringAfter("session/").substringBefore('?')
                navController.navigate("session/$id")
            }
            else -> navController.navigate(Routes.chat())
        }
        onDeepLinkConsumed()
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            listOf(
                TopDest.Chats to Icons.Outlined.ChatBubbleOutline,
                TopDest.Activity to Icons.Outlined.NotificationsNone,
                TopDest.Manage to Icons.Outlined.ManageAccounts,
                TopDest.You to Icons.Outlined.PersonOutline,
            ).forEach { (dest, icon) ->
                item(
                    selected = currentTop == dest.route,
                    onClick = {
                        currentTop = dest.route
                        navController.navigate(dest.route) { launchSingleTop = true }
                    },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(dest.label) },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = start, modifier = Modifier.padding()) {
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
                ScreenScaffold("Manage", "More surfaces in a follow-up slice") {
                    androidx.compose.material3.TextButton(onClick = { navController.navigate(Routes.SESSIONS) }) {
                        Text("Sessions")
                    }
                }
            }
            composable(TopDest.You.route) {
                YouScreen(
                    onConnect = { navController.navigate(Routes.CONNECT) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                )
            }
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
            composable(Routes.PRIVACY) { PrivacyScreen() }
        }
    }
}

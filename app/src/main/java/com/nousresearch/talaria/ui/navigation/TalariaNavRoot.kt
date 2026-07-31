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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.feature.connection.ConnectScreen
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
    val start = if (profiles.isEmpty()) Routes.CONNECT else TopDest.You.route
    var currentTop by remember { mutableStateOf(TopDest.You.route) }

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
                        navController.navigate(TopDest.You.route) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                )
            }
            composable(TopDest.Chats.route) {
                ScreenScaffold("Chats", "Landing in a follow-up slice") { Text("Chat coming next") }
            }
            composable(TopDest.Activity.route) {
                ScreenScaffold("Activity", "") { Text("Activity coming next") }
            }
            composable(TopDest.Manage.route) {
                ScreenScaffold("Manage", "") { Text("Manage coming next") }
            }
            composable(TopDest.You.route) {
                YouScreen(
                    onConnect = { navController.navigate(Routes.CONNECT) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                )
            }
            composable(Routes.PRIVACY) { PrivacyScreen() }
        }
    }
}

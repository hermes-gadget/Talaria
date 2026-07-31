package com.nousresearch.talaria.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import com.nousresearch.talaria.ui.components.ScreenScaffold

@Composable
fun TalariaNavRoot(
    shareText: String?,
    deepLink: String?,
    onShareConsumed: () -> Unit,
    onDeepLinkConsumed: () -> Unit,
) {
    var currentTop by remember { mutableStateOf(TopDest.Chats.route) }
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
                    onClick = { currentTop = dest.route },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(dest.label) },
                )
            }
        },
    ) {
        ScreenScaffold("Talaria", "Scaffold — connect your Hermes instance in a follow-up slice") {
            Text("Shell ready. Features arrive in stacked PRs.")
        }
    }
}

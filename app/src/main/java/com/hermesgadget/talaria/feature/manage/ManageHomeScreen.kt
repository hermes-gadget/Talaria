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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.R
import com.hermesgadget.talaria.ui.components.ScreenScaffold
import com.hermesgadget.talaria.ui.theme.LocalSpacing

private data class LocalizedManageItem(
    val section: String,
    val item: ManageItem,
    val title: String,
    val subtitle: String,
)

/**
 * Manage home: one row per category. Categories open a sub-list
 * ([ManageSectionScreen]); the search action reaches any destination directly.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ManageHomeScreen(onOpen: (String) -> Unit, onOpenSection: (String) -> Unit) {
    val spacing = LocalSpacing.current
    var paletteOpen by remember { mutableStateOf(false) }

    if (paletteOpen) {
        CommandPalette(
            onDismiss = { paletteOpen = false },
            onOpen = { route ->
                paletteOpen = false
                onOpen(route)
            },
        )
    }

    ScreenScaffold(
        stringResource(R.string.manage_title),
        showProfileSwitcher = true,
        actions = {
            IconButton(onClick = { paletteOpen = true }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.manage_search_settings),
                )
            }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(manageSections, key = { it.id }) { section ->
                // The preview line doubles as the section's description, so the
                // extra navigation level still shows what lives behind each row.
                val preview = section.items
                    .map { stringResource(it.titleRes) }
                    .joinToString(" · ")
                NavigationRow(
                    icon = section.icon,
                    title = stringResource(section.titleRes),
                    subtitle = preview,
                    onClick = { onOpenSection(section.id) },
                )
            }
        }
    }
}

/** The drill-down list for a single Manage category. */
@Composable
fun ManageSectionScreen(sectionId: String?, onOpen: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val section = manageSection(sectionId)
    if (section == null) {
        ScreenScaffold(stringResource(R.string.manage_title)) {
            Text(
                stringResource(R.string.manage_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    ScreenScaffold(stringResource(section.titleRes), showProfileSwitcher = true) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(section.items, key = { it.route }) { item ->
                NavigationRow(
                    icon = item.icon,
                    title = stringResource(item.titleRes),
                    subtitle = stringResource(item.subtitleRes),
                    onClick = { onOpen(item.route) },
                )
            }
        }
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Quick-jump command palette (roadmap 15.7). A searchable, fuzzy-filtered list of
 * every Manage destination so users can reach any settings screen in two taps
 * instead of walking the category tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandPalette(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = mutableListOf<LocalizedManageItem>()
    for (section in manageSections) {
        for (item in section.items) {
            all += LocalizedManageItem(
                section = stringResource(section.titleRes),
                item = item,
                title = stringResource(item.titleRes),
                subtitle = stringResource(item.subtitleRes),
            )
        }
    }
    val q = query.trim()
    val results = if (q.isEmpty()) {
        all
    } else {
        all.filter { result ->
            result.title.contains(q, ignoreCase = true) ||
                result.subtitle.contains(q, ignoreCase = true) ||
                result.section.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.manage_jump_to_setting)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (results.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.manage_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(results, key = { it.item.route }) { result ->
                val item = result.item
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item.route) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${result.section} · ${result.subtitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

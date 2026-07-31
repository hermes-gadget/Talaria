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
package com.nousresearch.talaria.feature.manage.skills

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SkillInfo
import com.nousresearch.talaria.domain.model.ToolsetInfo
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun SkillsScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    val context = LocalContext.current
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    var toolsets by remember { mutableStateOf<List<ToolsetInfo>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reloadSkills() = scope.launch {
        repo.getSkills()
            .onSuccess { skills = it }
            .onFailure { message = it.message }
    }
    fun reloadToolsets() = scope.launch {
        repo.getToolsets()
            .onSuccess { toolsets = it }
            .onFailure { message = it.message }
    }

    LaunchedEffect(Unit) {
        reloadSkills()
        reloadToolsets()
    }

    val categories = remember(skills) {
        skills.mapNotNull { it.category?.ifBlank { null } }.distinct().sorted()
    }
    val filteredSkills = remember(skills, query, category) {
        skills.filter { skill ->
            val q = query.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                skill.name.lowercase().contains(q) ||
                skill.description.orEmpty().lowercase().contains(q)
            val matchesCat = category == null || skill.category == category
            matchesQuery && matchesCat
        }
    }

    ScreenScaffold("Skills", "Skills & toolsets", actions = {
        TextButton(onClick = {
            when (tab) {
                0 -> reloadSkills()
                1 -> reloadToolsets()
                else -> Unit
            }
        }) { Text("Refresh") }
    }) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Skills") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Toolsets") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Hub") })
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (tab == 2) {
            Text(
                "Hermes Skills Hub install APIs are not stable in the dashboard yet. " +
                    "Browse docs in a Custom Tab, then install skills on the host.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            OutlinedButton(
                onClick = {
                    CustomTabsIntent.Builder().build().launchUrl(
                        context,
                        Uri.parse("https://hermes-agent.nousresearch.com/docs/user-guide/features/skills"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Browse Skills Hub docs") }
        } else if (tab == 0) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search skills") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                singleLine = true,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("All") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            LazyColumn {
                items(filteredSkills, key = { it.name }) { skill ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.titleLarge)
                                Text(skill.description ?: "")
                                skill.category?.let {
                                    Text(it, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Switch(
                                checked = skill.enabled == true,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        repo.toggleSkill(skill.name, enabled)
                                        reloadSkills()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                items(toolsets, key = { it.name }) { ts ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ts.label ?: ts.name, style = MaterialTheme.typography.titleMedium)
                                ts.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (ts.tools.isNotEmpty()) {
                                    Text(
                                        ts.tools.joinToString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val on = ts.enabled ?: ts.active ?: false
                            Switch(
                                checked = on,
                                enabled = ts.available != false,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        repo.setToolsetEnabled(ts.name, enabled)
                                            .onSuccess { reloadToolsets() }
                                            .onFailure { message = it.message }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

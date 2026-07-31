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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.domain.model.SkillInfo
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

@Composable
fun SkillsScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun reload() = scope.launch { repo.getSkills().onSuccess { skills = it } }
    LaunchedEffect(Unit) { reload() }
    ScreenScaffold("Skills", "Toggle installed skills", actions = {
        TextButton(onClick = { reload() }) { Text("Refresh") }
    }) {
        LazyColumn {
            items(skills, key = { it.name }) { skill ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleLarge)
                            Text(skill.description ?: "")
                            Text(skill.category ?: "", style = MaterialTheme.typography.labelLarge)
                        }
                        Switch(
                            checked = skill.enabled == true,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    repo.toggleSkill(skill.name, enabled)
                                    reload()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

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


package com.nousresearch.talaria.feature.manage.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nousresearch.talaria.TalariaApp
import com.nousresearch.talaria.core.network.JsonConfig
import com.nousresearch.talaria.ui.components.ScreenScaffold
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import androidx.compose.ui.Modifier

@Composable
fun ConfigScreen() {
    val repo = TalariaApp.instance.container.hermesRepository
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        repo.getConfig().onSuccess {
            text = JsonConfig.json.encodeToString(it)
        }.onFailure { message = it.message }
    }
    ScreenScaffold("Config", "Raw JSON editor for config.yaml") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp)
                .verticalScroll(rememberScrollState()),
            minLines = 16,
        )
        message?.let { Text(it) }
        Button(onClick = {
            scope.launch {
                runCatching {
                    val obj = JsonConfig.json.parseToJsonElement(text).jsonObject
                    repo.putConfig(obj).getOrThrow()
                    message = "Saved"
                }.onFailure { message = it.message }
            }
        }) { Text("Save") }
    }
}

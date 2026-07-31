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


package com.nousresearch.talaria.feature.manage.analytics

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nousresearch.talaria.domain.model.AnalyticsUsage
import com.nousresearch.talaria.feature.manage.SimpleManageViewModel
import com.nousresearch.talaria.ui.components.ErrorBox
import com.nousresearch.talaria.ui.components.KeyValueList
import com.nousresearch.talaria.ui.components.LoadingBox
import com.nousresearch.talaria.ui.components.ScreenScaffold

@Composable
fun AnalyticsScreen(
    vm: SimpleManageViewModel = viewModel(factory = SimpleManageViewModel.factory { getAnalytics(30) }),
) {
    val ui by vm.ui.collectAsState()
    ScreenScaffold("Analytics", "Last 30 days", actions = {
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }) {
        when {
            ui.loading -> LoadingBox()
            ui.error != null -> ErrorBox(ui.error!!, vm::refresh)
            else -> {
                val a = ui.data as AnalyticsUsage
                KeyValueList(
                    listOf(
                        "Sessions" to (a.session_count?.toString() ?: "—"),
                        "Input tokens" to (a.total_input_tokens?.toString() ?: "—"),
                        "Output tokens" to (a.total_output_tokens?.toString() ?: "—"),
                        "Cost" to (a.total_cost?.toString() ?: "—"),
                    ),
                )
            }
        }
    }
}

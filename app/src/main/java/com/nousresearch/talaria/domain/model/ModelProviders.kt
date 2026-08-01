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
package com.nousresearch.talaria.domain.model

import kotlinx.serialization.Serializable

/** A provider entry from `/api/model/options` (`providers[]`). */
@Serializable
data class ModelProvider(
    val slug: String = "",
    val name: String = "",
    val is_current: Boolean = false,
    val is_user_defined: Boolean = false,
    val models: List<String> = emptyList(),
    val total_models: Int = 0,
    val source: String? = null,
    val authenticated: Boolean = true,
    val auth_type: String? = null,
    val warning: String? = null,
)

@Serializable
data class ModelOptionsResponse(
    val providers: List<ModelProvider> = emptyList(),
)

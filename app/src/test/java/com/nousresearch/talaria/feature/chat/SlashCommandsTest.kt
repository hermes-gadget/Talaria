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

package com.nousresearch.talaria.feature.chat

import com.nousresearch.talaria.domain.model.SlashCommand
import com.nousresearch.talaria.domain.model.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    private val catalog = listOf(
        SlashCommand("/help", "Show commands"),
        SlashCommand("/model", "Switch model"),
        SlashCommand("/compress", "Compress context", aliases = listOf("/compact")),
        SlashCommand("/research", "Search sources", category = "Skills"),
    )

    @Test
    fun prefixMatchesRankBeforeDescriptionMatches() {
        assertEquals("/model", SlashCommands.suggest("/mo", catalog).first().command)
    }

    @Test
    fun fuzzySubsequenceSupportsPredictiveTyping() {
        assertEquals("/model", SlashCommands.suggest("/mdl", catalog).single().command)
    }

    @Test
    fun aliasesFindCanonicalCommand() {
        assertEquals("/compress", SlashCommands.suggest("/compa", catalog).first().command)
    }

    @Test
    fun bareSlashBrowsesCatalogWithinLimit() {
        val suggestions = SlashCommands.suggest("/", catalog, limit = 3)
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.all { it.command.startsWith('/') })
    }

    @Test
    fun argumentsAreLeftToLiveSidecarCompletion() {
        assertTrue(SlashCommands.suggest("/model ant", catalog).isEmpty())
    }
}

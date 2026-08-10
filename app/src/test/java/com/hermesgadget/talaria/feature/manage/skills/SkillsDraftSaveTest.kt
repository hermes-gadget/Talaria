package com.hermesgadget.talaria.feature.manage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsDraftSaveTest {
    @Test
    fun `toolset drafts clear only after success and preserve newer values`() {
        val submitted = mapOf("TOKEN" to "old")
        assertEquals(
            submitted,
            clearSubmittedToolsetDrafts(submitted, submitted, succeeded = false),
        )
        assertEquals(
            mapOf("TOKEN" to "new"),
            clearSubmittedToolsetDrafts(mapOf("TOKEN" to "new"), submitted, succeeded = true),
        )
        assertTrue(clearSubmittedToolsetDrafts(submitted, submitted, succeeded = true).isEmpty())
    }

    @Test
    fun `content editor closes only when the submitted draft is still current`() {
        val submitted = SkillContentFields("skill", "old", "body")
        assertTrue(shouldCloseSkillEditorAfterSave(submitted, submitted, succeeded = true))
        assertFalse(
            shouldCloseSkillEditorAfterSave(
                submitted,
                submitted.copy(body = "new"),
                succeeded = true,
            ),
        )
        assertFalse(shouldCloseSkillEditorAfterSave(submitted, submitted, succeeded = false))
    }
}

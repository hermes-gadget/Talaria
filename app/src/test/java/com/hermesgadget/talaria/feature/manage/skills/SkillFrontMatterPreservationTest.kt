/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.manage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B66: editing a skill's body/description must not destroy YAML metadata
 * it doesn't understand. The rebuild patches keys INSIDE the original
 * front-matter block instead of regenerating a bare name/description pair.
 */
class SkillFrontMatterPreservationTest {

    @Test
    fun `multiline yaml metadata survives an ordinary body edit`() {
        val original = """---
name: deploy-notes
description: Post-deploy summaries
version: 3
owner:
  team: platform
  handle: ben
---

Body text here.
"""
        val fields = parseSkillContent(original, "deploy-notes")
        assertEquals("Post-deploy summaries", fields.description)

        val rebuilt = buildSkillContent(fields.copy(body = "New body."))
        assertTrue("version survived", rebuilt.contains("version: 3"))
        assertTrue("nested owner block survived", rebuilt.contains("  team: platform"))
        assertTrue("name patched inside", rebuilt.contains("name: \"deploy-notes\""))
        assertTrue("body updated", rebuilt.contains("New body."))
    }

    @Test
    fun `renaming patches the existing name key without duplicating it`() {
        val original = """---
name: old-name
description: d
aliases:
  - old-name
---

b
"""
        val fields = parseSkillContent(original, "old-name")
        val rebuilt = buildSkillContent(fields.copy(name = "new-name"))
        assertTrue(rebuilt.contains("name: \"new-name\""))
        assertEquals(1, rebuilt.lines().count { it.trimStart().startsWith("name:") })
        assertTrue("aliases survived", rebuilt.contains("aliases:"))
    }

    @Test
    fun `file without front matter still gets a fresh header`() {
        val rebuilt = buildSkillContent(SkillContentFields(name = "n", description = "d", body = "b"))
        assertTrue(rebuilt.startsWith("---\nname: \"n\"\ndescription: \"d\"\n---"))
    }
}

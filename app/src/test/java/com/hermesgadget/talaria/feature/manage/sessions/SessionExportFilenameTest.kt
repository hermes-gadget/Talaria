package com.hermesgadget.talaria.feature.manage.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B64: session export filenames must be scoped to the origin (profile/base
 * URL) and each export attempt, so exports from different servers/profiles
 * (or repeated exports) never overwrite each other in the shared dir.
 */
class SessionExportFilenameTest {

    @Test
    fun differentScopesProduceDifferentNames() {
        val a = safeSessionExportFilename("sess-1", scope = "profileA|https://one.example", attempt = 0)
        val b = safeSessionExportFilename("sess-1", scope = "profileB|https://two.example", attempt = 0)
        assertNotEquals(a, b)
        assertTrue(a.endsWith(".md"))
    }

    @Test
    fun sameScopeDifferentAttemptsDiffer() {
        val a = safeSessionExportFilename("sess-1", scope = "p|u", attempt = 1_000)
        val b = safeSessionExportFilename("sess-1", scope = "p|u", attempt = 2_000)
        assertNotEquals(a, b)
    }

    @Test
    fun noArgsBackwardCompatibleStable() {
        assertEquals(
            safeSessionExportFilename("abc"),
            safeSessionExportFilename("abc"),
        )
    }

    @Test
    fun hostileSessionIdIsSanitized() {
        val name = safeSessionExportFilename("../../etc/passwd")
        assertTrue(!name.contains('/'))
        assertTrue(name.startsWith("session-") && name.endsWith(".md"))
    }
}

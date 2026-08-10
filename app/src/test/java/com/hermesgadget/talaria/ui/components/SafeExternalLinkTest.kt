package com.hermesgadget.talaria.ui.components

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafeExternalLinkTest {
    @Test
    fun `accepts hierarchical http and https links`() {
        assertNotNull(safeExternalWebUri("https://docs.example.test/path?q=1"))
        assertNotNull(safeExternalWebUri("HTTP://example.test"))
    }

    @Test
    fun `rejects non-web and opaque links`() {
        assertNull(safeExternalWebUri("intent://settings#Intent;end"))
        assertNull(safeExternalWebUri("talaria://chat"))
        assertNull(safeExternalWebUri("mailto:admin@example.test"))
        assertNull(safeExternalWebUri("https:docs.example.test"))
        assertNull(safeExternalWebUri("https://"))
    }
}

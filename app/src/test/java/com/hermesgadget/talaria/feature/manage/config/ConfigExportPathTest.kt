package com.hermesgadget.talaria.feature.manage.config

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B51: config export writes through the FileProvider instead of a Binder
 * EXTRA_TEXT. The cache/exports path must exist under the provider's
 * declared roots so the URI grant actually resolves at runtime.
 */
class ConfigExportPathTest {

    @Test
    fun exportDirectoryIsProviderMapped() {
        val filePathsXml = File(
            "src/main/res/xml/file_paths.xml",
        )
        assertTrue("file_paths.xml must exist", filePathsXml.exists())
        val text = filePathsXml.readText()
        assertTrue(
            "cache/exports must be mapped for FileProvider export URIs",
            text.contains("name=\"exports\"") && text.contains("exports/"),
        )
    }
}

/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.notifications

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the notification small-icon regression (HermesMind #61): the status
 * bar renders only the alpha channel of a ~16-24dp glyph, so notification
 * builders MUST use the full-bleed monochrome `ic_notification_small` vector.
 * Re-pointing a builder at the launcher adaptive icon (`ic_launcher_monochrome`,
 * padded safe zone) makes the icon render tiny and this test fails.
 */
class NotificationSmallIconTest {

    private val mainSrc: String = when {
        File("app/src/main/java").isDirectory -> "app/src/main/java" // project root
        File("src/main/java").isDirectory -> "src/main/java" // module dir (gradle unit tests)
        File("../app/src/main/java").isDirectory -> "../app/src/main/java"
        else -> "app/src/main/java"
    }

    private fun sourceFiles(): List<File> = listOf(
        File(mainSrc, "com/hermesgadget/talaria/core/notifications/TalariaNotifier.kt"),
        File(mainSrc, "com/hermesgadget/talaria/car/CarConversationNotifier.kt"),
    )

    @Test
    fun everyNotificationBuilderUsesTheFullBleedSmallIcon() {
        for (file in sourceFiles()) {
            assertTrue("missing source: $file", file.exists())
            val text = file.readText()
            val smallIconSites = Regex("""\.setSmallIcon\(R\.drawable\.\w+\)""")
                .findAll(text)
                .map { it.value }
                .toList()
            assertTrue("no setSmallIcon calls in $file", smallIconSites.isNotEmpty())
            for (site in smallIconSites) {
                assertTrue(
                    "$file uses $site — notification small icons must be the " +
                        "full-bleed ic_notification_small vector (status bar alpha rendering)",
                    site.contains("ic_notification_small"),
                )
            }
        }
    }

    @Test
    fun launcherMonochromeIsNeverUsedAsNotificationIcon() {
        for (file in sourceFiles()) {
            val text = file.readText()
            assertFalse(
                "$file re-pointed a notification builder at the padded launcher icon",
                Regex("""\.setSmallIcon\(R\.drawable\.ic_launcher_monochrome\)""")
                    .containsMatchIn(text),
            )
        }
    }
}

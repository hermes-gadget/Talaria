/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.manage.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareFileManagerTest {
    @Test
    fun `streamed share file reports progress and exposes no partial`() {
        val root = tempDirectory()
        try {
            val progress = mutableListOf<Long>()
            val manager = ShareFileManager(
                cacheDirectory = root,
                nowMillis = { 1_000L },
                ownerId = "process-a",
            )

            val file = manager.createShareFile(
                prefix = "test-",
                suffix = ".bin",
                source = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                declaredBytes = 3,
                onProgress = { copied, _ -> progress += copied },
            )

            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertTrue(progress.last() == 3L)
            assertTrue(root.walkTopDown().none { it.name.endsWith(".partial") })
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `declared and streamed oversize payloads leave no orphan`() {
        val root = tempDirectory()
        try {
            val manager = ShareFileManager(root, ownerId = "process-a")
            assertThrows(IllegalArgumentException::class.java) {
                manager.createShareFile(
                    prefix = "test-",
                    suffix = ".bin",
                    source = ByteArrayInputStream(byteArrayOf(1)),
                    declaredBytes = MAX_SHARE_FILE_BYTES + 1,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.createShareFile(
                    prefix = "test-",
                    suffix = ".bin",
                    source = CountingInputStream(MAX_SHARE_FILE_BYTES + 1),
                )
            }

            assertTrue(root.walkTopDown().none { it.isFile })
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `cancellation deletes the owned partial`() {
        val root = tempDirectory()
        try {
            val manager = ShareFileManager(root, ownerId = "process-a")
            var reads = 0

            assertThrows(CancellationException::class.java) {
                manager.createManagedDownload(
                    prefix = "test-",
                    suffix = ".download",
                    source = CountingInputStream(10),
                    beforeRead = {
                        reads += 1
                        if (reads == 2) throw CancellationException("cancel")
                    },
                )
            }

            assertTrue(root.walkTopDown().none { it.isFile })
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `managed download cap can be raised per surface while default stays bounded`() {
        val root = tempDirectory()
        try {
            val manager = ShareFileManager(root, ownerId = "process-a")

            assertThrows(IllegalArgumentException::class.java) {
                manager.createManagedDownload(
                    prefix = "test-",
                    suffix = ".download",
                    source = ByteArrayInputStream(byteArrayOf(1)),
                    declaredBytes = MAX_MANAGED_DOWNLOAD_BYTES + 1,
                )
            }

            val backup = manager.createManagedDownload(
                prefix = "backup-",
                suffix = ".zip",
                source = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                declaredBytes = 3,
                maxBytes = MAX_OPS_BACKUP_DOWNLOAD_BYTES,
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), backup.readBytes())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `restart sweep removes prior transfer owner but retains chooser file until ttl`() {
        val root = tempDirectory()
        try {
            var clock = 100L
            val oldProcess = ShareFileManager(
                cacheDirectory = root,
                nowMillis = { clock },
                ttlMillis = 100L,
                ownerId = "process-a",
            )
            val transfer = oldProcess.createManagedDownload(
                prefix = "test-",
                suffix = ".download",
                source = ByteArrayInputStream(byteArrayOf(1)),
            )
            val chooserFile = oldProcess.createShareFile(
                prefix = "test-",
                suffix = ".bin",
                bytes = byteArrayOf(2),
            )

            clock = 199L
            ShareFileManager(
                cacheDirectory = root,
                nowMillis = { clock },
                ttlMillis = 100L,
                ownerId = "process-b",
            )
            assertFalse(transfer.exists())
            assertTrue(chooserFile.exists())

            clock = 200L
            ShareFileManager(
                cacheDirectory = root,
                nowMillis = { clock },
                ttlMillis = 100L,
                ownerId = "process-c",
            )
            assertFalse(chooserFile.exists())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `fresh chooser files consume bounded cache capacity without eviction`() {
        val root = tempDirectory()
        try {
            val manager = ShareFileManager(
                cacheDirectory = root,
                maxCacheBytes = 3,
                maxCacheFiles = 2,
                ownerId = "process-a",
            )
            val first = manager.createShareFile("test-", ".bin", byteArrayOf(1, 2, 3))

            assertThrows(IllegalStateException::class.java) {
                manager.createShareFile("test-", ".bin", byteArrayOf(4))
            }
            assertTrue(first.exists())
            assertTrue(root.walkTopDown().filter(File::isFile).count() == 1)
        } finally {
            deleteTree(root)
        }
    }

    private fun tempDirectory() = Files.createTempDirectory("talaria-share-test").toFile()

    private fun deleteTree(root: java.io.File) {
        if (!root.exists()) return
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class CountingInputStream(private var remaining: Long) : InputStream() {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }

        override fun read(): Int = if (remaining == 0L) -1 else {
            remaining -= 1
            0
        }
    }
}

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
package com.hermesgadget.talaria.feature.manage.files

import java.io.File

internal const val SHARE_FILE_TTL_MILLIS = 15L * 60L * 1000L
internal const val SHARE_CACHE_LIMIT_BYTES = 32L * 1024L * 1024L
internal const val MAX_SHARE_FILE_BYTES = 16L * 1024L * 1024L

/**
 * Owns files handed to Android's share chooser.
 *
 * A chooser may outlive the screen that created it, so files are retained for
 * a short grace period. The mtime is the durable fallback expiry marker after
 * a process restart; the in-memory map gives newly-created files an explicit
 * expiry until the next cleanup pass.
 */
class ShareFileManager(
    private val cacheDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SHARE_FILE_TTL_MILLIS,
    private val maxCacheBytes: Long = SHARE_CACHE_LIMIT_BYTES,
) {
    private val expirations = mutableMapOf<String, Long>()

    init {
        cleanupStaleFiles()
    }

    /** Creates a bounded, tracked file in the provider-readable cache path. */
    @Synchronized
    fun createShareFile(prefix: String, suffix: String, bytes: ByteArray): File {
        require(bytes.size.toLong() <= MAX_SHARE_FILE_BYTES) {
            "Share file exceeds the ${MAX_SHARE_FILE_BYTES / (1024 * 1024)} MiB limit"
        }

        val now = nowMillis()
        cleanupStaleFilesLocked(now)
        val directory = File(cacheDirectory, SHARE_DIRECTORY).apply { mkdirs() }
        val safePrefix = prefix.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(24)
            .ifBlank { "share-" }
            .let { if (it.length >= 3) it else it.padEnd(3, '-') }
        val safeSuffix = suffix
            .filter { it.isLetterOrDigit() || it in setOf('.', '-', '_') }
            .take(80)
            .ifBlank { ".share" }
            .let { if (it.startsWith('.')) it else ".$it" }
        val file = File.createTempFile(safePrefix, safeSuffix, directory)
        try {
            file.outputStream().use { output -> output.write(bytes) }
            file.setLastModified(now)
            expirations[file.absolutePath] = now + ttlMillis
            enforceCacheLimitLocked()
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Deletes stale files from current and legacy share directories. */
    @Synchronized
    fun cleanupStaleFiles() {
        cleanupStaleFilesLocked(nowMillis())
    }

    private fun cleanupStaleFilesLocked(now: Long) {
        val files = managedFiles()
        files.forEach { file ->
            val expiry = expirations[file.absolutePath] ?: (file.lastModified() + ttlMillis)
            if (expiry <= now) {
                deleteTrackedFileLocked(file)
            }
        }
        expirations.keys.removeAll { path -> !File(path).exists() }
        enforceCacheLimitLocked()
    }

    private fun enforceCacheLimitLocked() {
        val files = managedFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxCacheBytes) break
            total -= file.length()
            deleteTrackedFileLocked(file)
        }
    }

    private fun managedFiles(): List<File> = LEGACY_SHARE_DIRECTORIES
        .asSequence()
        .map { File(cacheDirectory, it) }
        .filter(File::isDirectory)
        .flatMap { directory ->
            directory.walkTopDown().filter { file -> file.isFile }.asSequence()
        }
        .toList()

    private fun deleteTrackedFileLocked(file: File) {
        expirations.remove(file.absolutePath)
        file.delete()
    }

    private companion object {
        const val SHARE_DIRECTORY = "share-files"
        val LEGACY_SHARE_DIRECTORIES = listOf(
            SHARE_DIRECTORY,
            "artifacts",
            "files-share",
            "ops-backups",
            "ops-debug",
        )
    }
}

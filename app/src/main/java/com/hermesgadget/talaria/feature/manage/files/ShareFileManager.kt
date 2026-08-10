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

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val SHARE_FILE_TTL_MILLIS = 15L * 60L * 1000L
internal const val SHARE_CACHE_LIMIT_BYTES = 32L * 1024L * 1024L
internal const val SHARE_CACHE_LIMIT_FILES = 16
internal const val MAX_SHARE_FILE_BYTES = 16L * 1024L * 1024L

/** A generous cap for a managed file selected by the Files screen. */
internal const val MAX_MANAGED_DOWNLOAD_BYTES = 64L * 1024L * 1024L

/** Backups contain the session database, artifacts, and configuration. */
internal const val MAX_OPS_BACKUP_DOWNLOAD_BYTES = 512L * 1024L * 1024L

private const val PARTIAL_SUFFIX = ".partial"
private const val MANAGED_DOWNLOAD_DIRECTORY = "managed-downloads"
private const val MANAGED_PARTIAL_DIRECTORY = "managed-partials"
private val PROCESS_OWNER_ID = UUID.randomUUID().toString().replace('-', '_')

/**
 * Owns files handed to Android's share chooser and temporary transfer files.
 *
 * A chooser may outlive the screen or even the process that created it, so
 * completed share files are retained for a short grace period. Transfer files
 * carry a process-owner token in their name; a new process can remove old
 * partials and completed-but-unconsumed downloads without touching a chooser
 * file that is still within its retention window.
 */
class ShareFileManager(
    private val cacheDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SHARE_FILE_TTL_MILLIS,
    private val maxCacheBytes: Long = SHARE_CACHE_LIMIT_BYTES,
    private val maxCacheFiles: Int = SHARE_CACHE_LIMIT_FILES,
    private val ownerId: String = PROCESS_OWNER_ID,
) {
    private val expirations = mutableMapOf<String, Long>()
    private val ownerToken = ownerId.take(12)

    init {
        require(ttlMillis >= 0L) { "Share file TTL must not be negative" }
        require(maxCacheBytes >= 0L) { "Share file cache weight must not be negative" }
        require(maxCacheFiles > 0) { "Share file cache count must be positive" }
        cleanupStaleFiles()
    }

    /** Creates a bounded, tracked file in the provider-readable cache path. */
    @Synchronized
    fun createShareFile(prefix: String, suffix: String, bytes: ByteArray): File =
        createShareFile(
            prefix = prefix,
            suffix = suffix,
            source = ByteArrayInputStream(bytes),
            declaredBytes = bytes.size.toLong(),
        )

    /**
     * Streams a bounded payload into an owned partial and exposes it only
     * after the stream has completed successfully.
     */
    @Synchronized
    fun createShareFile(
        prefix: String,
        suffix: String,
        source: InputStream,
        declaredBytes: Long = -1L,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        beforeRead: () -> Unit = {},
    ): File = writeOwnedFile(
        directoryName = SHARE_DIRECTORY,
        prefix = prefix,
        suffix = suffix,
        source = source,
        declaredBytes = declaredBytes,
        maxBytes = MAX_SHARE_FILE_BYTES,
        onProgress = onProgress,
        beforeRead = beforeRead,
        enforceShareQuota = true,
    )

    /** Stream a file into the share cache without first duplicating its bytes. */
    @Synchronized
    fun createShareFile(
        prefix: String,
        suffix: String,
        source: File,
        declaredBytes: Long = source.length(),
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        beforeRead: () -> Unit = {},
    ): File = source.inputStream().use { input ->
        createShareFile(
            prefix = prefix,
            suffix = suffix,
            source = input,
            declaredBytes = declaredBytes,
            onProgress = onProgress,
            beforeRead = beforeRead,
        )
    }

    /**
     * Stream a managed download into a process-owned file. These files are
     * swept on the next process start and are never included in the chooser
     * cache quota.
     */
    @Synchronized
    fun createManagedDownload(
        prefix: String,
        suffix: String,
        source: InputStream,
        declaredBytes: Long = -1L,
        maxBytes: Long = MAX_MANAGED_DOWNLOAD_BYTES,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        beforeRead: () -> Unit = {},
    ): File = writeOwnedFile(
        directoryName = MANAGED_DOWNLOAD_DIRECTORY,
        prefix = prefix,
        suffix = suffix,
        source = source,
        declaredBytes = declaredBytes,
        maxBytes = maxBytes,
        onProgress = onProgress,
        beforeRead = beforeRead,
        enforceShareQuota = false,
    )

    /** Creates an empty owned partial for callers that need a custom writer. */
    @Synchronized
    fun createOwnedPartial(
        directoryName: String = MANAGED_PARTIAL_DIRECTORY,
        prefix: String = "transfer-",
        suffix: String = ".bin",
    ): File {
        val directory = File(cacheDirectory, directoryName).apply { mkdirs() }
        return File.createTempFile(
            "${safePrefix(prefix)}$ownerToken-",
            "${safeSuffix(suffix)}$PARTIAL_SUFFIX",
            directory,
        )
    }

    /** Deletes a file only when it is inside this manager's cache tree. */
    @Synchronized
    fun deleteOwnedFile(file: File?): Boolean {
        if (file == null || !isInsideCache(file)) return false
        expirations.remove(file.absolutePath)
        return !file.exists() || file.delete()
    }

    /** Deletes stale chooser files and transfer files from a prior process. */
    @Synchronized
    fun cleanupStaleFiles() {
        cleanupStaleFilesLocked(nowMillis())
    }

    private fun writeOwnedFile(
        directoryName: String,
        prefix: String,
        suffix: String,
        source: InputStream,
        declaredBytes: Long,
        maxBytes: Long,
        onProgress: (copied: Long, total: Long) -> Unit,
        beforeRead: () -> Unit,
        enforceShareQuota: Boolean,
    ): File {
        require(declaredBytes < 0L || declaredBytes <= maxBytes) {
            "Payload exceeds the ${maxBytes / (1024 * 1024)} MiB limit"
        }
        require(maxBytes >= 0L) { "Payload limit must not be negative" }

        val now = nowMillis()
        cleanupStaleFilesLocked(now)
        if (enforceShareQuota && !hasShareCapacityLocked(declaredBytes.takeIf { it >= 0L } ?: 0L)) {
            error("Share cache is full")
        }

        val partial = createOwnedPartial(directoryName, prefix, suffix)
        var completed = false
        try {
            source.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        beforeRead()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(copied <= maxBytes - count) {
                            "Payload exceeds the ${maxBytes / (1024 * 1024)} MiB limit"
                        }
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, declaredBytes)
                    }
                    onProgress(copied, declaredBytes)
                }
            }

            val completedFile = File(partial.parentFile, partial.name.removeSuffix(PARTIAL_SUFFIX))
            check(partial.renameTo(completedFile)) { "Could not finalize owned file" }
            completedFile.setLastModified(now)
            if (enforceShareQuota) {
                expirations[completedFile.absolutePath] = safeExpiry(now)
                enforceCacheLimitLocked(now)
                if (!hasShareCapacityLocked(0L, includesCandidate = true)) {
                    deleteTrackedFileLocked(completedFile)
                    error("Share cache is full")
                }
            }
            completed = true
            return completedFile
        } finally {
            if (!completed) {
                partial.delete()
            }
        }
    }

    private fun cleanupStaleFilesLocked(now: Long) {
        sweepTransferFilesLocked()
        shareFiles().forEach { file ->
            val expiry = expirations[file.absolutePath] ?: safeExpiry(file.lastModified())
            if (expiry <= now) deleteTrackedFileLocked(file)
        }
        expirations.keys.removeAll { path -> !File(path).exists() }
        enforceCacheLimitLocked(now)
    }

    /**
     * Transfer files are not chooser-owned. Preserve current-process files so
     * constructing a second manager cannot kill a live operation, but remove
     * every file owned by another process (or a legacy unmarked partial).
     */
    private fun sweepTransferFilesLocked() {
        TRANSFER_DIRECTORIES
            .map { File(cacheDirectory, it) }
            .filter(File::isDirectory)
            .flatMap { directory -> directory.walkTopDown().filter(File::isFile).toList() }
            .forEach { file ->
                if (!file.name.contains(ownerToken)) file.delete()
            }

        // Partial share files are never safe to expose to a chooser.
        allShareFiles()
            .filter { it.name.endsWith(PARTIAL_SUFFIX) && !it.name.contains(ownerToken) }
            .forEach { it.delete() }
    }

    private fun enforceCacheLimitLocked(now: Long) {
        val files = shareFiles()
        var total = files.sumOf { it.length() }
        var count = files.size
        // Only expired files may be evicted by a sweep. Fresh files may still
        // be held by ACTION_SEND grants, so a new creation fails closed when
        // all available capacity is protected by the chooser grace period.
        files.sortedBy { it.lastModified() }
            .filter { safeExpiry(it.lastModified()) <= now }
            .forEach { file ->
                if (total <= maxCacheBytes && count <= maxCacheFiles) return@forEach
                total -= file.length()
                count -= 1
                deleteTrackedFileLocked(file)
            }
    }

    private fun hasShareCapacityLocked(incomingBytes: Long, includesCandidate: Boolean = false): Boolean {
        if (incomingBytes < 0L || incomingBytes > maxCacheBytes) return false
        val files = shareFiles()
        val countWithinLimit = if (includesCandidate) {
            files.size <= maxCacheFiles
        } else {
            files.size < maxCacheFiles
        }
        return countWithinLimit &&
            files.sumOf { it.length() } <= maxCacheBytes - incomingBytes
    }

    private fun allShareFiles(): List<File> = LEGACY_SHARE_DIRECTORIES
        .asSequence()
        .map { File(cacheDirectory, it) }
        .filter(File::isDirectory)
        .flatMap { directory ->
            directory.walkTopDown().filter { file -> file.isFile }.asSequence()
        }
        .toList()

    private fun shareFiles(): List<File> = allShareFiles()
        .filterNot { it.name.endsWith(PARTIAL_SUFFIX) }
        .toList()

    private fun deleteTrackedFileLocked(file: File) {
        expirations.remove(file.absolutePath)
        file.delete()
    }

    private fun safeExpiry(storedAt: Long): Long = when {
        storedAt <= 0L -> ttlMillis
        Long.MAX_VALUE - storedAt < ttlMillis -> Long.MAX_VALUE
        else -> storedAt + ttlMillis
    }

    private fun isInsideCache(file: File): Boolean {
        val root = runCatching { cacheDirectory.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
    }

    private fun safePrefix(prefix: String): String = prefix
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(24)
        .ifBlank { "share-" }
        .let { if (it.length >= 3) it else it.padEnd(3, '-') }

    private fun safeSuffix(suffix: String): String = suffix
        .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        .take(80)
        .ifBlank { ".share" }
        .let { if (it.startsWith('.')) it else ".$it" }

    private companion object {
        const val SHARE_DIRECTORY = "share-files"
        val LEGACY_SHARE_DIRECTORIES = listOf(
            SHARE_DIRECTORY,
            "artifacts",
            "files-share",
            "ops-backups",
            "ops-debug",
            "exports",
        )
        val TRANSFER_DIRECTORIES = listOf(
            MANAGED_DOWNLOAD_DIRECTORY,
            MANAGED_PARTIAL_DIRECTORY,
        )
    }
}

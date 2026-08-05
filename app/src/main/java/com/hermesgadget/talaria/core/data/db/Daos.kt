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


package com.hermesgadget.talaria.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val DELETE_BATCH_SIZE = 500

@Dao
interface SessionDao {
    @Query("SELECT * FROM cached_sessions WHERE connectionId = :connectionId ORDER BY updatedAt DESC")
    fun observeSessions(connectionId: String): Flow<List<CachedSessionEntity>>

    @Query("SELECT * FROM cached_sessions WHERE connectionId = :connectionId")
    suspend fun getAll(connectionId: String): List<CachedSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedSessionEntity>)

    @Query("DELETE FROM cached_sessions WHERE connectionId = :connectionId")
    suspend fun clear(connectionId: String)

    @Query("DELETE FROM cached_sessions WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun deleteByIdsBatch(connectionId: String, ids: List<String>)

    /** Keep Room's bind-variable usage bounded for an authoritative full prune. */
    suspend fun deleteByIds(connectionId: String, ids: List<String>) {
        ids.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            deleteByIdsBatch(connectionId, batch)
        }
    }

    /**
     * Metadata-only transaction retained for DAO callers; repository
     * reconciliation uses [TalariaDatabase.reconcileSessionCache] so message
     * rows are deleted in the same cross-DAO transaction.
     */
    @Transaction
    suspend fun reconcile(
        connectionId: String,
        deleteIds: List<String>,
        upserts: List<CachedSessionEntity>,
    ) {
        if (deleteIds.isNotEmpty()) deleteByIds(connectionId, deleteIds)
        if (upserts.isNotEmpty()) upsertAll(upserts)
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE connectionId = :connectionId AND sessionId = :sessionId ORDER BY ordinal ASC")
    fun observeMessages(connectionId: String, sessionId: String): Flow<List<CachedMessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE connectionId = :connectionId AND sessionId = :sessionId ORDER BY ordinal ASC")
    suspend fun getSessionMessages(connectionId: String, sessionId: String): List<CachedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE connectionId = :connectionId AND sessionId = :sessionId")
    suspend fun clearSession(connectionId: String, sessionId: String)

    @Query("DELETE FROM cached_messages WHERE connectionId = :connectionId AND sessionId IN (:sessionIds)")
    suspend fun deleteBySessionIdsBatch(connectionId: String, sessionIds: List<String>)

    /** Delete transcript rows in bounded batches; SQLite has a finite bind limit. */
    suspend fun deleteBySessionIds(connectionId: String, sessionIds: List<String>) {
        sessionIds.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            deleteBySessionIdsBatch(connectionId, batch)
        }
    }

    /** Atomic full-transcript replace: no window where readers see half-old/half-new rows. */
    @Transaction
    suspend fun replaceSessionMessages(
        connectionId: String,
        sessionId: String,
        items: List<CachedMessageEntity>,
    ) {
        clearSession(connectionId, sessionId)
        upsertAll(items)
    }
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_events WHERE connectionId = :connectionId ORDER BY createdAt DESC LIMIT :limit")
    fun observe(connectionId: String, limit: Int = 200): Flow<List<ActivityEventEntity>>

    @Insert
    suspend fun insert(event: ActivityEventEntity): Long

    @Query("DELETE FROM activity_events WHERE connectionId = :connectionId AND id NOT IN (SELECT id FROM activity_events WHERE connectionId = :connectionId ORDER BY createdAt DESC, id DESC LIMIT :keep)")
    suspend fun trim(connectionId: String, keep: Int)

    @Query("UPDATE activity_events SET read = 1 WHERE connectionId = :connectionId")
    suspend fun markAllRead(connectionId: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM chat_drafts WHERE connectionId = :connectionId LIMIT 1")
    suspend fun get(connectionId: String): ChatDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ChatDraftEntity)
}

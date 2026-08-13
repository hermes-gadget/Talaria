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

    /** Delete every row for a connection, including management-profile scope variants. */
    @Query("DELETE FROM cached_sessions WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purge(connectionId: String, scopePrefix: String)

    @Query("DELETE FROM cached_sessions WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun deleteByIdsBatch(connectionId: String, ids: List<String>)

    /** Keep Room's bind-variable usage bounded for an authoritative full prune. */
    suspend fun deleteByIds(connectionId: String, ids: List<String>) {
        ids.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            deleteByIdsBatch(connectionId, batch)
        }
    }

    /**
     * @deprecated Use [TalariaDatabase.reconcileSessionCache] so transcript
     * rows and local session metadata share the same cross-DAO transaction.
     */
    @Deprecated("Use TalariaDatabase.reconcileSessionCache for cache reconciliation")
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
interface SessionOrganizationDao {
    @Query(
        "SELECT * FROM local_session_collections " +
            "WHERE connectionId = :connectionId ORDER BY kind ASC, name COLLATE NOCASE ASC",
    )
    fun observeCollections(connectionId: String): Flow<List<LocalSessionCollectionEntity>>

    @Query("SELECT * FROM local_session_collections WHERE connectionId = :connectionId ORDER BY kind ASC, name COLLATE NOCASE ASC")
    suspend fun getCollections(connectionId: String): List<LocalSessionCollectionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollection(collection: LocalSessionCollectionEntity): Long

    @Query("DELETE FROM local_session_collection_links WHERE connectionId = :connectionId AND collectionId = :collectionId")
    suspend fun deleteLinksForCollection(connectionId: String, collectionId: Long)

    @Query(
        "UPDATE saved_session_filters SET labelId = NULL, groupId = NULL " +
            "WHERE connectionId = :connectionId AND (labelId = :collectionId OR groupId = :collectionId)",
    )
    suspend fun clearFilterCollectionReferences(connectionId: String, collectionId: Long)

    @Query("DELETE FROM local_session_collections WHERE connectionId = :connectionId AND id = :collectionId")
    suspend fun deleteCollectionRow(connectionId: String, collectionId: Long)

    @Transaction
    suspend fun deleteCollection(connectionId: String, collectionId: Long) {
        deleteLinksForCollection(connectionId, collectionId)
        clearFilterCollectionReferences(connectionId, collectionId)
        deleteCollectionRow(connectionId, collectionId)
    }

    @Query("SELECT * FROM local_session_collection_links WHERE connectionId = :connectionId")
    fun observeCollectionLinks(connectionId: String): Flow<List<LocalSessionCollectionLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCollectionLink(link: LocalSessionCollectionLinkEntity)

    @Query(
        "DELETE FROM local_session_collection_links " +
            "WHERE connectionId = :connectionId AND sessionId = :sessionId AND collectionId = :collectionId",
    )
    suspend fun deleteCollectionLink(connectionId: String, sessionId: String, collectionId: Long)

    @Query("SELECT * FROM local_session_favorites WHERE connectionId = :connectionId")
    fun observeFavorites(connectionId: String): Flow<List<LocalSessionFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: LocalSessionFavoriteEntity)

    @Query("DELETE FROM local_session_favorites WHERE connectionId = :connectionId AND sessionId = :sessionId")
    suspend fun deleteFavorite(connectionId: String, sessionId: String)

    @Query("SELECT * FROM saved_session_filters WHERE connectionId = :connectionId ORDER BY updatedAt DESC, id DESC")
    fun observeSavedFilters(connectionId: String): Flow<List<SavedSessionFilterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedFilter(filter: SavedSessionFilterEntity): Long

    @Query("DELETE FROM saved_session_filters WHERE connectionId = :connectionId AND id = :filterId")
    suspend fun deleteSavedFilter(connectionId: String, filterId: Long)

    @Query("DELETE FROM local_session_collection_links WHERE connectionId = :connectionId AND sessionId IN (:sessionIds)")
    suspend fun deleteCollectionLinksForSessionsBatch(connectionId: String, sessionIds: List<String>)

    @Query("DELETE FROM local_session_favorites WHERE connectionId = :connectionId AND sessionId IN (:sessionIds)")
    suspend fun deleteFavoritesForSessionsBatch(connectionId: String, sessionIds: List<String>)

    /** Remove local metadata only for rows deleted from this exact profile scope. */
    suspend fun deleteSessions(connectionId: String, sessionIds: List<String>) {
        sessionIds.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            deleteCollectionLinksForSessionsBatch(connectionId, batch)
            deleteFavoritesForSessionsBatch(connectionId, batch)
        }
    }

    /** Delete every org row for a connection, including management-profile scope variants. */
    @Query("DELETE FROM local_session_collections WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purgeCollections(connectionId: String, scopePrefix: String)

    @Query("DELETE FROM local_session_collection_links WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purgeCollectionLinks(connectionId: String, scopePrefix: String)

    @Query("DELETE FROM local_session_favorites WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purgeFavorites(connectionId: String, scopePrefix: String)

    @Query("DELETE FROM saved_session_filters WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purgeSavedFilters(connectionId: String, scopePrefix: String)

    @Transaction
    suspend fun purge(connectionId: String, scopePrefix: String) {
        purgeCollections(connectionId, scopePrefix)
        purgeCollectionLinks(connectionId, scopePrefix)
        purgeFavorites(connectionId, scopePrefix)
        purgeSavedFilters(connectionId, scopePrefix)
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

    /** Delete every row for a connection, including management-profile scope variants. */
    @Query("DELETE FROM cached_messages WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purge(connectionId: String, scopePrefix: String)

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

    /** Delete every row for a connection, including management-profile scope variants. */
    @Query("DELETE FROM activity_events WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purge(connectionId: String, scopePrefix: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM chat_drafts WHERE connectionId = :connectionId LIMIT 1")
    suspend fun get(connectionId: String): ChatDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ChatDraftEntity)

    /** Delete every row for a connection, including management-profile scope variants. */
    @Query("DELETE FROM chat_drafts WHERE connectionId = :connectionId OR connectionId LIKE :scopePrefix")
    suspend fun purge(connectionId: String, scopePrefix: String)
}

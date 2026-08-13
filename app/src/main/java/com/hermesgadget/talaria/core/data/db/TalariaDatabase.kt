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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedSessionEntity::class,
        CachedMessageEntity::class,
        ActivityEventEntity::class,
        ChatDraftEntity::class,
        LocalSessionCollectionEntity::class,
        LocalSessionCollectionLinkEntity::class,
        LocalSessionFavoriteEntity::class,
        SavedSessionFilterEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class TalariaDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun sessionOrganization(): SessionOrganizationDao
    abstract fun messages(): MessageDao
    abstract fun activity(): ActivityDao
    abstract fun drafts(): DraftDao

    /**
     * Reconcile session metadata and transcripts as one unit. A server list is
     * authoritative only when both tables agree, so a failed delete/upsert
     * rolls back the whole cache mutation.
     */
    suspend fun reconcileSessionCache(
        connectionId: String,
        deleteIds: List<String>,
        upserts: List<CachedSessionEntity>,
    ) {
        withTransaction {
            if (deleteIds.isNotEmpty()) {
                sessions().deleteByIds(connectionId, deleteIds)
                messages().deleteBySessionIds(connectionId, deleteIds)
                sessionOrganization().deleteSessions(connectionId, deleteIds)
            }
            if (upserts.isNotEmpty()) sessions().upsertAll(upserts)
        }
    }

    suspend fun deleteSessionCache(connectionId: String, sessionId: String) {
        reconcileSessionCache(
            connectionId = connectionId,
            deleteIds = listOf(sessionId),
            upserts = emptyList(),
        )
    }

    /**
     * Delete every offline row belonging to a connection: transcripts, session
     * metadata, activity, drafts, and local organization (collections, links,
     * favorites, filters). Covers every management-profile scope variant of the
     * connection (`id` plus `id|profile|…`), because a profile switch orphans
     * the old scope while the connection id stays the same.
     */
    suspend fun purgeConnection(connectionId: String) {
        // Connection ids are UUIDs (hex + dashes), so they can never contain
        // the LIKE wildcards; the prefix match is exact for every scope variant.
        val scopePrefix = "$connectionId|profile|%"
        withTransaction {
            sessions().purge(connectionId, scopePrefix)
            messages().purge(connectionId, scopePrefix)
            activity().purge(connectionId, scopePrefix)
            drafts().purge(connectionId, scopePrefix)
            sessionOrganization().purge(connectionId, scopePrefix)
        }
    }

    companion object {
        /**
         * v1 keyed cached sessions/messages only by the remote id. Two Hermes
         * connections can legitimately reuse those ids, so one profile could
         * overwrite another profile's offline cache. Rebuild just the two cache
         * tables with connection-scoped compound keys; activity and drafts stay.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_sessions RENAME TO cached_sessions_v1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_sessions (
                        id TEXT NOT NULL,
                        connectionId TEXT NOT NULL,
                        title TEXT,
                        source TEXT,
                        model TEXT,
                        preview TEXT,
                        messageCount INTEGER,
                        lastActive TEXT,
                        json TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(connectionId, id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO cached_sessions
                        (id, connectionId, title, source, model, preview, messageCount, lastActive, json, updatedAt)
                    SELECT id, connectionId, title, source, model, preview, messageCount, lastActive, json, updatedAt
                    FROM cached_sessions_v1
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE cached_sessions_v1")

                db.execSQL("ALTER TABLE cached_messages RENAME TO cached_messages_v1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_messages (
                        `key` TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        connectionId TEXT NOT NULL,
                        role TEXT,
                        content TEXT,
                        timestamp TEXT,
                        ordinal INTEGER NOT NULL,
                        PRIMARY KEY(connectionId, `key`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO cached_messages
                        (`key`, sessionId, connectionId, role, content, timestamp, ordinal)
                    SELECT `key`, sessionId, connectionId, role, content, timestamp, ordinal
                    FROM cached_messages_v1
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE cached_messages_v1")
            }
        }

        /** Add the query-shaping indices without rebuilding or discarding data. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_cached_sessions_connectionId_updatedAt` " +
                        "ON `cached_sessions` (`connectionId`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_cached_messages_connectionId_sessionId_ordinal` " +
                        "ON `cached_messages` (`connectionId`, `sessionId`, `ordinal`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_activity_events_connectionId_createdAt_id` " +
                        "ON `activity_events` (`connectionId`, `createdAt`, `id`)"
                )
            }
        }

        /** Add profile-scoped local organization without changing server/session pin semantics. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_sessions ADD COLUMN platform TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_session_collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        connectionId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_local_session_collections_connectionId_kind_name " +
                        "ON local_session_collections (connectionId, kind, name)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_session_collection_links (
                        connectionId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        collectionId INTEGER NOT NULL,
                        PRIMARY KEY(connectionId, sessionId, collectionId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_local_session_collection_links_connectionId_sessionId " +
                        "ON local_session_collection_links (connectionId, sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_local_session_collection_links_connectionId_collectionId " +
                        "ON local_session_collection_links (connectionId, collectionId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_session_favorites (
                        connectionId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        PRIMARY KEY(connectionId, sessionId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_session_filters (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        connectionId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        source TEXT,
                        platform TEXT,
                        endReason TEXT,
                        labelId INTEGER,
                        groupId INTEGER,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_saved_session_filters_connectionId_updatedAt " +
                        "ON saved_session_filters (connectionId, updatedAt)"
                )
            }
        }
    }
}

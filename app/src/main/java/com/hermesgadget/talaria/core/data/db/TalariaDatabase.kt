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
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedSessionEntity::class,
        CachedMessageEntity::class,
        ActivityEventEntity::class,
        ChatDraftEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TalariaDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun activity(): ActivityDao
    abstract fun drafts(): DraftDao

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
    }
}

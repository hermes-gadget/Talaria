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
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM cached_sessions WHERE connectionId = :connectionId ORDER BY updatedAt DESC")
    fun observeSessions(connectionId: String): Flow<List<CachedSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedSessionEntity>)

    @Query("DELETE FROM cached_sessions WHERE connectionId = :connectionId")
    suspend fun clear(connectionId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE connectionId = :connectionId AND sessionId = :sessionId ORDER BY ordinal ASC")
    fun observeMessages(connectionId: String, sessionId: String): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_messages WHERE connectionId = :connectionId AND sessionId = :sessionId")
    suspend fun clearSession(connectionId: String, sessionId: String)
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

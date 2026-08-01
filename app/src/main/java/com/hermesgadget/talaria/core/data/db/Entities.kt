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

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_sessions", primaryKeys = ["connectionId", "id"])
data class CachedSessionEntity(
    val id: String,
    val connectionId: String,
    val title: String?,
    val source: String?,
    val model: String?,
    val preview: String?,
    val messageCount: Int?,
    val lastActive: String?,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cached_messages", primaryKeys = ["connectionId", "key"])
data class CachedMessageEntity(
    val key: String,
    val sessionId: String,
    val connectionId: String,
    val role: String?,
    val content: String?,
    val timestamp: String?,
    val ordinal: Int,
)

@Entity(tableName = "activity_events")
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: String,
    val type: String,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)

@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(
    @PrimaryKey val connectionId: String,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

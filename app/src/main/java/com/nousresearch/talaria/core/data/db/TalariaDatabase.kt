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


package com.nousresearch.talaria.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedSessionEntity::class,
        CachedMessageEntity::class,
        ActivityEventEntity::class,
        ChatDraftEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TalariaDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun activity(): ActivityDao
    abstract fun drafts(): DraftDao
}

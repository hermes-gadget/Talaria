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

package com.nousresearch.talaria.core.data.repo

import com.nousresearch.talaria.core.data.db.ChatDraftEntity
import com.nousresearch.talaria.core.data.db.TalariaDatabase
import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.core.network.PtyEvent
import com.nousresearch.talaria.core.network.PtyWebSocketSession
import com.nousresearch.talaria.core.network.WsAuthHelper
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(
    private val clientFactory: HermesClientFactory,
    private val db: TalariaDatabase,
    private val connectionStore: SecureConnectionStore,
    private val wsAuth: WsAuthHelper,
) {
    fun openPty(
        resumeSessionId: String? = null,
        channelId: String = UUID.randomUUID().toString(),
        cols: Int = 80,
        rows: Int = 24,
    ): Pair<PtyWebSocketSession, Flow<PtyEvent>> {
        val session = PtyWebSocketSession(clientFactory.webSocketClient(), connectionStore, wsAuth)
        return session to session.connect(resumeSessionId, channelId, cols, rows)
    }

    suspend fun saveDraft(text: String) {
        val id = connectionStore.activeProfile()?.id ?: return
        db.drafts().upsert(ChatDraftEntity(id, text))
    }

    suspend fun loadDraft(): String {
        val id = connectionStore.activeProfile()?.id ?: return ""
        return db.drafts().get(id)?.text.orEmpty()
    }
}

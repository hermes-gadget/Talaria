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

package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.ChatDraftEntity
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.ConnectionSnapshot
import com.hermesgadget.talaria.core.network.PtyEvent
import com.hermesgadget.talaria.core.network.PtyGenerationGate
import com.hermesgadget.talaria.core.network.PtyWebSocketSession
import com.hermesgadget.talaria.core.network.WsAuthHelper
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(
    private val clientFactory: HermesClientFactory,
    private val db: TalariaDatabase,
    private val connectionStore: SecureConnectionStore,
    private val wsAuth: WsAuthHelper,
) {
    fun openPty(
        snapshot: ConnectionSnapshot,
        resumeSessionId: String? = null,
        channelId: String = UUID.randomUUID().toString(),
        cols: Int = 80,
        rows: Int = 24,
        attachToken: String? = null,
        generationId: Long? = null,
        generationGate: PtyGenerationGate? = null,
    ): Pair<PtyWebSocketSession, Flow<PtyEvent>> {
        val session = PtyWebSocketSession(
            client = clientFactory.webSocketClient(snapshot),
            wsAuth = wsAuth,
            snapshot = snapshot,
            generationId = generationId,
            generationGate = generationGate,
        )
        return session to session.connect(resumeSessionId, channelId, cols, rows, attachToken)
    }

    suspend fun saveDraft(scopeId: String, text: String) {
        db.drafts().upsert(ChatDraftEntity(scopeId, text))
    }

    suspend fun loadDraft(scopeId: String): String =
        db.drafts().get(scopeId)?.text.orEmpty()
}

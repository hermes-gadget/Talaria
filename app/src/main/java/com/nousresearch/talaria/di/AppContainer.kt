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

package com.nousresearch.talaria.di

import android.content.Context
import androidx.room.Room
import com.nousresearch.talaria.core.data.db.TalariaDatabase
import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.data.prefs.SettingsStore
import com.nousresearch.talaria.core.data.repo.ChatRepository
import com.nousresearch.talaria.core.data.repo.ConnectionRepository
import com.nousresearch.talaria.core.data.repo.HermesRepository
import com.nousresearch.talaria.core.lifecycle.HermesForegroundObserver
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.core.network.HermesEventClient
import com.nousresearch.talaria.core.network.WsAuthHelper
import com.nousresearch.talaria.core.notifications.TalariaNotifier
import com.nousresearch.talaria.core.voice.SpeechCoordinator
import com.nousresearch.talaria.core.voice.TtsSpeaker

/**
 * Lightweight manual DI graph. Keeps the app free of annotation processors
 * beyond Room/KSP while remaining easy to test by constructing fakes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val connectionStore = SecureConnectionStore(appContext)
    val database: TalariaDatabase = Room.databaseBuilder(
        appContext,
        TalariaDatabase::class.java,
        "talaria.db",
    ).fallbackToDestructiveMigration().build()

    val clientFactory = HermesClientFactory(connectionStore, settingsStore)
    val wsAuthHelper = WsAuthHelper(clientFactory, connectionStore)
    val eventClient = HermesEventClient(clientFactory, connectionStore, wsAuthHelper)
    val connectionRepository = ConnectionRepository(connectionStore, clientFactory, settingsStore)
    val hermesRepository = HermesRepository(clientFactory, database, connectionStore, appContext)
    val chatRepository = ChatRepository(clientFactory, database, connectionStore, wsAuthHelper)
    val notifier = TalariaNotifier(appContext, settingsStore)
    val speechCoordinator = SpeechCoordinator(appContext, settingsStore)
    val ttsSpeaker = TtsSpeaker(appContext, settingsStore)
    val foregroundObserver = HermesForegroundObserver(
        eventClient,
        wsAuthHelper,
        hermesRepository,
        connectionStore,
    )
}

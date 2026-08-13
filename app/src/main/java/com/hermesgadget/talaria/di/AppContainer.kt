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

package com.hermesgadget.talaria.di

import android.content.Context
import androidx.room.Room
import com.hermesgadget.talaria.core.data.db.TalariaDatabase
import com.hermesgadget.talaria.core.data.prefs.SecureConnectionStore
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustStore
import com.hermesgadget.talaria.core.data.prefs.LocaleManager
import com.hermesgadget.talaria.core.data.prefs.SettingsStore
import com.hermesgadget.talaria.core.data.repo.ChatRepository
import com.hermesgadget.talaria.core.data.repo.ConnectionRepository
import com.hermesgadget.talaria.core.data.repo.HermesRepository
import com.hermesgadget.talaria.core.data.repo.SessionOrganizationRepository
import com.hermesgadget.talaria.core.lifecycle.HermesForegroundObserver
import com.hermesgadget.talaria.core.network.HermesClientFactory
import com.hermesgadget.talaria.core.network.HermesEventClient
import com.hermesgadget.talaria.core.network.NativeOidcLogin
import com.hermesgadget.talaria.core.network.WsAuthHelper
import com.hermesgadget.talaria.core.notifications.TalariaNotifier
import com.hermesgadget.talaria.core.notifications.AgentAlertDispatcher
import com.hermesgadget.talaria.core.voice.SpeechCoordinator
import com.hermesgadget.talaria.core.voice.TtsSpeaker
import com.hermesgadget.talaria.feature.capture.ShareIntakeStore
import com.hermesgadget.talaria.feature.manage.files.ShareFileManager
import com.hermesgadget.talaria.feature.manage.sessions.SharedPreferencesSessionPinStore

/**
 * Lightweight manual DI graph. Keeps the app free of annotation processors
 * beyond Room/KSP while remaining easy to test by constructing fakes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val contentResolver = appContext.contentResolver
    val shareFileManager = ShareFileManager(appContext.cacheDir)
    val shareIntakeStore = ShareIntakeStore(appContext)
    val sessionPinStore = SharedPreferencesSessionPinStore(appContext)

    val settingsStore = SettingsStore(appContext)
    val localeManager = LocaleManager(settingsStore)
    val connectionStore = SecureConnectionStore(appContext)
    val carHostTrustStore = CarHostTrustStore(appContext)
    val database: TalariaDatabase = Room.databaseBuilder(
        appContext,
        TalariaDatabase::class.java,
        "talaria.db",
    ).addMigrations(
        TalariaDatabase.MIGRATION_1_2,
        TalariaDatabase.MIGRATION_2_3,
        TalariaDatabase.MIGRATION_3_4,
    ).build()

    val clientFactory = HermesClientFactory(connectionStore, settingsStore)
    val nativeOidcLogin = NativeOidcLogin(clientFactory, connectionStore)
    val wsAuthHelper = WsAuthHelper(clientFactory, connectionStore)
    val eventClient = HermesEventClient(clientFactory, wsAuthHelper)
    val hermesRepository = HermesRepository(clientFactory, database, connectionStore, appContext)
    val connectionRepository = ConnectionRepository(
        connectionStore,
        clientFactory,
        wsAuthHelper,
        database,
        settingsStore,
        hermesRepository,
    )
    val sessionOrganizationRepository = SessionOrganizationRepository(database.sessionOrganization())
    val chatRepository = ChatRepository(clientFactory, database, connectionStore, wsAuthHelper)
    val notifier = TalariaNotifier(appContext, settingsStore)
    val agentAlertDispatcher = AgentAlertDispatcher(notifier)
    val speechCoordinator = SpeechCoordinator(appContext, settingsStore)
    val ttsSpeaker = TtsSpeaker(appContext, settingsStore)
    val foregroundObserver = HermesForegroundObserver(
        eventClient,
        wsAuthHelper,
        hermesRepository,
        connectionStore,
    )
}

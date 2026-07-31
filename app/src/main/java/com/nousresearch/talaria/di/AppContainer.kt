package com.nousresearch.talaria.di

import android.content.Context
import androidx.room.Room
import com.nousresearch.talaria.core.data.db.TalariaDatabase
import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.core.data.prefs.SettingsStore
import com.nousresearch.talaria.core.data.repo.ChatRepository
import com.nousresearch.talaria.core.data.repo.ConnectionRepository
import com.nousresearch.talaria.core.data.repo.HermesRepository
import com.nousresearch.talaria.core.network.HermesClientFactory
import com.nousresearch.talaria.core.notifications.TalariaNotifier
import com.nousresearch.talaria.core.voice.SpeechCoordinator
import com.nousresearch.talaria.core.voice.TtsSpeaker

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
    val connectionRepository = ConnectionRepository(connectionStore, clientFactory, settingsStore)
    val hermesRepository = HermesRepository(clientFactory, database, connectionStore)
    val chatRepository = ChatRepository(clientFactory, database, connectionStore)
    val notifier = TalariaNotifier(appContext, settingsStore)
    val speechCoordinator = SpeechCoordinator(appContext, settingsStore)
    val ttsSpeaker = TtsSpeaker(appContext, settingsStore)
}

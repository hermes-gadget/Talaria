package com.nousresearch.talaria

import android.app.Application
import com.nousresearch.talaria.di.AppContainer

class TalariaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: TalariaApp
            private set
    }
}

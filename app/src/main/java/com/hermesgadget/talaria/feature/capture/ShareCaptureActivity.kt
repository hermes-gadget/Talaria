/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.feature.capture

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.hermesgadget.talaria.TalariaApp
import com.hermesgadget.talaria.core.data.prefs.LocaleManager
import com.hermesgadget.talaria.ui.theme.TalariaTheme

/** Small task composer used as the exported Android share target. */
class ShareCaptureActivity : ComponentActivity() {
    private val viewModel: ShareCaptureViewModel by viewModels {
        ShareCaptureViewModel.factory(TalariaApp.instance.container)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.acceptIntent(intent)
        setContent {
            TalariaTheme {
                ShareCaptureScreen(
                    viewModel = viewModel,
                    onFinished = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptIntent(intent)
    }
}

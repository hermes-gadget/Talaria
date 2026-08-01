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

package com.nousresearch.talaria

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.nousresearch.talaria.core.data.prefs.ThemeMode
import com.nousresearch.talaria.ui.navigation.TalariaNavRoot
import com.nousresearch.talaria.ui.theme.TalariaTheme

class MainActivity : ComponentActivity() {
    private var shareText by mutableStateOf<String?>(null)
    private var shareImage by mutableStateOf<Uri?>(null)
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.Transparent.toArgb(),
                darkScrim = Color.Transparent.toArgb(),
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.Transparent.toArgb(),
                darkScrim = Color.Transparent.toArgb(),
            ),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIntent(intent)
        setContent {
            TalariaTheme {
                SystemBarsForTheme(this)
                TalariaNavRoot(
                    shareText = shareText,
                    shareImage = shareImage,
                    deepLink = deepLink,
                    onShareConsumed = {
                        shareText = null
                        shareImage = null
                    },
                    onDeepLinkConsumed = { deepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                shareText = intent.getStringExtra(Intent.EXTRA_TEXT)
                shareImage = null
                if (intent.type?.startsWith("image/") == true) {
                    shareImage = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                }
            }
            Intent.ACTION_VIEW -> deepLink = intent.data?.toString()
        }
    }
}

@Composable
private fun SystemBarsForTheme(activity: ComponentActivity) {
    val settings = TalariaApp.instance.container.settingsStore
    val themeMode by settings.themeModeFlow.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    DisposableEffect(dark) {
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(Color.Transparent.toArgb())
            } else {
                SystemBarStyle.light(
                    Color.Transparent.toArgb(),
                    Color.Transparent.toArgb(),
                )
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(Color.Transparent.toArgb())
            } else {
                SystemBarStyle.light(
                    Color.Transparent.toArgb(),
                    Color.Transparent.toArgb(),
                )
            },
        )
        onDispose { }
    }
}

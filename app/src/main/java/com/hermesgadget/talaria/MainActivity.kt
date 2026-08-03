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

package com.hermesgadget.talaria

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.car.app.activity.CarAppActivity
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
import com.hermesgadget.talaria.core.data.prefs.ThemeMode
import com.hermesgadget.talaria.core.data.prefs.LocaleManager
import com.hermesgadget.talaria.feature.pip.PipChatIntent
import com.hermesgadget.talaria.feature.pip.PipChatSnapshot
import com.hermesgadget.talaria.feature.pip.PipModeState
import com.hermesgadget.talaria.ui.navigation.TalariaNavRoot
import com.hermesgadget.talaria.ui.theme.TalariaTheme

class MainActivity : ComponentActivity() {
    private var shareText by mutableStateOf<String?>(null)
    private var shareImage by mutableStateOf<Uri?>(null)
    private var deepLink by mutableStateOf<String?>(null)
    private var pipModeState = PipModeState()
    private var pipChatRequested = false
    private var launchingPipActivity = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android Automotive OS: the car launcher starts this activity
        // (it owns the MAIN/LAUNCHER filter), but on head units the car
        // experience must be the templated CarAppActivity rendered by the
        // template host. Hand off before any phone UI work — this activity
        // is a no-op on automotive devices.
        // Test hook: `am start ... --ez force_phone_ui true` keeps the
        // phone UI (e.g. to configure a connection on the AAOS emulator).
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            !intent.getBooleanExtra(EXTRA_FORCE_PHONE_UI, false)
        ) {
            startActivity(Intent(this, CarAppActivity::class.java))
            finish()
            return
        }
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

    /** Start the read-only chat snapshot in the dedicated PiP activity. */
    fun openPipChat(snapshot: PipChatSnapshot) {
        pipChatRequested = true
        launchingPipActivity = true
        startActivity(PipChatIntent.create(this, snapshot))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Starting the child activity is itself a user-initiated transition on
        // some Android versions. Let the child own the PiP window in that case.
        if (launchingPipActivity) {
            launchingPipActivity = false
            return
        }
        if (
            pipChatRequested &&
            pipModeState.shouldEnterOnUserLeave(
                supportsPictureInPicture = packageManager.hasSystemFeature(
                    PackageManager.FEATURE_PICTURE_IN_PICTURE,
                ),
                isFinishing = isFinishing,
            )
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeState = pipModeState.onPictureInPictureModeChanged(isInPictureInPictureMode)
        WindowCompat.setDecorFitsSystemWindows(window, isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            // Restore the same edge-to-edge contract used by the Compose host after
            // a PiP window is expanded again. The manifest keeps adjustResize.
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
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PipChatIntent.EXTRA_PIP_RETURNED, false) == true) {
            pipChatRequested = false
            launchingPipActivity = false
        }
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
            Intent.ACTION_VIEW -> {
                if (intent.data?.getQueryParameter("focus") == "composer") {
                    // The widget cannot host text input. Ask the full chat surface
                    // to bring up its composer when its deep link is opened.
                    window.setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                    )
                }
                deepLink = intent.data?.toString()
            }
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

/** Debug/test hook: `am start ... --ez force_phone_ui true` keeps the
 *  phone UI on automotive devices (AAOS emulator connection setup). */
private const val EXTRA_FORCE_PHONE_UI = "force_phone_ui"

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

package com.hermesgadget.talaria.feature.pip

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesgadget.talaria.MainActivity
import com.hermesgadget.talaria.ui.components.SimpleMarkdownText
import com.hermesgadget.talaria.ui.theme.TalariaTheme

data class PipChatMessage(val role: String, val text: String)

data class PipChatSnapshot(
    val title: String = "Chat",
    val messages: List<PipChatMessage> = emptyList(),
    val streamingText: String = "",
)

/** Small, explicit contract so widget/activity and UI code share no mutable chat state. */
object PipChatIntent {
    const val EXTRA_TITLE = "com.hermesgadget.talaria.pip.TITLE"
    const val EXTRA_ROLES = "com.hermesgadget.talaria.pip.ROLES"
    const val EXTRA_MESSAGES = "com.hermesgadget.talaria.pip.MESSAGES"
    const val EXTRA_STREAMING = "com.hermesgadget.talaria.pip.STREAMING"
    const val EXTRA_PIP_RETURNED = "com.hermesgadget.talaria.pip.RETURNED"

    fun create(context: Context, snapshot: PipChatSnapshot): Intent =
        Intent(context, PipChatActivity::class.java).apply {
            putExtra(EXTRA_TITLE, snapshot.title)
            putStringArrayListExtra(EXTRA_ROLES, ArrayList(snapshot.messages.map { it.role }))
            putStringArrayListExtra(EXTRA_MESSAGES, ArrayList(snapshot.messages.map { it.text }))
            putExtra(EXTRA_STREAMING, snapshot.streamingText)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun read(intent: Intent?): PipChatSnapshot {
        val roles = intent?.getStringArrayListExtra(EXTRA_ROLES).orEmpty()
        val messages = intent?.getStringArrayListExtra(EXTRA_MESSAGES).orEmpty()
        val count = minOf(roles.size, messages.size)
        return PipChatSnapshot(
            title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Chat" },
            messages = (0 until count).map { index ->
                PipChatMessage(roles[index], messages[index])
            },
            streamingText = intent?.getStringExtra(EXTRA_STREAMING).orEmpty(),
        )
    }
}

data class PipModeState(
    val isInPictureInPicture: Boolean = false,
    val hasEnteredPictureInPicture: Boolean = false,
) {
    fun onPictureInPictureModeChanged(isInPictureInPicture: Boolean): PipModeState = copy(
        isInPictureInPicture = isInPictureInPicture,
        hasEnteredPictureInPicture = hasEnteredPictureInPicture || isInPictureInPicture,
    )

    fun shouldEnterOnUserLeave(supportsPictureInPicture: Boolean, isFinishing: Boolean): Boolean =
        supportsPictureInPicture && !isFinishing && !isInPictureInPicture

    fun shouldReturnToMain(isFinishing: Boolean): Boolean =
        hasEnteredPictureInPicture && !isInPictureInPicture && !isFinishing
}

class PipChatActivity : ComponentActivity() {
    private var snapshot by mutableStateOf(PipChatSnapshot())
    private var pipModeState = PipModeState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = PipChatIntent.read(intent)
        setContent {
            TalariaTheme {
                PipChatContent(snapshot)
            }
        }
        enterPictureInPictureIfSupported()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        snapshot = PipChatIntent.read(intent)
        enterPictureInPictureIfSupported()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPictureIfSupported()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val previous = pipModeState
        pipModeState = pipModeState.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (
            previous.isInPictureInPicture &&
            pipModeState.shouldReturnToMain(isFinishing)
        ) {
            returnToMainApp()
        }
    }

    private fun enterPictureInPictureIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (
            pipModeState.shouldEnterOnUserLeave(
                supportsPictureInPicture = packageManager.hasSystemFeature(
                    "android.software.picture_in_picture",
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

    private fun returnToMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(PipChatIntent.EXTRA_PIP_RETURNED, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }
}

@Composable
private fun PipChatContent(snapshot: PipChatSnapshot) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                snapshot.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(snapshot.messages) { message ->
                    Text(
                        text = "${message.role}: ${message.text}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (snapshot.streamingText.isNotBlank()) {
                    item {
                        SimpleMarkdownText(
                            snapshot.streamingText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (snapshot.messages.isEmpty() && snapshot.streamingText.isBlank()) {
                    item {
                        Text(
                            "No messages yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

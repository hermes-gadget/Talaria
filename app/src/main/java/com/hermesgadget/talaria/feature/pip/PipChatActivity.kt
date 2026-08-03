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
    /** True when the snapshot was bounded and the visible transcript is partial. */
    val hasMore: Boolean = false,
)

/** Returns a prefix that never exceeds [maxBytes] when encoded as UTF-8. */
private fun String.utf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = Character.codePointAt(this, end)
        val charCount = Character.charCount(codePoint)
        val codePointBytes = codePoint.utf8ByteWidth()
        if (bytes + codePointBytes > maxBytes) break
        bytes += codePointBytes
        end += charCount
    }
    return substring(0, end)
}

private fun String.utf8ByteCount(): Int {
    var bytes = 0
    var index = 0
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        bytes += codePoint.utf8ByteWidth()
        index += Character.charCount(codePoint)
    }
    return bytes
}

private fun Int.utf8ByteWidth(): Int = when {
    this <= 0x7F -> 1
    this <= 0x7FF -> 2
    this <= 0xFFFF -> 3
    else -> 4
}

/** Small, explicit contract so widget/activity and UI code share no mutable chat state. */
object PipChatIntent {
    const val EXTRA_TITLE = "com.hermesgadget.talaria.pip.TITLE"
    const val EXTRA_ROLES = "com.hermesgadget.talaria.pip.ROLES"
    const val EXTRA_MESSAGES = "com.hermesgadget.talaria.pip.MESSAGES"
    const val EXTRA_STREAMING = "com.hermesgadget.talaria.pip.STREAMING"
    const val EXTRA_CONTINUATION = "com.hermesgadget.talaria.pip.CONTINUATION"
    const val EXTRA_PIP_RETURNED = "com.hermesgadget.talaria.pip.RETURNED"

    // Keep the raw string payload far below Binder's transaction limit. The
    // message-count cap also bounds Parcel array/object overhead for snapshots
    // made up of many short or empty strings.
    const val MAX_SNAPSHOT_UTF8_BYTES = 48 * 1024
    const val MAX_SNAPSHOT_MESSAGES = 128
    const val CONTINUATION_INDICATOR = "Transcript continues…"

    fun create(context: Context, snapshot: PipChatSnapshot): Intent {
        val bounded = snapshot.boundedForIntent()
        return Intent(context, PipChatActivity::class.java).apply {
            putExtra(EXTRA_TITLE, bounded.title)
            putStringArrayListExtra(EXTRA_ROLES, ArrayList(bounded.messages.map { it.role }))
            putStringArrayListExtra(EXTRA_MESSAGES, ArrayList(bounded.messages.map { it.text }))
            putExtra(EXTRA_STREAMING, bounded.streamingText)
            putExtra(EXTRA_CONTINUATION, bounded.hasMore)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
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
            hasMore = intent?.getBooleanExtra(EXTRA_CONTINUATION, false) == true,
        )
    }

    private fun PipChatSnapshot.boundedForIntent(): PipChatSnapshot {
        val budget = Utf8Budget(MAX_SNAPSHOT_UTF8_BYTES)
        var hasMore = this.hasMore

        val title = budget.take(this.title).also { hasMore = hasMore || it.wasTruncated }.value
        val sourceMessages = if (this.messages.size > MAX_SNAPSHOT_MESSAGES) {
            hasMore = true
            this.messages.takeLast(MAX_SNAPSHOT_MESSAGES)
        } else {
            this.messages
        }
        val boundedMessages = ArrayList<PipChatMessage>(sourceMessages.size)
        for (message in sourceMessages) {
            if (budget.isExhausted) {
                hasMore = true
                break
            }
            val role = budget.take(message.role)
            val text = budget.take(message.text)
            hasMore = hasMore || role.wasTruncated || text.wasTruncated
            boundedMessages += PipChatMessage(role.value, text.value)
        }

        val streaming = budget.take(this.streamingText)
        hasMore = hasMore || streaming.wasTruncated
        return copy(
            title = title,
            messages = boundedMessages,
            streamingText = streaming.value,
            hasMore = hasMore,
        )
    }

    private class Utf8Budget(private val limit: Int) {
        private var used = 0

        val isExhausted: Boolean get() = used >= limit

        fun take(value: String): BoundedText {
            if (value.isEmpty()) return BoundedText(value = value, wasTruncated = false)
            val available = (limit - used).coerceAtLeast(0)
            val prefix = value.utf8Prefix(available)
            used += prefix.utf8ByteCount()
            return BoundedText(value = prefix, wasTruncated = prefix.length < value.length)
        }
    }

    private data class BoundedText(val value: String, val wasTruncated: Boolean)
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
            if (snapshot.hasMore) {
                Text(
                    PipChatIntent.CONTINUATION_INDICATOR,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

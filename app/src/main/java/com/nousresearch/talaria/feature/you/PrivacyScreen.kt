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


package com.nousresearch.talaria.feature.you

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nousresearch.talaria.ui.components.ScreenScaffold
import androidx.compose.ui.Modifier

@Composable
fun PrivacyScreen() {
    ScreenScaffold("Privacy", "Local-first · no accounts required") {
        Text(
            """
Talaria is a privacy-respecting client for self-hosted Hermes Agent.

• Zero telemetry by default. Optional analytics never leave your device unless you enable a future opt-in (currently a no-op flag).
• Credentials are stored with EncryptedSharedPreferences + Android Keystore.
• Offline cache (Room) stays on-device; backups exclude secrets.
• Network traffic only targets Hermes dashboard URLs you configure.
• Certificate pinning is optional per connection profile.
• Microphone audio for dictation prefers on-device recognition; cloud STT requires explicit opt-in.
• Notifications are generated locally from your Hermes polls / chat events.

See PRIVACY.md in the repository for the full statement.
            """.trimIndent(),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

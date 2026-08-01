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


package com.hermesgadget.talaria.widget

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.hermesgadget.talaria.TalariaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile showing Hermes gateway up/down for the active profile. */
class TalariaTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        scope.launch {
            val label = runCatching {
                val status = TalariaApp.instance.container.hermesRepository.refreshStatus().getOrThrow()
                if ((status.gateway?.running ?: status.gateway_running) == true) "Hermes GW · up" else "Hermes GW · down"
            }.getOrElse { "Hermes · offline" }
            withContext(Dispatchers.Main.immediate) {
                tile.label = label
                tile.state = Tile.STATE_ACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

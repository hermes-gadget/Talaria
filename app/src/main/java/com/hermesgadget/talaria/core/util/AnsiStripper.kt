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
package com.hermesgadget.talaria.core.util

object AnsiStripper {
    private const val ESC = "\u001B"

    private val ansi = Regex(
        "(?:${Regex.escape(ESC)}\\[[0-9;?]*[A-Za-z])|" +
            "(?:${Regex.escape(ESC)}][^\\u0007]*\\u0007)|" +
            "(?:${Regex.escape(ESC)}[=><])|" +
            "(?:\\r)",
    )

    fun strip(input: String): String = ansi.replace(input, "").trimEnd()
}

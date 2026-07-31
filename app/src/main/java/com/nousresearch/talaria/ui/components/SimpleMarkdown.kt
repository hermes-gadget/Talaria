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
package com.nousresearch.talaria.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Lightweight offline markdown (bold / italic / inline code / fences stripped). */
@Composable
fun SimpleMarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val codeColor = MaterialTheme.colorScheme.tertiary
    val annotated = buildAnnotatedString {
        var i = 0
        val src = markdown.replace(Regex("```[\\s\\S]*?```")) { match ->
            "\n" + match.value.removePrefix("```").substringAfter('\n').removeSuffix("```").trim() + "\n"
        }
        while (i < src.length) {
            when {
                src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end < 0) {
                        append(src.substring(i)); break
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2
                }
                src.startsWith("`", i) -> {
                    val end = src.indexOf('`', i + 1)
                    if (end < 0) {
                        append(src.substring(i)); break
                    }
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor),
                    ) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                }
                src.startsWith("*", i) && !src.startsWith("**", i) -> {
                    val end = src.indexOf('*', i + 1)
                    if (end < 0) {
                        append(src.substring(i)); break
                    }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                }
                else -> {
                    append(src[i])
                    i++
                }
            }
        }
    }
    Text(annotated, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
}

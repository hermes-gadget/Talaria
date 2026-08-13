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
package com.hermesgadget.talaria.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri
import java.util.ArrayDeque
import com.hermesgadget.talaria.core.util.suspendResult

private const val LINK_TAG = "talaria-markdown-link"

internal const val MAX_MARKDOWN_INPUT_BYTES = 1L * 1024L * 1024L
internal const val MAX_MARKDOWN_BLOCKS = 512
internal const val MAX_MARKDOWN_INLINE_DEPTH = 16
internal const val MAX_MARKDOWN_INLINE_NODES = 4_096
internal const val MAX_MARKDOWN_CODE_TOKENS = 4_096
private const val MAX_MARKDOWN_LINES = 4_096
private const val MAX_MARKDOWN_TABLE_ROWS = 256
private const val MAX_MARKDOWN_TABLE_CELLS = 64

/**
 * A small, offline GFM-ish renderer for the chat transcript.
 *
 * Parsing is deliberately kept independent of Compose. The parsed document is
 * memoized by [SimpleMarkdownText], which is important because assistant text
 * can cause the chat screen to recompose at stream rate.
 */
@Composable
fun SimpleMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    SimpleMarkdownText(
        markdown = markdown,
        modifier = modifier,
        highlightQuery = "",
        onLinkClick = onLinkClick,
    )
}

/**
 * Additive overload used by transcript search. The original three-argument
 * signature remains intact for chat and non-chat callers.
 */
@Composable
fun SimpleMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    highlightQuery: String,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val document = remember(markdown) { parseMarkdown(markdown) }
    val openLink: (String) -> Unit = { url ->
        onLinkClick?.invoke(url) ?: openMarkdownUrl(context, url)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        document.blocks.forEach { block ->
            MarkdownBlockView(
                block = block,
                onLinkClick = openLink,
                highlightQuery = highlightQuery,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun openMarkdownUrl(context: android.content.Context, url: String) {
    val uri = runCatching { url.trim().toUri() }.getOrNull() ?: return
    if (uri.scheme !in setOf("http", "https", "mailto")) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    onLinkClick: (String) -> Unit,
    highlightQuery: String,
    modifier: Modifier,
) {
    when (block) {
        is MarkdownParagraph -> {
            val style = when (block.headingLevel) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyMedium
            }
            MarkdownInlineText(
                lines = block.lines,
                style = style,
                modifier = modifier,
                onLinkClick = onLinkClick,
                heading = block.headingLevel > 0,
                highlightQuery = highlightQuery,
            )
        }

        is MarkdownCodeBlock -> {
            val codeColor = MaterialTheme.colorScheme.onSurface
            val keywordColor = MaterialTheme.colorScheme.primary
            val stringColor = MaterialTheme.colorScheme.secondary
            val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
            val numberColor = MaterialTheme.colorScheme.tertiary
            val highlightBackground = MaterialTheme.colorScheme.tertiaryContainer
            val highlightForeground = MaterialTheme.colorScheme.onTertiaryContainer
            val annotated = remember(
                block.tokens,
                codeColor,
                keywordColor,
                stringColor,
                commentColor,
                numberColor,
                highlightQuery,
                highlightBackground,
                highlightForeground,
            ) {
                highlightAnnotatedString(
                    source = buildCodeAnnotatedString(
                        block.tokens,
                        codeColor,
                        keywordColor,
                        stringColor,
                        commentColor,
                        numberColor,
                    ),
                    query = highlightQuery,
                    background = highlightBackground,
                    foreground = highlightForeground,
                )
            }
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = annotated,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = codeColor,
                        ),
                    )
                }
            }
        }

        is MarkdownTable -> {
            MarkdownTableView(block, onLinkClick, highlightQuery, modifier)
        }

        is MarkdownQuote -> {
            Row(modifier = modifier.heightIn(min = 24.dp)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
                MarkdownInlineText(
                    lines = block.lines,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(start = 10.dp),
                    onLinkClick = onLinkClick,
                    highlightQuery = highlightQuery,
                )
            }
        }

        is MarkdownList -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width((item.depth * 16).dp))
                        Text(
                            text = if (item.ordered) "${item.number}." else "•",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(24.dp),
                        )
                        MarkdownInlineText(
                            lines = listOf(item.content),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            onLinkClick = onLinkClick,
                            highlightQuery = highlightQuery,
                        )
                    }
                }
            }
        }

        MarkdownHorizontalRule -> {
            HorizontalDivider(
                modifier = modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun MarkdownInlineText(
    lines: List<List<MarkdownInline>>,
    style: TextStyle,
    modifier: Modifier,
    onLinkClick: (String) -> Unit,
    heading: Boolean = false,
    highlightQuery: String = "",
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeColor = MaterialTheme.colorScheme.tertiary
    val inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant
    val highlightBackground = MaterialTheme.colorScheme.tertiaryContainer
    val highlightForeground = MaterialTheme.colorScheme.onTertiaryContainer
    val annotated = remember(
        lines,
        linkColor,
        inlineCodeColor,
        inlineCodeBackground,
        heading,
        highlightQuery,
        highlightBackground,
        highlightForeground,
    ) {
        highlightAnnotatedString(
            source = buildAnnotatedString {
                lines.forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    appendInline(
                        line,
                        linkColor = linkColor,
                        inlineCodeColor = inlineCodeColor,
                        inlineCodeBackground = inlineCodeBackground,
                    )
                }
            },
            query = highlightQuery,
            background = highlightBackground,
            foreground = highlightForeground,
        )
    }
    val hasLinks = annotated.getStringAnnotations(LINK_TAG, 0, annotated.length).isNotEmpty()
    if (hasLinks) {
        ClickableText(
            text = annotated,
            modifier = modifier,
            style = style,
            onClick = { offset ->
                annotated.getStringAnnotations(LINK_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { onLinkClick(it.item) }
            },
        )
    } else {
        Text(text = annotated, modifier = modifier, style = style)
    }
}

@Composable
private fun MarkdownTableView(
    table: MarkdownTable,
    onLinkClick: (String) -> Unit,
    highlightQuery: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            MarkdownTableRow(table.header, isHeader = true, onLinkClick = onLinkClick, highlightQuery = highlightQuery)
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MarkdownTableRow(
                    row,
                    isHeader = false,
                    onLinkClick = onLinkClick,
                    highlightQuery = highlightQuery,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<List<MarkdownInline>>,
    isHeader: Boolean,
    onLinkClick: (String) -> Unit,
    highlightQuery: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            MarkdownInlineText(
                lines = listOf(cell),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                onLinkClick = onLinkClick,
                highlightQuery = highlightQuery,
            )
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    nodes: List<MarkdownInline>,
    linkColor: Color,
    inlineCodeColor: Color,
    inlineCodeBackground: Color,
) {
    nodes.forEach { node ->
        when (node) {
            is MarkdownText -> append(node.value)
            is MarkdownBold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(node.children, linkColor, inlineCodeColor, inlineCodeBackground)
            }
            is MarkdownItalic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(node.children, linkColor, inlineCodeColor, inlineCodeBackground)
            }
            is MarkdownInlineCode -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = inlineCodeColor,
                    background = inlineCodeBackground,
                ),
            ) {
                append(node.value)
            }
            is MarkdownStrike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInline(node.children, linkColor, inlineCodeColor, inlineCodeBackground)
            }
            is MarkdownLink -> {
                val start = length
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    appendInline(node.children, linkColor, inlineCodeColor, inlineCodeBackground)
                }
                addStringAnnotation(LINK_TAG, node.url, start, length)
            }
        }
    }
}

private fun buildCodeAnnotatedString(
    tokens: List<MarkdownCodeToken>,
    codeColor: Color,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
): AnnotatedString = buildAnnotatedString {
    tokens.forEach { token ->
        val color = when (token.kind) {
            MarkdownCodeTokenKind.KEYWORD -> keywordColor
            MarkdownCodeTokenKind.STRING -> stringColor
            MarkdownCodeTokenKind.COMMENT -> commentColor
            MarkdownCodeTokenKind.NUMBER -> numberColor
            MarkdownCodeTokenKind.PLAIN -> codeColor
        }
        withStyle(SpanStyle(color = color)) { append(token.text) }
    }
}

/** Case-insensitive ranges used by both the Compose renderer and JVM tests. */
internal fun markdownHighlightRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return emptyList()
    val haystack = text.lowercase()
    val lowerNeedle = needle.lowercase()
    val ranges = mutableListOf<IntRange>()
    var offset = 0
    while (offset <= haystack.length - lowerNeedle.length) {
        val found = haystack.indexOf(lowerNeedle, offset)
        if (found < 0) break
        ranges += found..(found + lowerNeedle.length - 1)
        offset = found + lowerNeedle.length
    }
    return ranges
}

private fun highlightAnnotatedString(
    source: AnnotatedString,
    query: String,
    background: Color,
    foreground: Color,
): AnnotatedString {
    val ranges = markdownHighlightRanges(source.text, query)
    if (ranges.isEmpty()) return source
    return buildAnnotatedString {
        append(source)
        ranges.forEach { range ->
            addStyle(
                SpanStyle(background = background, color = foreground),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}

internal sealed interface MarkdownBlock

internal data class MarkdownDocument(val blocks: List<MarkdownBlock>)

internal data class MarkdownParagraph(
    val lines: List<List<MarkdownInline>>,
    val headingLevel: Int = 0,
) : MarkdownBlock

internal data class MarkdownCodeBlock(
    val language: String,
    val tokens: List<MarkdownCodeToken>,
) : MarkdownBlock

internal data class MarkdownTable(
    val header: List<List<MarkdownInline>>,
    val rows: List<List<List<MarkdownInline>>>,
) : MarkdownBlock

internal data class MarkdownQuote(val lines: List<List<MarkdownInline>>) : MarkdownBlock

internal data class MarkdownList(val items: List<MarkdownListItem>) : MarkdownBlock

internal data class MarkdownListItem(
    val depth: Int,
    val ordered: Boolean,
    val number: Int,
    val content: List<MarkdownInline>,
)

internal data object MarkdownHorizontalRule : MarkdownBlock

internal sealed interface MarkdownInline

internal data class MarkdownText(val value: String) : MarkdownInline
internal data class MarkdownBold(val children: List<MarkdownInline>) : MarkdownInline
internal data class MarkdownItalic(val children: List<MarkdownInline>) : MarkdownInline
internal data class MarkdownInlineCode(val value: String) : MarkdownInline
internal data class MarkdownStrike(val children: List<MarkdownInline>) : MarkdownInline
internal data class MarkdownLink(val children: List<MarkdownInline>, val url: String) : MarkdownInline

internal enum class MarkdownCodeTokenKind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER }

internal data class MarkdownCodeToken(
    val text: String,
    val kind: MarkdownCodeTokenKind,
)

/** Parse the supported markdown constructs in one bounded linear pass over the lines. */
internal fun parseMarkdown(markdown: String): MarkdownDocument {
    val bounded = markdown.takeUtf8Bytes(MAX_MARKDOWN_INPUT_BYTES)
    val lines = splitMarkdownLines(bounded.replace("\r\n", "\n").replace('\r', '\n'))
    val blocks = mutableListOf<MarkdownBlock>()
    val budget = MarkdownBudget()
    var index = 0

    while (index < lines.size && blocks.size < MAX_MARKDOWN_BLOCKS && !budget.exhausted) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }

        val fence = FENCE_OPEN.matchEntire(line)
        if (fence != null) {
            val language = fence.groupValues[1].trim().substringBefore(' ').lowercase()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !isFenceClose(lines[index])) {
                if (codeLines.size < MAX_MARKDOWN_LINES) codeLines += lines[index]
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownCodeBlock(language, tokenizeCode(codeLines.joinToString("\n"), language))
            continue
        }

        if (index + 1 < lines.size && isTableSeparator(lines[index + 1]) && line.contains('|')) {
            val header = parseTableRow(line, budget)
            index += 2
            val rows = mutableListOf<List<List<MarkdownInline>>>()
            while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                if (rows.size < MAX_MARKDOWN_TABLE_ROWS) rows += parseTableRow(lines[index], budget)
                index++
            }
            blocks += MarkdownTable(header, rows)
            continue
        }

        if (isHorizontalRule(line)) {
            blocks += MarkdownHorizontalRule
            index++
            continue
        }

        if (line.trimStart().startsWith('>')) {
            val quoteLines = mutableListOf<List<MarkdownInline>>()
            while (index < lines.size && lines[index].trimStart().startsWith('>')) {
                val quoteText = lines[index].trimStart().removePrefix(">").removePrefix(" ")
                if (quoteLines.size < MAX_MARKDOWN_LINES) quoteLines += parseInline(quoteText, budget)
                index++
            }
            blocks += MarkdownQuote(quoteLines)
            continue
        }

        if (isListLine(line)) {
            val items = mutableListOf<MarkdownListItem>()
            while (index < lines.size) {
                val match = listMatch(lines[index], budget) ?: break
                if (items.size < MAX_MARKDOWN_LINES) items += match
                index++
            }
            blocks += MarkdownList(items)
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MarkdownParagraph(
                lines = listOf(parseInline(heading.groupValues[2].trim(), budget)),
                headingLevel = heading.groupValues[1].length,
            )
            index++
            continue
        }

        val paragraph = mutableListOf<List<MarkdownInline>>()
        while (index < lines.size && lines[index].isNotBlank()) {
            if (paragraph.isNotEmpty() && startsBlock(lines, index)) break
            if (paragraph.size < MAX_MARKDOWN_LINES) paragraph += parseInline(lines[index], budget)
            index++
        }
        if (paragraph.isNotEmpty()) blocks += MarkdownParagraph(paragraph)
    }
    return MarkdownDocument(blocks)
}

private val FENCE_OPEN = Regex("^\\s*```(.*)$")
private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val TABLE_CELL = Regex("^:?-{3,}:?$")
private val FENCE_CLOSE = Regex("^```+$")

private fun startsBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    if (FENCE_OPEN.matches(line) || isHorizontalRule(line)) return true
    if (line.trimStart().startsWith('>') || isListLine(line) || HEADING.matches(line)) return true
    return index + 1 < lines.size && line.contains('|') && isTableSeparator(lines[index + 1])
}

private fun isFenceClose(line: String): Boolean = line.trim().matches(FENCE_CLOSE)

private fun isHorizontalRule(line: String): Boolean {
    val compact = line.trim().filterNot(Char::isWhitespace)
    return compact.length >= 3 && compact.toSet().size == 1 && compact[0] in charArrayOf('-', '*', '_')
}

private fun isTableSeparator(line: String): Boolean {
    if (!line.contains('|')) return false
    val cells = splitTableCells(line)
    return cells.isNotEmpty() && cells.all { TABLE_CELL.matches(it.trim()) }
}

private fun parseTableRow(line: String, budget: MarkdownBudget): List<List<MarkdownInline>> =
    splitTableCells(line).map { parseInline(it.trim(), budget) }

private fun splitTableCells(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    trimmed.forEach { character ->
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }
            character == '\\' -> {
                current.append(character)
                escaped = true
            }
            character == '|' -> {
                if (cells.size < MAX_MARKDOWN_TABLE_CELLS) cells += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
    }
    if (cells.size < MAX_MARKDOWN_TABLE_CELLS) cells += current.toString()
    return cells
}

private val LIST_ITEM = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.*)$")

private fun isListLine(line: String): Boolean = LIST_ITEM.matches(line)

private fun listMatch(line: String, budget: MarkdownBudget): MarkdownListItem? {
    val match = LIST_ITEM.matchEntire(line) ?: return null
    val marker = match.groupValues[2]
    return MarkdownListItem(
        depth = match.groupValues[1].length / 2,
        ordered = marker.first().isDigit(),
        number = marker.takeWhile(Char::isDigit).toIntOrNull() ?: 0,
        content = parseInline(match.groupValues[3], budget),
    )
}

private enum class InlineWrapperKind { BOLD, ITALIC, STRIKE, CODE, LINK }

private data class InlineWrapper(
    val kind: InlineWrapperKind,
    val url: String? = null,
)

private data class InlineFrame(
    val source: String,
    val endExclusive: Int,
    var index: Int,
    val output: MutableList<MarkdownInline>,
    val wrapper: InlineWrapper? = null,
    val depth: Int = 0,
    val plain: StringBuilder = StringBuilder(),
)

private class MarkdownBudget {
    var nodes: Int = 0
        private set
    var exhausted: Boolean = false
        private set

    fun addNode(): Boolean {
        if (exhausted || nodes >= MAX_MARKDOWN_INLINE_NODES) {
            exhausted = true
            return false
        }
        nodes++
        return true
    }
}

/** Iterative inline parser with an explicit frame stack and bounded nesting. */
private fun parseInline(source: String, budget: MarkdownBudget): List<MarkdownInline> {
    val result = mutableListOf<MarkdownInline>()
    val frames = ArrayDeque<InlineFrame>()
    frames.addLast(InlineFrame(source, source.length, 0, result))

    fun flush(frame: InlineFrame): Boolean {
        if (frame.plain.isEmpty()) return true
        if (!budget.addNode()) return false
        frame.output += MarkdownText(frame.plain.toString())
        frame.plain.clear()
        return true
    }

    while (frames.isNotEmpty() && !budget.exhausted) {
        val frame = frames.peekLast() ?: break
        if (frame.index >= frame.endExclusive) {
            if (!flush(frame)) break
            frames.removeLast()
            val wrapper = frame.wrapper
            val parent = frames.peekLast()
            if (wrapper != null && parent != null) {
                if (!budget.addNode()) break
                parent.output += when (wrapper.kind) {
                    InlineWrapperKind.BOLD -> MarkdownBold(frame.output.toList())
                    InlineWrapperKind.ITALIC -> MarkdownItalic(frame.output.toList())
                    InlineWrapperKind.STRIKE -> MarkdownStrike(frame.output.toList())
                    InlineWrapperKind.CODE -> MarkdownInlineCode(
                        frame.output.joinToString("") { node ->
                            (node as? MarkdownText)?.value.orEmpty()
                        },
                    )
                    InlineWrapperKind.LINK -> MarkdownLink(
                        children = frame.output.toList(),
                        url = wrapper.url.orEmpty(),
                    )
                }
            }
            continue
        }

        val index = frame.index
        if (frame.source[index] == '\\' && index + 1 < frame.endExclusive) {
            frame.plain.append(frame.source[index + 1])
            frame.index += 2
            continue
        }

        val linkEnd = if (frame.source[index] == '[') {
            frame.source.indexOf("](", index + 1).takeIf { it in (index + 1) until frame.endExclusive }
        } else {
            null
        }
        if (linkEnd != null) {
            val urlEnd = frame.source.indexOf(')', linkEnd + 2)
            if (urlEnd > linkEnd + 2 && urlEnd < frame.endExclusive) {
                if (!flush(frame)) break
                val child = InlineFrame(
                    source = frame.source,
                    endExclusive = linkEnd,
                    index = index + 1,
                    output = mutableListOf(),
                    wrapper = InlineWrapper(
                        kind = InlineWrapperKind.LINK,
                        url = frame.source.substring(linkEnd + 2, urlEnd).trim(),
                    ),
                    depth = frame.depth + 1,
                )
                frame.index = urlEnd + 1
                if (child.depth > MAX_MARKDOWN_INLINE_DEPTH) {
                    frame.plain.append(frame.source, index, frame.index)
                } else {
                    frames.addLast(child)
                }
                continue
            }
        }

        val marker = when {
            frame.source.startsWith("**", index) -> "**"
            frame.source.startsWith("__", index) -> "__"
            frame.source.startsWith("~~", index) -> "~~"
            frame.source[index] == '`' -> "`"
            frame.source[index] == '*' -> "*"
            frame.source[index] == '_' -> "_"
            else -> null
        }
        if (marker != null) {
            val end = frame.source.indexOf(marker, index + marker.length)
            if (end > index + marker.length && end < frame.endExclusive) {
                if (!flush(frame)) break
                val kind = when (marker) {
                    "**", "__" -> InlineWrapperKind.BOLD
                    "~~" -> InlineWrapperKind.STRIKE
                    "`" -> InlineWrapperKind.CODE
                    else -> InlineWrapperKind.ITALIC
                }
                frame.index = end + marker.length
                if (kind == InlineWrapperKind.CODE) {
                    if (!budget.addNode()) break
                    frame.output += MarkdownInlineCode(
                        frame.source.substring(index + marker.length, end),
                    )
                    continue
                }
                val child = InlineFrame(
                    source = frame.source,
                    endExclusive = end,
                    index = index + marker.length,
                    output = mutableListOf(),
                    wrapper = InlineWrapper(kind),
                    depth = frame.depth + 1,
                )
                if (child.depth > MAX_MARKDOWN_INLINE_DEPTH) {
                    frame.plain.append(frame.source, index, frame.index)
                } else {
                    frames.addLast(child)
                }
                continue
            }
        }

        frame.plain.append(frame.source[index])
        frame.index++
    }
    return result
}

private fun splitMarkdownLines(source: String): List<String> {
    val lines = ArrayList<String>(minOf(MAX_MARKDOWN_LINES, 128))
    var start = 0
    var index = 0
    while (index < source.length && lines.size < MAX_MARKDOWN_LINES) {
        if (source[index] == '\n') {
            lines += source.substring(start, index)
            start = index + 1
        }
        index++
    }
    if (lines.size < MAX_MARKDOWN_LINES && start <= source.length) {
        lines += source.substring(start)
    }
    return lines
}

private fun String.takeUtf8Bytes(maxBytes: Long): String {
    if (maxBytes <= 0L) return ""
    var used = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        val charBytes = when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 4
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            else -> 3
        }
        if (used + charBytes > maxBytes) break
        used += charBytes
        index += if (charBytes == 4) 2 else 1
    }
    return substring(0, index)
}

private val COMMON_KEYWORDS = setOf("true", "false", "null")
private val LANGUAGE_KEYWORDS = mapOf(
    "kotlin" to setOf(
        "as", "break", "class", "continue", "data", "else", "fun", "if", "import", "in", "interface",
        "is", "object", "open", "override", "package", "private", "public", "return", "sealed", "val", "var",
        "when", "while", "suspend", "companion", "this", "super",
    ),
    "python" to setOf(
        "and", "as", "assert", "async", "await", "class", "def", "elif", "else", "for", "from", "if", "import",
        "in", "is", "lambda", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    ),
    "bash" to setOf("case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in", "then", "until", "while"),
    "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
    "json" to COMMON_KEYWORDS,
    "javascript" to setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "export", "extends", "finally", "for", "from", "function", "if", "import",
        "in", "instanceof", "let", "new", "of", "return", "switch", "this", "throw", "try", "typeof",
        "var", "void", "while", "with", "yield", "undefined",
    ),
    "typescript" to setOf(
        "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "default", "else",
        "enum", "export", "extends", "finally", "for", "from", "function", "if", "implements", "import",
        "interface", "keyof", "let", "namespace", "new", "of", "private", "public", "readonly", "return",
        "static", "switch", "this", "throw", "try", "type", "typeof", "var", "void", "while", "with",
        "yield",
    ),
    "java" to setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
    ),
    "csharp" to setOf(
        "abstract", "as", "async", "await", "base", "bool", "break", "case", "catch", "class", "const", "continue",
        "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false",
        "finally", "fixed", "float", "for", "foreach", "if", "implicit", "in", "int", "interface", "internal", "is",
        "lock", "long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private",
        "protected", "public", "readonly", "ref", "return", "sealed", "short", "sizeof", "stackalloc", "static",
        "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe",
        "ushort", "using", "virtual", "void", "volatile", "while",
    ),
    "go" to setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func",
        "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch",
        "type", "var",
    ),
    "rust" to setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false",
        "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
    ),
    "swift" to setOf(
        "as", "break", "case", "catch", "class", "continue", "defer", "default", "do", "else", "enum", "extension",
        "fallthrough", "false", "for", "func", "if", "import", "in", "init", "let", "nil", "override", "private",
        "protocol", "public", "repeat", "return", "self", "static", "struct", "super", "switch", "throw", "throws",
        "true", "try", "typealias", "var", "where", "while",
    ),
    "sql" to setOf(
        "and", "as", "asc", "between", "by", "case", "create", "delete", "desc", "distinct", "drop", "else", "end",
        "from", "group", "having", "in", "insert", "into", "is", "join", "left", "like", "limit", "not", "null", "on",
        "or", "order", "outer", "select", "set", "table", "then", "union", "update", "values", "when", "where", "with",
    ),
)

private fun tokenizeCode(code: String, language: String): List<MarkdownCodeToken> {
    val canonicalLanguage = when (language.lowercase()) {
        "kt" -> "kotlin"
        "kts" -> "kotlin"
        "py" -> "python"
        "sh", "shell", "zsh" -> "bash"
        "yml" -> "yaml"
        "js", "jsx", "mjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "cs" -> "csharp"
        "golang" -> "go"
        "rs" -> "rust"
        "html", "xhtml" -> "xml"
        else -> language.lowercase()
    }
    val keywords = LANGUAGE_KEYWORDS[canonicalLanguage].orEmpty()
    val result = mutableListOf<MarkdownCodeToken>()
    val plain = StringBuilder()
    var tokenCount = 0
    var tokenLimitHit = false

    fun emit(token: MarkdownCodeToken): Boolean {
        if (tokenCount >= MAX_MARKDOWN_CODE_TOKENS) {
            tokenLimitHit = true
            return false
        }
        result += token
        tokenCount++
        return true
    }

    fun flushPlain(): Boolean {
        if (plain.isNotEmpty()) {
            if (!emit(MarkdownCodeToken(plain.toString(), MarkdownCodeTokenKind.PLAIN))) return false
            plain.clear()
        }
        return true
    }

    var index = 0
    while (index < code.length && !tokenLimitHit) {
        val current = code[index]
        val lineComment = when {
            current == '#' && canonicalLanguage in setOf("python", "bash", "yaml", "ruby") -> true
            current == '-' && index + 1 < code.length && code[index + 1] == '-' && canonicalLanguage == "sql" -> true
            current == '/' && index + 1 < code.length && code[index + 1] == '/' &&
                canonicalLanguage in setOf("kotlin", "json", "javascript", "typescript", "java", "csharp", "go", "rust", "swift", "css") -> true
            current == '<' && code.startsWith("<!--", index) && canonicalLanguage == "xml" -> true
            else -> false
        }
        if (lineComment) {
            if (!flushPlain()) break
            val end = when {
                current == '<' -> code.indexOf("-->", index + 4).let { if (it < 0) code.length else it + 3 }
                else -> code.indexOf('\n', index).let { if (it < 0) code.length else it }
            }
            if (!emit(MarkdownCodeToken(code.substring(index, end), MarkdownCodeTokenKind.COMMENT))) break
            index = end
            continue
        }

        if (current == '/' && index + 1 < code.length && code[index + 1] == '*' &&
            canonicalLanguage in setOf("kotlin", "javascript", "typescript", "java", "csharp", "go", "rust", "swift", "css", "xml")
        ) {
            if (!flushPlain()) break
            val close = code.indexOf("*/", index + 2)
            val end = if (close < 0) code.length else close + 2
            if (!emit(MarkdownCodeToken(code.substring(index, end), MarkdownCodeTokenKind.COMMENT))) break
            index = end
            continue
        }

        if (current == '"' || current == '\'') {
            if (!flushPlain()) break
            val quote = current
            var end = index + 1
            var escaped = false
            while (end < code.length) {
                val character = code[end]
                if (!escaped && character == quote) {
                    end++
                    break
                }
                escaped = !escaped && character == '\\'
                if (character != '\\') escaped = false
                end++
            }
            if (!emit(MarkdownCodeToken(code.substring(index, end), MarkdownCodeTokenKind.STRING))) break
            index = end
            continue
        }

        if (current.isDigit() && (index == 0 || !code[index - 1].isLetterOrDigit())) {
            if (!flushPlain()) break
            var end = index + 1
            while (end < code.length && (code[end].isDigit() || code[end] in ".xABCDEFXabcdef")) end++
            if (!emit(MarkdownCodeToken(code.substring(index, end), MarkdownCodeTokenKind.NUMBER))) break
            index = end
            continue
        }

        if (current.isLetter() || current == '_') {
            var end = index + 1
            while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
            val word = code.substring(index, end)
            if (word in keywords || word in COMMON_KEYWORDS) {
                if (!flushPlain()) break
                if (!emit(MarkdownCodeToken(word, MarkdownCodeTokenKind.KEYWORD))) break
            } else {
                plain.append(word)
            }
            index = end
            continue
        }

        plain.append(current)
        index++
    }
    if (!tokenLimitHit) flushPlain()
    return result
}

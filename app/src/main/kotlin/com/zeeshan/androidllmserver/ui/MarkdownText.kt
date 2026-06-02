package com.zeeshan.androidllmserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Strip leftover chat-template / mtmd / thinking tags that the model
 * sometimes echoes verbatim into its output.
 *
 * We do this on display rather than mid-stream because the streaming
 * parser already does buffer-aware <think> handling, and the rest of
 * these tags are short fixed strings that we can safely scrub once
 * generation finishes (or per-token, since the strings don't span
 * boundaries any worse than they already would in the source).
 */
fun sanitizeAssistantText(raw: String): String {
    if (raw.isEmpty()) return raw
    return raw
        // Gemma chat template
        .replace(Regex("""<start_of_turn>(user|model|system)\n?""", RegexOption.IGNORE_CASE), "")
        .replace("<end_of_turn>", "", ignoreCase = true)
        // ChatML (in case a non-Gemma model template leaks too)
        .replace(Regex("""<\|im_start\|>(user|assistant|system)\n?""", RegexOption.IGNORE_CASE), "")
        .replace("<|im_end|>", "", ignoreCase = true)
        // mtmd marker (should never reach the user, but be safe)
        .replace("<__media__>", "")
        // Residual <think> tags — primary parser strips these but late-arriving
        // partials can slip past on cancel.
        .replace("<think>", "").replace("</think>", "")
        .trimStart()
}

// ── Minimal markdown rendering ──────────────────────────────────────────────
// Supports the common subset that chat models actually emit:
//   - paragraphs of inline text
//   - **bold**, *italic*, `inline code`
//   - fenced code blocks (``` ... ```)
//   - simple bullet/numbered lists
//   - "# heading" up to three levels
// Anything fancier (tables, footnotes, etc.) renders as plain text.

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val lang: String?, val code: String) : MdBlock()
    data class ListItem(val marker: String, val text: String) : MdBlock()
}

private fun parseBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.split('\n')
    var i = 0
    val paraBuf = StringBuilder()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            out.add(MdBlock.Paragraph(paraBuf.toString().trimEnd()))
            paraBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // Code fence — ``` optionally followed by lang tag
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out.add(MdBlock.CodeBlock(lang, code.toString()))
                if (i < lines.size) i++ // skip closing fence
                continue
            }
            // Headings: support all six levels, with or without space after the hashes.
            // Some models emit "####Heading" or "## **Heading**"; we normalise by
            // stripping leading hashes and any single trailing space.
            trimmed.startsWith("#") -> {
                var hashes = 0
                while (hashes < 6 && hashes < trimmed.length && trimmed[hashes] == '#') hashes++
                if (hashes in 1..6 && (hashes == trimmed.length || trimmed[hashes] == ' ' || trimmed[hashes] == '#')) {
                    flushPara()
                    val text = trimmed.drop(hashes).trimStart()
                    out.add(MdBlock.Heading(hashes, text))
                    i++; continue
                }
                // not actually a heading (e.g. "#1234" hashtag) — fall through to paragraph
                if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                paraBuf.append(line)
                i++; continue
            }
            // Bullet lists
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                out.add(MdBlock.ListItem("•", trimmed.drop(2)))
                i++; continue
            }
            // Numbered lists ("1. foo")
            trimmed.length > 2 && trimmed[0].isDigit() && trimmed.indexOf(". ") in 1..3 -> {
                flushPara()
                val dot = trimmed.indexOf(". ")
                out.add(MdBlock.ListItem(trimmed.substring(0, dot + 1), trimmed.substring(dot + 2)))
                i++; continue
            }
            // Blank line ends a paragraph
            line.isBlank() -> { flushPara(); i++; continue }
            else -> {
                if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                paraBuf.append(line)
                i++
            }
        }
    }
    flushPara()
    return out
}

/**
 * Build inline AnnotatedString from a paragraph of markdown-ish text.
 * Handles **bold**, *italic*, and `code` tokens. Doesn't try to parse
 * markdown links — they're rare in chat output and adding them right
 * means handling escape sequences too.
 */
private fun buildInline(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = raw.length
    while (i < n) {
        when {
            // Inline code: `text`
            raw[i] == '`' -> {
                val end = raw.indexOf('`', startIndex = i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33808080))) {
                        append(raw.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(raw[i]); i++
                }
            }
            // Bold: **text**
            i + 1 < n && raw[i] == '*' && raw[i + 1] == '*' -> {
                val end = raw.indexOf("**", startIndex = i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(raw[i]); i++
                }
            }
            // Italic: *text* (single, not **)
            raw[i] == '*' -> {
                val end = raw.indexOf('*', startIndex = i + 1)
                if (end > i && (end + 1 >= n || raw[end + 1] != '*')) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(raw[i]); i++
                }
            }
            else -> { append(raw[i]); i++ }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Spacer(Modifier.padding(top = 4.dp))
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = buildInline(block.text),
                    color = color,
                    style = style,
                )
                is MdBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(color = color, fontWeight = FontWeight.Bold)
                    Text(text = buildInline(block.text), style = headingStyle)
                }
                is MdBlock.CodeBlock -> Text(
                    text = block.code,
                    color = color,
                    style = style.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22808080))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                is MdBlock.ListItem -> Row {
                    Text(text = block.marker, color = color, style = style)
                    Spacer(Modifier.width(6.dp))
                    Text(text = buildInline(block.text), color = color, style = style)
                }
            }
        }
    }
}

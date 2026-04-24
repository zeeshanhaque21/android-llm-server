package com.zeeshan.androidllmserver.http

import com.zeeshan.androidllmserver.llm.LlmBridge
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { encodeDefaults = true }

/**
 * Install all OpenAI-compatible routes into the Ktor routing tree.
 *
 * @param bridge  the LlmBridge instance that owns the loaded model
 * @param modelName  human-readable name shown in /v1/models (e.g. "qwen2.5:1.5b-q4_k_m")
 */
fun Routing.installOpenAiRoutes(bridge: LlmBridge, modelName: String) {

    get("/health") {
        call.respond(mapOf("status" to "ok"))
    }

    get("/v1/models") {
        val now = System.currentTimeMillis() / 1000
        call.respond(
            ModelsResponse(
                data = listOf(
                    ModelInfo(id = modelName, created = now)
                )
            )
        )
    }

    post("/v1/chat/completions") {
        val request = call.receive<ChatCompletionRequest>()
        val (prompt, media) = buildPromptAndMedia(request.messages, modelName)
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        val effectiveModel = request.model.ifBlank { modelName }

        // Route: use multimodal path only when the bridge actually has an
        // mmproj loaded AND the request carries media.
        val useMm = media.isNotEmpty() && bridge.supportsMultimodal

        if (request.stream) {
            handleStreaming(call, bridge, prompt, media, useMm, request, requestId, created, effectiveModel)
        } else {
            handleNonStreaming(call, bridge, prompt, media, useMm, request, requestId, created, effectiveModel)
        }
    }
}

// ── Non-streaming handler ───────────────────────────────────────────────────

private suspend fun handleNonStreaming(
    call: io.ktor.server.application.ApplicationCall,
    bridge: LlmBridge,
    prompt: String,
    media: List<MediaPart>,
    useMm: Boolean,
    request: ChatCompletionRequest,
    requestId: String,
    created: Long,
    model: String,
) {
    val tokens = if (useMm) {
        bridge.generateMultimodal(prompt, media.map { it.bytes() }, request.maxTokens).toList()
    } else {
        bridge.generate(prompt, request.maxTokens).toList()
    }
    val fullText = tokens.joinToString("")

    // Token counts are approximations — llama.cpp doesn't expose prompt token
    // counts through our JNI surface. Use whitespace-split as a rough proxy.
    val promptTokens = prompt.split(Regex("\\s+")).size
    val completionTokens = tokens.size

    call.respond(
        ChatCompletionResponse(
            id = requestId,
            created = created,
            model = model,
            choices = listOf(
                Choice(
                    message = ChatMessage(role = "assistant", content = fullText),
                )
            ),
            usage = Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            ),
        )
    )
}

// ── Streaming handler (SSE) ─────────────────────────────────────────────────

private suspend fun handleStreaming(
    call: io.ktor.server.application.ApplicationCall,
    bridge: LlmBridge,
    prompt: String,
    media: List<MediaPart>,
    useMm: Boolean,
    request: ChatCompletionRequest,
    requestId: String,
    created: Long,
    model: String,
) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        // First chunk: send the role
        val roleChunk = ChatCompletionChunk(
            id = requestId,
            created = created,
            model = model,
            choices = listOf(
                ChunkChoice(delta = Delta(role = "assistant"))
            ),
        )
        write("data: ${json.encodeToString(roleChunk)}\n\n")
        flush()

        // Stream tokens
        try {
            val flow = if (useMm) {
                bridge.generateMultimodal(prompt, media.map { it.bytes() }, request.maxTokens)
            } else {
                bridge.generate(prompt, request.maxTokens)
            }
            flow.collect { token ->
                val chunk = ChatCompletionChunk(
                    id = requestId,
                    created = created,
                    model = model,
                    choices = listOf(
                        ChunkChoice(delta = Delta(content = token))
                    ),
                )
                write("data: ${json.encodeToString(chunk)}\n\n")
                flush()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Client disconnected — Flow collection cancelled, which triggers
            // nativeCancel via LlmBridge's finally block. Nothing else to do.
            return@respondTextWriter
        }

        // Final chunk: finish_reason = "stop"
        val doneChunk = ChatCompletionChunk(
            id = requestId,
            created = created,
            model = model,
            choices = listOf(
                ChunkChoice(
                    delta = Delta(),
                    finishReason = "stop",
                )
            ),
        )
        write("data: ${json.encodeToString(doneChunk)}\n\n")
        write("data: [DONE]\n\n")
        flush()
    }
}

// ── Ollama-compatible routes ─────────────────────────────────────────────────

fun Routing.installOllamaRoutes(bridge: LlmBridge, modelName: String) {

    get("/api/tags") {
        val now = System.currentTimeMillis() / 1000
        call.respond(OllamaTagsResponse(
            models = listOf(OllamaModelEntry(
                name = modelName,
                model = modelName,
                modifiedAt = java.time.Instant.ofEpochSecond(now).toString(),
            ))
        ))
    }

    post("/api/chat") {
        val body = call.receiveText()
        val req = Json.decodeFromString<OllamaChatRequest>(body)

        // Map Ollama messages into the same InboundMessage shape the OpenAI
        // path uses so multimodal handling is identical.
        val inbound = req.messages.map { m ->
            val parts = mutableListOf<InboundPart>()
            if (m.content.isNotEmpty()) parts.add(InboundPart.Text(m.content))
            m.images?.forEach { b64 ->
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                parts.add(InboundPart.Media(MediaPart.Image(bytes)))
            }
            InboundMessage(m.role, parts)
        }
        val (prompt, media) = buildPromptAndMedia(inbound, modelName)
        val useMm = media.isNotEmpty() && bridge.supportsMultimodal
        val model = req.model.ifBlank { modelName }
        val stream = req.stream

        if (stream) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Application.Json) {
                try {
                    val flow = if (useMm) {
                        bridge.generateMultimodal(prompt, media.map { it.bytes() }, 256)
                    } else {
                        bridge.generate(prompt, 256)
                    }
                    flow.collect { token ->
                        val chunk = OllamaChatResponse(
                            model = model,
                            message = OllamaChatMessage(role = "assistant", content = token),
                            done = false,
                        )
                        write(json.encodeToString(chunk) + "\n")
                        flush()
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@respondTextWriter
                }
                // Final done message
                val done = OllamaChatResponse(
                    model = model,
                    message = OllamaChatMessage(role = "assistant", content = ""),
                    done = true,
                )
                write(json.encodeToString(done) + "\n")
                flush()
            }
        } else {
            val tokens = if (useMm) {
                bridge.generateMultimodal(prompt, media.map { it.bytes() }, 256).toList()
            } else {
                bridge.generate(prompt, 256).toList()
            }
            val fullText = tokens.joinToString("")
            call.respond(OllamaChatResponse(
                model = model,
                message = OllamaChatMessage(role = "assistant", content = fullText),
                done = true,
            ))
        }
    }
}

// ── Prompt formatting ───────────────────────────────────────────────────────

private const val MEDIA_MARKER = "<__media__>"

/**
 * Extension that yields the raw encoded bytes of any [MediaPart] variant.
 * libmtmd handles JPEG/PNG/WAV/MP3/FLAC byte-level decoding internally.
 */
internal fun MediaPart.bytes(): ByteArray = when (this) {
    is MediaPart.Image -> bytes
    is MediaPart.Audio -> bytes
}

/**
 * Build the prompt string and ordered media list for inference.
 *
 * Chooses a chat template based on the loaded model name:
 *   - Gemma family ("gemma")      -> <start_of_turn>/<end_of_turn>
 *   - Everything else             -> ChatML (<|im_start|>/<|im_end|>)
 *
 * Media placeholders are emitted inline with the user text so libmtmd's
 * mtmd_tokenize can swap in image/audio token chunks at the right position.
 */
internal fun buildPromptAndMedia(
    messages: List<InboundMessage>,
    modelName: String,
): Pair<String, List<MediaPart>> {
    val useGemma = modelName.contains("gemma", ignoreCase = true)
    val media = mutableListOf<MediaPart>()
    val sb = StringBuilder()

    for (msg in messages) {
        // Interleave the message's parts so media markers sit exactly where
        // the caller placed them between text chunks.
        val content = buildString {
            for (p in msg.parts) when (p) {
                is InboundPart.Text  -> append(p.text)
                is InboundPart.Media -> {
                    append(MEDIA_MARKER)
                    media.add(p.media)
                }
            }
        }

        if (useGemma) {
            // Gemma templates use "user" and "model" roles.
            val role = if (msg.role == "assistant") "model" else msg.role
            sb.append("<start_of_turn>$role\n$content<end_of_turn>\n")
        } else {
            sb.append("<|im_start|>${msg.role}\n$content<|im_end|>\n")
        }
    }

    // Open the assistant turn.
    sb.append(if (useGemma) "<start_of_turn>model\n" else "<|im_start|>assistant\n")
    return sb.toString() to media
}

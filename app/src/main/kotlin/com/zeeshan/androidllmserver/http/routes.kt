package com.zeeshan.androidllmserver.http

import com.zeeshan.androidllmserver.llm.InferenceBackend
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
 * Status/discovery routes that are ALWAYS registered, independent of whether
 * a model loaded. This is the fix for the silent-failure bug: a client must be
 * able to hit /health and /v1/models and learn the model state instead of
 * getting a blanket 404 when load fails or the selected runtime can't serve.
 *
 * @param modelName  the loaded model's name, or null when nothing is loaded
 * @param modelLoaded  true if any inference/image model is serving
 * @param loadError  human-readable load failure, surfaced via /health
 */
fun Routing.installStatusRoutes(modelName: String?, modelLoaded: Boolean, loadError: String?) {

    get("/health") {
        call.respond(
            HealthResponse(
                modelLoaded = modelLoaded,
                model = modelName.takeIf { modelLoaded },
                error = loadError,
            )
        )
    }

    get("/v1/models") {
        val now = System.currentTimeMillis() / 1000
        val data = if (modelLoaded && modelName != null) listOf(ModelInfo(id = modelName, created = now))
                   else emptyList()
        call.respond(ModelsResponse(data = data))
    }

    get("/api/tags") {
        val now = System.currentTimeMillis() / 1000
        val models = if (modelLoaded && modelName != null) listOf(
            OllamaModelEntry(
                name = modelName,
                model = modelName,
                modifiedAt = java.time.Instant.ofEpochSecond(now).toString(),
            )
        ) else emptyList()
        call.respond(OllamaTagsResponse(models = models))
    }
}

/**
 * Install the OpenAI-compatible chat route. Registered unconditionally so the
 * path never 404s; when [backend] is null (model failed to load or the runtime
 * couldn't serve) it returns a clear 503 carrying [loadError].
 *
 * @param backend  the engine (llama.cpp or LiteRT-LM) that owns the loaded model, or null
 * @param modelName  human-readable name echoed in responses
 */
fun Routing.installOpenAiRoutes(backend: InferenceBackend?, modelName: String, loadError: String?) {

    post("/v1/chat/completions") {
        if (backend == null) return@post call.respondModelUnavailable(loadError)

        val request = call.receive<ChatCompletionRequest>()
        val (prompt, media) = buildPromptAndMedia(request.messages, modelName)
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        val effectiveModel = request.model.ifBlank { modelName }

        // Route: use multimodal path only when the backend actually supports
        // non-text input AND the request carries media.
        val useMm = media.isNotEmpty() && backend.supportsMultimodal

        if (request.stream) {
            handleStreaming(call, backend, prompt, media, useMm, request, requestId, created, effectiveModel)
        } else {
            handleNonStreaming(call, backend, prompt, media, useMm, request, requestId, created, effectiveModel)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondModelUnavailable(loadError: String?) {
    val msg = loadError ?: "No model loaded"
    respondText(
        text = """{"error":{"message":"${msg.replace("\"", "\\\"")}","type":"model_not_loaded"}}""",
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.ServiceUnavailable,
    )
}

// ── Non-streaming handler ───────────────────────────────────────────────────

private suspend fun handleNonStreaming(
    call: io.ktor.server.application.ApplicationCall,
    backend: InferenceBackend,
    prompt: String,
    media: List<MediaPart>,
    useMm: Boolean,
    request: ChatCompletionRequest,
    requestId: String,
    created: Long,
    model: String,
) {
    val tokens = if (useMm) {
        backend.generateMultimodal(prompt, media.map { it.toMediaInput() }, request.maxTokens).toList()
    } else {
        backend.generate(prompt, request.maxTokens).toList()
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
    backend: InferenceBackend,
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
                backend.generateMultimodal(prompt, media.map { it.toMediaInput() }, request.maxTokens)
            } else {
                backend.generate(prompt, request.maxTokens)
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

fun Routing.installOllamaRoutes(backend: InferenceBackend?, modelName: String, loadError: String?) {

    post("/api/chat") {
        if (backend == null) return@post call.respondModelUnavailable(loadError)

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
        val useMm = media.isNotEmpty() && backend.supportsMultimodal
        val model = req.model.ifBlank { modelName }
        val stream = req.stream

        if (stream) {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(contentType = ContentType.Application.Json) {
                try {
                    val flow = if (useMm) {
                        backend.generateMultimodal(prompt, media.map { it.toMediaInput() }, 256)
                    } else {
                        backend.generate(prompt, 256)
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
                backend.generateMultimodal(prompt, media.map { it.toMediaInput() }, 256).toList()
            } else {
                backend.generate(prompt, 256).toList()
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

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
        val prompt = formatChatPrompt(request.messages)
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        val effectiveModel = request.model.ifBlank { modelName }

        if (request.stream) {
            handleStreaming(call, bridge, prompt, request, requestId, created, effectiveModel)
        } else {
            handleNonStreaming(call, bridge, prompt, request, requestId, created, effectiveModel)
        }
    }
}

// ── Non-streaming handler ───────────────────────────────────────────────────

private suspend fun handleNonStreaming(
    call: io.ktor.server.application.ApplicationCall,
    bridge: LlmBridge,
    prompt: String,
    request: ChatCompletionRequest,
    requestId: String,
    created: Long,
    model: String,
) {
    val tokens = bridge.generate(prompt, request.maxTokens).toList()
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
            bridge.generate(prompt, request.maxTokens).collect { token ->
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

// ── Prompt formatting ───────────────────────────────────────────────────────

/**
 * Format chat messages into a ChatML prompt string.
 *
 * Uses the `<|im_start|>` / `<|im_end|>` template which is widely supported
 * by models quantized for chat (Qwen, Mistral-instruct, etc.).
 */
internal fun formatChatPrompt(messages: List<ChatMessage>): String {
    val sb = StringBuilder()
    for (msg in messages) {
        sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
    }
    sb.append("<|im_start|>assistant\n")
    return sb.toString()
}

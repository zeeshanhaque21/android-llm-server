package com.zeeshan.androidllmserver.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Request ─────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<ChatMessage>,
    val temperature: Float = 0.7f,
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

// ── Non-streaming response ──────────────────────────────────────────────────

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

// ── Streaming chunk ─────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
)

// ── Models endpoint ─────────────────────────────────────────────────────────

@Serializable
data class ModelsResponse(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelInfo>,
)

// ── Ollama-compatible DTOs ───────────────────────────────────────────────────

@Serializable
data class OllamaChatRequest(
    val model: String = "",
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = true,
)

@Serializable
data class OllamaChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelEntry>,
)

@Serializable
data class OllamaModelEntry(
    val name: String,
    val model: String,
    @SerialName("modified_at") val modifiedAt: String = "",
    val size: Long = 0,
    val digest: String = "",
)

@Serializable
data class OllamaChatResponse(
    val model: String,
    val message: OllamaChatMessage,
    val done: Boolean,
)

// ── Models endpoint ─────────────────────────────────────────────────────────

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val obj: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "local",
)

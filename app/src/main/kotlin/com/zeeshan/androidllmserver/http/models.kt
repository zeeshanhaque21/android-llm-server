package com.zeeshan.androidllmserver.http

import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── Request ─────────────────────────────────────────────────────────────────
//
// Incoming chat completions carry `content` as either a string (text only)
// or an OpenAI-style array of typed parts:
//   [{"type":"text","text":"..."},
//    {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}},
//    {"type":"input_audio","input_audio":{"data":"<b64>","format":"mp3"}}]
//
// InboundMessage normalises both forms into text chunks plus decoded media
// bytes, ready for the JNI bridge.

@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<InboundMessage>,
    val temperature: Float = 0.7f,
    @SerialName("max_tokens") val maxTokens: Int = 256,
    val stream: Boolean = false,
)

sealed class MediaPart {
    data class Image(val bytes: ByteArray) : MediaPart()
    /** format: "mp3", "wav", "flac", etc. libmtmd auto-detects by sniffing bytes. */
    data class Audio(val bytes: ByteArray, val format: String) : MediaPart()
}

/**
 * A chat message after parts-normalisation. `parts` preserves the original
 * interleaving of text and media so prompt assembly can insert media markers
 * in the right places.
 */
@Serializable(with = InboundMessageSerializer::class)
data class InboundMessage(
    val role: String,
    val parts: List<InboundPart>,
) {
    fun textOnly(): String = parts.filterIsInstance<InboundPart.Text>().joinToString("") { it.text }
    fun media(): List<MediaPart> = parts.mapNotNull { (it as? InboundPart.Media)?.media }
    fun mediaCount(): Int = parts.count { it is InboundPart.Media }
}

sealed class InboundPart {
    data class Text(val text: String) : InboundPart()
    data class Media(val media: MediaPart) : InboundPart()
}

private object InboundMessageSerializer : KSerializer<InboundMessage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InboundMessage", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: InboundMessage) {
        throw UnsupportedOperationException("InboundMessage is decode-only")
    }

    override fun deserialize(decoder: Decoder): InboundMessage {
        val json = decoder as? JsonDecoder
            ?: throw IllegalStateException("InboundMessage only decodable from JSON")
        val obj = json.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("InboundMessage expected a JSON object")

        val role = (obj["role"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("message missing role")
        val contentEl = obj["content"]

        // Ollama-style images field: siblings to `content`, array of base64 strings.
        val imageList = (obj["images"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content
        } ?: emptyList()

        val parts = mutableListOf<InboundPart>()
        when (contentEl) {
            null -> { /* content absent */ }
            is JsonPrimitive -> parts.add(InboundPart.Text(contentEl.content))
            is JsonArray -> {
                for (partEl in contentEl) {
                    val part = partEl as? JsonObject ?: continue
                    when ((part["type"] as? JsonPrimitive)?.content) {
                        "text" ->
                            (part["text"] as? JsonPrimitive)?.content?.let {
                                parts.add(InboundPart.Text(it))
                            }
                        "image_url" -> {
                            val url = (part["image_url"] as? JsonObject)
                                ?.get("url")?.let { it as? JsonPrimitive }?.content
                            if (url != null) parts.add(InboundPart.Media(MediaPart.Image(decodeImageUrl(url))))
                        }
                        "input_audio" -> {
                            val body = part["input_audio"] as? JsonObject
                            val data = (body?.get("data") as? JsonPrimitive)?.content
                            val fmt  = (body?.get("format") as? JsonPrimitive)?.content ?: "wav"
                            if (data != null) parts.add(InboundPart.Media(
                                MediaPart.Audio(Base64.decode(data, Base64.DEFAULT), fmt)))
                        }
                        else -> { /* unknown type — skip */ }
                    }
                }
            }
            else -> throw IllegalArgumentException("content must be string or array")
        }

        // Append Ollama-style images after the text content.
        for (b64 in imageList) {
            parts.add(InboundPart.Media(MediaPart.Image(Base64.decode(b64, Base64.DEFAULT))))
        }

        return InboundMessage(role, parts)
    }

    /**
     * Accepts `data:<mime>;base64,<b64>`, a raw base64 string, or an http(s)
     * URL. For security we do NOT fetch http(s) URLs from the phone — this
     * server is meant to be a LAN endpoint, not an outbound fetcher. Data
     * URLs are the expected shape for inline images.
     */
    private fun decodeImageUrl(url: String): ByteArray = when {
        url.startsWith("data:") -> {
            val comma = url.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("malformed data URL")
            val payload = url.substring(comma + 1)
            // data:<mime>[;base64],<payload>
            if (url.substring(0, comma).contains(";base64"))
                Base64.decode(payload, Base64.DEFAULT)
            else
                payload.toByteArray(Charsets.UTF_8)
        }
        url.startsWith("http://") || url.startsWith("https://") ->
            throw IllegalArgumentException("remote image_url not supported — send as data URL")
        else -> Base64.decode(url, Base64.DEFAULT)
    }
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
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
    /** Base64-encoded images per Ollama's multimodal protocol. */
    val images: List<String>? = null,
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

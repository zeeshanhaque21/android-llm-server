package com.zeeshan.androidllmserver.llm

import kotlinx.coroutines.flow.Flow

/**
 * A single non-text input attached to a chat turn.
 *
 * Neutral over the two engines: [LlmBridge] (llama.cpp / libmtmd) treats
 * image and audio bytes uniformly and sniffs the format itself, while
 * [LiteRtBridge] (LiteRT-LM) needs the image/audio distinction to route
 * each part to the right encoder (`Content.ImageBytes` vs `Content.AudioBytes`).
 * Keeping the variant here lets both backends do the right thing from one
 * HTTP request shape.
 */
sealed interface MediaInput {
    val bytes: ByteArray

    /** JPEG/PNG bytes. */
    class Image(override val bytes: ByteArray) : MediaInput

    /** MP3/WAV/FLAC bytes. [format] is advisory — libmtmd sniffs anyway. */
    class Audio(override val bytes: ByteArray, val format: String = "wav") : MediaInput
}

/**
 * Common surface the HTTP layer drives, implemented by both inference
 * engines so [com.zeeshan.androidllmserver.http] never branches on the
 * concrete backend.
 *
 *   - [LlmBridge]    — llama.cpp/GGUF via JNI, multimodal through an mmproj sidecar
 *   - [LiteRtBridge] — Google LiteRT-LM (.litertlm), multimodal natively
 *
 * Backend selection happens upstream in the service by model file extension.
 */
interface InferenceBackend {
    /** True if the loaded model can take image input. */
    val supportsVision: Boolean
    /** True if the loaded model can take audio input. */
    val supportsAudio: Boolean
    /** True if the loaded model accepts any non-text input. */
    val supportsMultimodal: Boolean

    /** Text-only completion, streamed as a Flow of token pieces. */
    fun generate(prompt: String, maxTokens: Int = 256): Flow<String>

    /**
     * Completion with interleaved [media]. The [prompt] carries one
     * `<__media__>` marker per element in order; backends that don't use
     * markers (LiteRT-LM) ignore them and attach the media as discrete
     * content parts.
     */
    fun generateMultimodal(prompt: String, media: List<MediaInput>, maxTokens: Int = 256): Flow<String>

    suspend fun free()
}

package com.zeeshan.androidllmserver.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Second inference engine, parallel to [LlmBridge].
 *
 * Wraps Google's LiteRT-LM (formerly MediaPipe LLM) Kotlin SDK for
 * .litertlm models. The Maven artifact ships hand-tuned Adreno OpenCL
 * kernels — the same path Edge Gallery uses, and meaningfully faster
 * than ggml-opencl on Snapdragon for the model architectures Google
 * has tuned for.
 *
 * Multimodal (image + audio) inputs go through Contents.of(...) with
 * Content.Text/ImageBytes/AudioBytes parts. Vision and audio backends
 * are wired at engine init; for text-only models the SDK ignores the
 * unused backend slots.
 */
class LiteRtBridge : InferenceBackend {

    @Volatile private var engine: Engine? = null

    /**
     * Load a .litertlm model.
     *
     * useGpu hints which backend to prefer for the LLM body. Vision
     * stays on GPU (Adreno OpenCL is great at conv ops) and audio on
     * CPU (the audio encoder is small and CPU-faster).
     */
    suspend fun load(modelPath: String, useGpu: Boolean = true) {
        check(engine == null) { "LiteRT engine already loaded" }
        Log.i(TAG, "loading LiteRT model: $modelPath (gpu=$useGpu)")
        val cfg = EngineConfig(
            modelPath = modelPath,
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(),
        )
        val e = Engine(cfg)
        e.initialize()
        engine = e
        Log.i(TAG, "LiteRT model loaded")
    }

    /** Text-only single-shot completion as a Flow of partial chunks. */
    override fun generate(prompt: String, @Suppress("UNUSED_PARAMETER") maxTokens: Int): Flow<String> = flow {
        val e = engine ?: error("LiteRT model not loaded")
        e.createConversation().use { conv ->
            conv.sendMessageAsync(prompt).collect { msg -> emit(msg.toString()) }
        }
    }

    /**
     * Multimodal generation. Each [MediaInput] is mapped to a LiteRT-LM
     * content part — images to `Content.ImageBytes`, audio to
     * `Content.AudioBytes` — preserving the order the caller assembled
     * them, then the text prompt is appended. Any `<__media__>` markers in
     * [prompt] are inert here; LiteRT-LM attaches media as discrete parts
     * rather than splicing on a marker the way libmtmd does.
     */
    override fun generateMultimodal(
        prompt: String,
        media: List<MediaInput>,
        @Suppress("UNUSED_PARAMETER") maxTokens: Int,
    ): Flow<String> = flow {
        val e = engine ?: error("LiteRT model not loaded")
        val parts = mutableListOf<Content>()
        for (m in media) when (m) {
            is MediaInput.Image -> parts.add(Content.ImageBytes(m.bytes))
            is MediaInput.Audio -> parts.add(Content.AudioBytes(m.bytes))
        }
        parts.add(Content.Text(prompt))
        e.createConversation().use { conv ->
            conv.sendMessageAsync(Contents.of(*parts.toTypedArray()))
                .collect { msg -> emit(msg.toString()) }
        }
    }

    // We don't have a clean way to ask the loaded model whether it
    // supports vision/audio without attempting an inference, so we
    // optimistically advertise both. If a text-only .litertlm rejects
    // a multimodal Contents call, the error surfaces in the chat
    // bubble via ChatScreen's catch block.
    override val supportsVision: Boolean get() = true
    override val supportsAudio:  Boolean get() = true
    override val supportsMultimodal: Boolean get() = true

    override suspend fun free() {
        engine?.close()
        engine = null
    }

    private companion object { const val TAG = "LiteRtBridge" }
}

package com.zeeshan.androidllmserver.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin Kotlin wrapper around the native `llmbridge` library.
 *
 * llama.cpp context is single-threaded: all calls that touch it (load, generate,
 * free) must run on the same thread. We enforce that with a dedicated
 * single-thread dispatcher held for the lifetime of the app process.
 *
 * The public API is suspending and returns a Flow of token pieces for streaming.
 * Cancelling the collecting coroutine calls `nativeCancel()`, which flips an
 * atomic flag the native decode loop checks every iteration.
 */
class LlmBridge {

    private val handle = AtomicLong(0L)

    fun interface TokenCallback {
        // Must be public and match JNI signature: void onToken(String)
        fun onToken(token: String)
    }

    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4) {
        withContext(inferenceDispatcher) {
            check(handle.get() == 0L) { "model already loaded" }
            val h = nativeInit(modelPath, nCtx, nThreads)
            check(h != 0L) { "nativeInit failed for $modelPath" }
            handle.set(h)
        }
    }

    fun generate(prompt: String, maxTokens: Int = 256): Flow<String> = channelFlow {
        val h = handle.get()
        check(h != 0L) { "model not loaded" }

        val cb = TokenCallback { tok ->
            // trySend because the native thread is already the dispatcher thread
            // we pin generation to, and channelFlow's send is suspending.
            trySend(tok)
        }

        try {
            val n = nativeGenerate(h, prompt, maxTokens, cb)
            if (n < 0) error("nativeGenerate returned $n")
        } finally {
            // If the collector cancelled mid-stream, make sure the native loop
            // is told to stop. No-op if generation already returned.
            nativeCancel(h)
        }
    }.flowOn(inferenceDispatcher)

    suspend fun free() {
        withContext(inferenceDispatcher) {
            val h = handle.getAndSet(0L)
            if (h != 0L) nativeFree(h)
        }
    }

    // --- JNI surface (keep names in sync with llm_bridge.cpp) ---------------

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, nPredict: Int, cb: TokenCallback): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    companion object {
        // A single pinned thread so every llama.cpp call happens from the
        // same OS thread. Do NOT replace with Dispatchers.Default.
        val inferenceDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "llm-inference").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        @Volatile private var loaded = false
        fun ensureNativeLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("llmbridge")
                    loaded = true
                }
            }
        }
    }
}

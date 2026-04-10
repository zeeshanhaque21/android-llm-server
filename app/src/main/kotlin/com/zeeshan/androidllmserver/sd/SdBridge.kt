package com.zeeshan.androidllmserver.sd

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SdBridge {
    private val handle = AtomicLong(0L)

    suspend fun load(modelPath: String, useGpu: Boolean = false) {
        withContext(sdDispatcher) {
            check(handle.get() == 0L) { "SD model already loaded" }
            val h = nativeInit(modelPath, useGpu)
            check(h != 0L) { "Failed to load SD model: $modelPath" }
            handle.set(h)
        }
    }

    suspend fun generate(
        prompt: String,
        negativePrompt: String = "",
        width: Int = 512,
        height: Int = 512,
        steps: Int = 20,
        cfgScale: Float = 7.0f,
        seed: Long = -1,
    ): String {
        return withContext(sdDispatcher) {
            val h = handle.get()
            check(h != 0L) { "SD model not loaded" }
            nativeGenerate(h, prompt, negativePrompt, width, height, steps, cfgScale, seed)
        }
    }

    suspend fun free() {
        withContext(sdDispatcher) {
            val h = handle.getAndSet(0L)
            if (h != 0L) nativeFree(h)
        }
    }

    val isLoaded: Boolean get() = handle.get() != 0L

    private external fun nativeInit(modelPath: String, useGpu: Boolean): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, negativePrompt: String,
        width: Int, height: Int, steps: Int, cfgScale: Float, seed: Long,
    ): String
    private external fun nativeFree(handle: Long)

    companion object {
        val sdDispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "sd-inference").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        @Volatile private var loaded = false
        fun ensureNativeLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    try {
                        System.loadLibrary("sdbridge")
                        loaded = true
                    } catch (_: UnsatisfiedLinkError) {
                        // SD not built - that's OK, feature is optional
                    }
                }
            }
        }
    }
}

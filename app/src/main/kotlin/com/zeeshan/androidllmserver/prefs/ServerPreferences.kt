package com.zeeshan.androidllmserver.prefs

import android.content.Context
import android.content.SharedPreferences

class ServerPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_START, value).apply() }

    var lastModelPath: String?
        get() = prefs.getString(KEY_LAST_MODEL_PATH, null)
        set(value) { prefs.edit().putString(KEY_LAST_MODEL_PATH, value).apply() }

    var authEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTH_ENABLED, value).apply() }

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_AUTH_TOKEN, value).apply() }

    var httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)
        set(value) { prefs.edit().putInt(KEY_HTTP_PORT, value).apply() }

    var nCtx: Int
        get() = prefs.getInt(KEY_N_CTX, DEFAULT_N_CTX)
        set(value) { prefs.edit().putInt(KEY_N_CTX, value).apply() }

    var nThreads: Int
        get() = prefs.getInt(KEY_N_THREADS, DEFAULT_N_THREADS)
        set(value) { prefs.edit().putInt(KEY_N_THREADS, value).apply() }

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) { prefs.edit().putFloat(KEY_TEMPERATURE, value).apply() }

    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_GPU, value).apply() }

    /** Speculative decoding for LiteRT-LM (.litertlm). On by default. */
    var speculativeDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPECULATIVE, true)
        set(value) { prefs.edit().putBoolean(KEY_SPECULATIVE, value).apply() }

    var plusEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLUS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PLUS_ENABLED, value).apply() }

    var samsungWarningDismissed: Boolean
        get() = prefs.getBoolean(KEY_SAMSUNG_WARNING_DISMISSED, false)
        set(value) { prefs.edit().putBoolean(KEY_SAMSUNG_WARNING_DISMISSED, value).apply() }

    companion object {
        const val PREFS_NAME = "llm_server_prefs"
        const val DEFAULT_HTTP_PORT = 8085
        const val DEFAULT_N_CTX = 8192
        const val DEFAULT_N_THREADS = 8
        const val DEFAULT_TEMPERATURE = 0.7f

        private const val KEY_AUTO_START = "autostart"
        private const val KEY_LAST_MODEL_PATH = "last_model_path"
        private const val KEY_AUTH_ENABLED = "auth_enabled"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_N_CTX = "n_ctx"
        private const val KEY_N_THREADS = "n_threads"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_SPECULATIVE = "speculative_decoding"
        private const val KEY_PLUS_ENABLED = "plus_enabled"
        private const val KEY_SAMSUNG_WARNING_DISMISSED = "samsung_warning_dismissed"
    }
}

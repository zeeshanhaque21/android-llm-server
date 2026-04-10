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

    companion object {
        const val PREFS_NAME = "llm_server_prefs"
        const val DEFAULT_HTTP_PORT = 8085

        private const val KEY_AUTO_START = "autostart"
        private const val KEY_LAST_MODEL_PATH = "last_model_path"
        private const val KEY_AUTH_ENABLED = "auth_enabled"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_HTTP_PORT = "http_port"
    }
}

package com.zeeshan.androidllmserver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.zeeshan.androidllmserver.prefs.ServerPreferences
import com.zeeshan.androidllmserver.service.LlmService

/**
 * Starts the LLM server automatically after a device reboot, if the user
 * has opted in via [ServerPreferences.autoStartOnBoot] and a model path
 * was previously saved.
 *
 * Registered in AndroidManifest.xml with an intent filter for
 * [Intent.ACTION_BOOT_COMPLETED].
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = ServerPreferences(context)
        if (!prefs.autoStartOnBoot) {
            Log.d(TAG, "Auto-start on boot is disabled — skipping")
            return
        }

        val modelPath = prefs.lastModelPath
        if (modelPath.isNullOrBlank()) {
            Log.w(TAG, "Auto-start enabled but no model path saved — skipping")
            return
        }

        Log.i(TAG, "Boot completed — starting LLM server with model: $modelPath")
        val startIntent = Intent(context, LlmService::class.java).apply {
            action = LlmService.ACTION_START
            putExtra(LlmService.EXTRA_MODEL_PATH, modelPath)
        }

        // On Android 8+ (API 26+) we must use startForegroundService.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }
}

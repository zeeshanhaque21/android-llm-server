package com.zeeshan.androidllmserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.zeeshan.androidllmserver.MainActivity
import com.zeeshan.androidllmserver.http.LlmHttpServer
import com.zeeshan.androidllmserver.llm.LlmBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the LlmBridge lifecycle.
 *
 * The service holds a partial wakelock and a persistent notification so
 * Android (including Samsung One UI) keeps the process alive while the
 * screen is off. The HTTP server (Phase 3) will be started here too.
 *
 * Control via intent actions:
 *   ACTION_START — load the model at "model_path" extra and go foreground
 *   ACTION_STOP  — free the model, release wakelock, stop self
 */
class LlmService : Service() {

    companion object {
        private const val TAG = "LlmService"
        const val ACTION_START = "com.zeeshan.androidllmserver.action.START"
        const val ACTION_STOP = "com.zeeshan.androidllmserver.action.STOP"
        const val EXTRA_MODEL_PATH = "model_path"

        private const val CHANNEL_ID = "llm_server"
        private const val NOTIF_ID = 1
        private const val HTTP_PORT = 8085
    }

    // --- Binder for Activity / HTTP layer access ---

    inner class LocalBinder : Binder() {
        val service: LlmService get() = this@LlmService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // --- Public state ---

    var bridge: LlmBridge? = null
        private set

    val isModelLoaded: Boolean
        get() = bridge != null

    private var _isGenerating = false
    val isGenerating: Boolean get() = _isGenerating

    // --- Internals ---

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var loadJob: Job? = null
    private var httpServer: LlmHttpServer? = null

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // --- Start / Stop ---

    private fun handleStart(intent: Intent) {
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
            Log.e(TAG, "ACTION_START without model_path extra — ignoring")
            stopSelf()
            return
        }

        // Go foreground immediately to avoid the ANR window on Android 14+.
        startForeground(NOTIF_ID, buildNotification("LLM Server — loading model..."))
        acquireWakeLock()

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            try {
                LlmBridge.ensureNativeLoaded()
                val b = LlmBridge()
                b.load(modelPath)
                bridge = b
                Log.i(TAG, "Model loaded: $modelPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                updateNotification("LLM Server — error: ${e.message}")
                return@launch
            }

            // Start HTTP server now that the model is loaded.
            try {
                val modelFileName = modelPath.substringAfterLast('/')
                httpServer = LlmHttpServer(bridge!!, modelFileName, port = HTTP_PORT).also { it.start() }
                updateNotification("LLM Server — idle (port $HTTP_PORT)")
                Log.i(TAG, "HTTP server started on port $HTTP_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
                updateNotification("LLM Server — model loaded, HTTP error: ${e.message}")
            }
        }
    }

    private fun handleStop() {
        loadJob?.cancel()
        serviceScope.launch {
            httpServer?.stop()
            httpServer = null
            try {
                bridge?.free()
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing model", e)
            }
            bridge = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- Notification helpers ---

    fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * Called by the HTTP layer (or test code) to indicate generation state.
     * Updates the notification accordingly.
     */
    fun setGenerating(generating: Boolean) {
        _isGenerating = generating
        updateNotification(
            if (generating) "LLM Server — generating" else "LLM Server — idle"
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LlmService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LLM Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingOpen)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", pendingStop
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLM Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while the LLM inference server is running"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // --- Wakelock ---

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "llm-server::inference").apply {
            acquire()
        }
        Log.i(TAG, "Wakelock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "Wakelock released")
        }
        wakeLock = null
    }
}

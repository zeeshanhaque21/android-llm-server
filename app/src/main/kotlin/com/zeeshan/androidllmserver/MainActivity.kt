package com.zeeshan.androidllmserver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.model.CatalogEntry
import com.zeeshan.androidllmserver.model.ModelCatalog
import com.zeeshan.androidllmserver.model.ModelDownloadManager
import com.zeeshan.androidllmserver.model.ModelRepository
import com.zeeshan.androidllmserver.service.LlmService
import com.zeeshan.androidllmserver.ui.ModelsScreen
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

private const val SMOKE_PROMPT =
    "<|im_start|>user\nSay hello in one short sentence.<|im_end|>\n<|im_start|>assistant\n"

private enum class Screen { SERVER, MODELS }

class MainActivity : ComponentActivity() {

    private var llmService: LlmService? = null
    private var serviceBound by mutableStateOf(false)
    private var serviceStatus by mutableStateOf("stopped")
    private var testOutput by mutableStateOf("")
    private var currentScreen by mutableStateOf(Screen.SERVER)
    private var selectedModelPath by mutableStateOf<String?>(null)
    private var activeModelPath by mutableStateOf<String?>(null)

    private lateinit var modelRepository: ModelRepository
    private lateinit var modelCatalog: ModelCatalog
    private lateinit var downloadManager: ModelDownloadManager
    private var catalogEntries by mutableStateOf<List<CatalogEntry>>(emptyList())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as LlmService.LocalBinder
            llmService = localBinder.service
            serviceBound = true
            serviceStatus = if (localBinder.service.isModelLoaded) "running" else "loading model..."
            Log.i(TAG, "Bound to LlmService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            llmService = null
            serviceBound = false
            serviceStatus = "disconnected"
            Log.w(TAG, "LlmService disconnected")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS granted")
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS denied — service will still work but notification may not show")
        }
        // Start the service regardless — the permission only affects the notification visibility.
        startLlmService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelRepository = ModelRepository(this)
        modelCatalog = ModelCatalog(this)
        downloadManager = ModelDownloadManager(this)
        catalogEntries = modelCatalog.loadCatalog()

        // Default to first installed model if none selected
        val installed = modelRepository.listModels()
        if (selectedModelPath == null && installed.isNotEmpty()) {
            selectedModelPath = installed.first().path
        }

        setContent {
            MaterialTheme {
                when (currentScreen) {
                    Screen.SERVER -> {
                        Scaffold { padding ->
                            val scope = rememberCoroutineScope()
                            val selectedName = selectedModelPath
                                ?.substringAfterLast('/')
                                ?: "No model selected"

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text("android-llm-server", style = MaterialTheme.typography.titleLarge)
                                Text("LLM inference server", style = MaterialTheme.typography.bodySmall)

                                Text(
                                    "Model: $selectedName",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Status: $serviceStatus",
                                    style = MaterialTheme.typography.bodyMedium,
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Button(
                                        onClick = { requestStartService() },
                                        enabled = (serviceStatus == "stopped" || serviceStatus == "disconnected")
                                            && selectedModelPath != null,
                                    ) {
                                        Text("Start Server")
                                    }

                                    OutlinedButton(
                                        onClick = { stopLlmService() },
                                        enabled = serviceBound,
                                    ) {
                                        Text("Stop Server")
                                    }
                                }

                                // Models button
                                OutlinedButton(onClick = { currentScreen = Screen.MODELS }) {
                                    Text("Models")
                                }

                                // Test generate button — keeps smoke test capability
                                Button(
                                    onClick = {
                                        val svc = llmService ?: return@Button
                                        val bridge = svc.bridge ?: return@Button
                                        testOutput = ""
                                        scope.launch {
                                            runCatching {
                                                svc.setGenerating(true)
                                                bridge.generate(SMOKE_PROMPT, maxTokens = 128).collect { tok ->
                                                    testOutput += tok
                                                }
                                                svc.setGenerating(false)
                                            }.onFailure { e ->
                                                Log.e(TAG, "Test generate failed", e)
                                                testOutput = "Error: ${e.message}"
                                                svc.setGenerating(false)
                                            }
                                        }
                                    },
                                    enabled = serviceBound && llmService?.isModelLoaded == true,
                                ) {
                                    Text("Test Generate")
                                }

                                if (testOutput.isNotEmpty()) {
                                    Text(
                                        testOutput,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    Screen.MODELS -> {
                        Scaffold { padding ->
                            Column(modifier = Modifier.padding(padding)) {
                                ModelsScreen(
                                    modelRepository = modelRepository,
                                    catalog = catalogEntries,
                                    downloadManager = downloadManager,
                                    activeModelPath = activeModelPath,
                                    onLoadModel = { path ->
                                        selectedModelPath = path
                                        currentScreen = Screen.SERVER
                                    },
                                    onBack = { currentScreen = Screen.SERVER },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
            llmService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-bind if the service is already running (e.g., coming back from background).
        val intent = Intent(this, LlmService::class.java)
        bindService(intent, connection, 0)
    }

    private fun requestStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startLlmService()
        }
    }

    private fun startLlmService() {
        val modelPath = selectedModelPath
        if (modelPath == null) {
            serviceStatus = "error: no model selected"
            return
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            serviceStatus = "error: model not found at $modelPath"
            return
        }

        serviceStatus = "loading model..."
        activeModelPath = modelPath
        val intent = Intent(this, LlmService::class.java).apply {
            action = LlmService.ACTION_START
            putExtra(LlmService.EXTRA_MODEL_PATH, modelFile.absolutePath)
        }
        startForegroundService(intent)

        // Bind so we can access the bridge.
        bindService(Intent(this, LlmService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopLlmService() {
        val intent = Intent(this, LlmService::class.java).apply {
            action = LlmService.ACTION_STOP
        }
        startService(intent)
        serviceStatus = "stopped"
        activeModelPath = null
        testOutput = ""
    }
}

package com.zeeshan.androidllmserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.auth.AuthManager
import com.zeeshan.androidllmserver.model.CatalogEntry
import com.zeeshan.androidllmserver.model.ModelCatalog
import com.zeeshan.androidllmserver.model.ModelDownloadManager
import com.zeeshan.androidllmserver.model.ModelRepository
import com.zeeshan.androidllmserver.prefs.ServerPreferences
import com.zeeshan.androidllmserver.service.LlmService
import com.zeeshan.androidllmserver.ui.ChatScreen
import com.zeeshan.androidllmserver.ui.ModelsScreen
import com.zeeshan.androidllmserver.ui.SettingsDialog
import com.zeeshan.androidllmserver.ui.theme.LlmServerTheme
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

private const val SMOKE_PROMPT =
    "<|im_start|>user\nSay hello in one short sentence.<|im_end|>\n<|im_start|>assistant\n"

private enum class Screen { SERVER, MODELS, SETTINGS, CHAT }

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
    private lateinit var serverPreferences: ServerPreferences
    private lateinit var authManager: AuthManager
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelRepository = ModelRepository(this)
        modelCatalog = ModelCatalog(this)
        downloadManager = ModelDownloadManager(this)
        serverPreferences = ServerPreferences(this)
        authManager = AuthManager(this)
        catalogEntries = modelCatalog.loadCatalog()

        // Default to first installed model if none selected
        val installed = modelRepository.listModels()
        if (selectedModelPath == null && installed.isNotEmpty()) {
            selectedModelPath = installed.first().path
        }

        setContent {
            LlmServerTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var showSettingsDialog by mutableStateOf(false)

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "android-llm-server",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Home") },
                                selected = currentScreen == Screen.SERVER,
                                onClick = {
                                    currentScreen = Screen.SERVER
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                label = { Text("Models") },
                                selected = currentScreen == Screen.MODELS,
                                onClick = {
                                    currentScreen = Screen.MODELS
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                label = { Text("Chat") },
                                selected = currentScreen == Screen.CHAT,
                                onClick = {
                                    if (serviceBound && llmService?.isModelLoaded == true) {
                                        currentScreen = Screen.CHAT
                                    } else {
                                        Toast.makeText(this@MainActivity, "Load a model first", Toast.LENGTH_SHORT).show()
                                    }
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                                selected = false,
                                onClick = {
                                    showSettingsDialog = true
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    },
                ) {
                    when (currentScreen) {
                        Screen.SERVER -> {
                            val selectedName = selectedModelPath
                                ?.substringAfterLast('/')
                                ?: "No model selected"

                            val wifiIp = getWifiIpAddress(this@MainActivity)
                            val port = serverPreferences.httpPort
                            val token = authManager.getOrCreateToken()
                            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                            var showSamsungWarning by mutableStateOf(
                                isSamsung && !serverPreferences.samsungWarningDismissed
                            )
                            val isRunning = serviceStatus == "running"

                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text("android-llm-server") },
                                        navigationIcon = {
                                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                                            }
                                        },
                                    )
                                },
                            ) { padding ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(padding)
                                        .padding(horizontal = 16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Spacer(Modifier.height(4.dp))

                                    // Hero
                                    Column {
                                        Row {
                                            Text(
                                                "android-",
                                                style = MaterialTheme.typography.headlineLarge,
                                            )
                                            Text(
                                                "LLM Server",
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            "Local LLM inference over your network",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Samsung One UI warning
                                    if (showSamsungWarning) {
                                        OutlinedCard(
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(modifier = Modifier.padding(16.dp)) {
                                                Icon(
                                                    Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(end = 12.dp),
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Samsung Device Detected",
                                                        style = MaterialTheme.typography.titleSmall,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        "For reliable operation on Samsung devices:",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        "1. Settings \u2192 Apps \u2192 android-llm-server \u2192 Battery \u2192 Unrestricted",
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    Text(
                                                        "2. Settings \u2192 Device care \u2192 Battery \u2192 Background usage limits \u2192 Never sleeping apps \u2192 Add this app",
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    TextButton(onClick = {
                                                        serverPreferences.samsungWarningDismissed = true
                                                        showSamsungWarning = false
                                                    }) {
                                                        Text("Dismiss")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Server Status Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            // Status indicator row
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isRunning) Color(0xFF81C995)
                                                            else Color(0xFF5F6368)
                                                        ),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    if (isRunning) "Running" else serviceStatus.replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                            }

                                            if (isRunning || serviceStatus == "loading model...") {
                                                // Server URL
                                                if (wifiIp != null) {
                                                    Text(
                                                        "http://$wifiIp:$port",
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                        ),
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                } else {
                                                    Text(
                                                        "Not connected to WiFi",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                // Model name
                                                Text(
                                                    "Model: $selectedName",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )

                                                // Bearer token (truncated + copy)
                                                if (authManager.authEnabled) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            "Token: ${token.take(16)}...",
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        IconButton(onClick = {
                                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            clipboard.setPrimaryClip(ClipData.newPlainText("Bearer Token", token))
                                                            Toast.makeText(this@MainActivity, "Token copied", Toast.LENGTH_SHORT).show()
                                                        }) {
                                                            Icon(
                                                                Icons.Default.ContentCopy,
                                                                contentDescription = "Copy token",
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Start / Stop buttons
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
                                        }
                                    }

                                    // Quick Actions
                                    Text(
                                        "Quick Actions",
                                        style = MaterialTheme.typography.titleMedium,
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        // Test API card
                                        OutlinedCard(
                                            onClick = {
                                                val svc = llmService ?: return@OutlinedCard
                                                val bridge = svc.bridge ?: return@OutlinedCard
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
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF81C995).copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = Color(0xFF81C995),
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    "Test API",
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    "Run a smoke test",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        // Server Logs card
                                        OutlinedCard(
                                            onClick = {
                                                Toast.makeText(this@MainActivity, "Coming soon", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF8AB4F8).copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.List,
                                                        contentDescription = null,
                                                        tint = Color(0xFF8AB4F8),
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    "Server Logs",
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    "View request history",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    // Chat card
                                    OutlinedCard(
                                        onClick = {
                                            if (serviceBound && llmService?.isModelLoaded == true) {
                                                currentScreen = Screen.CHAT
                                            } else {
                                                Toast.makeText(this@MainActivity, "Load a model first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFFCC934).copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Chat,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFCC934),
                                                )
                                            }
                                            Column {
                                                Text(
                                                    "Chat",
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    "Test conversations with loaded model",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    // Test output
                                    if (testOutput.isNotEmpty()) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            ),
                                        ) {
                                            Text(
                                                testOutput,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                modifier = Modifier.padding(16.dp),
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }

                        Screen.MODELS -> {
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

                        Screen.CHAT -> {
                            val modelLabel = selectedModelPath
                                ?.substringAfterLast('/')
                                ?: "Unknown model"
                            ChatScreen(
                                bridge = llmService?.bridge,
                                modelName = modelLabel,
                                onBack = { currentScreen = Screen.SERVER },
                            )
                        }

                        Screen.SETTINGS -> {
                            // Not used — settings shown as dialog
                        }
                    }

                    // Settings dialog overlay
                    if (showSettingsDialog) {
                        SettingsDialog(onDismiss = { showSettingsDialog = false })
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

@Suppress("DEPRECATION")
fun getWifiIpAddress(context: Context): String? {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ip = wifiManager.connectionInfo.ipAddress
    if (ip == 0) return null
    return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
}

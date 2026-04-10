package com.zeeshan.androidllmserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.auth.AuthManager
import com.zeeshan.androidllmserver.prefs.ServerPreferences

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { ServerPreferences(context) }
    val authManager = remember { AuthManager(context) }

    // Server settings state
    var portText by remember { mutableStateOf(prefs.httpPort.toString()) }
    var autoStart by remember { mutableStateOf(prefs.autoStartOnBoot) }

    // Auth state
    var authEnabled by remember { mutableStateOf(authManager.authEnabled) }
    var currentToken by remember { mutableStateOf(authManager.getOrCreateToken()) }
    var showRegenDialog by remember { mutableStateOf(false) }

    // Model defaults state
    var nCtxText by remember { mutableStateOf(prefs.nCtx.toString()) }
    var nThreadsText by remember { mutableStateOf(prefs.nThreads.toString()) }
    var temperatureText by remember { mutableStateOf(prefs.temperature.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // --- Server Section ---
        Text("Server", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // HTTP Port
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() } },
                        label = { Text("HTTP Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val port = portText.toIntOrNull()
                        if (port != null && port in 1..65535) {
                            prefs.httpPort = port
                            Toast.makeText(context, "Port saved. Restart server to apply.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid port (1-65535)", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Save")
                    }
                }

                val port = portText.toIntOrNull()
                if (port != null && port < 1024) {
                    Text(
                        "Warning: ports below 1024 may require root access",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                // Auto-start on boot
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Auto-start on boot", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            prefs.autoStartOnBoot = it
                        },
                    )
                }

                HorizontalDivider()

                // Bind mode (static for now)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Bind mode", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "0.0.0.0 (all interfaces)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Authentication Section ---
        Spacer(Modifier.height(4.dp))
        Text("Authentication", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Auth enabled toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Require bearer token", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = authEnabled,
                        onCheckedChange = {
                            authEnabled = it
                            authManager.authEnabled = it
                        },
                    )
                }

                HorizontalDivider()

                // Bearer token display
                Text("Bearer Token", style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        currentToken,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        copyToClipboard(context, "Bearer Token", currentToken)
                    }) {
                        Text("Copy")
                    }
                }

                // Regenerate button
                OutlinedButton(onClick = { showRegenDialog = true }) {
                    Text("Regenerate Token")
                }
            }
        }

        // --- Model Defaults Section ---
        Spacer(Modifier.height(4.dp))
        Text("Model Defaults", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Context size
                OutlinedTextField(
                    value = nCtxText,
                    onValueChange = { nCtxText = it.filter { c -> c.isDigit() } },
                    label = { Text("Context size (n_ctx): 256-8192") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Thread count
                OutlinedTextField(
                    value = nThreadsText,
                    onValueChange = { nThreadsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Thread count: 1-8") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Temperature
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { temperatureText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Temperature: 0.0-2.0") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Button(onClick = {
                    var valid = true
                    val nCtx = nCtxText.toIntOrNull()
                    val nThreads = nThreadsText.toIntOrNull()
                    val temp = temperatureText.toFloatOrNull()

                    if (nCtx == null || nCtx !in 256..8192) {
                        valid = false
                        Toast.makeText(context, "Context size must be 256-8192", Toast.LENGTH_SHORT).show()
                    } else if (nThreads == null || nThreads !in 1..8) {
                        valid = false
                        Toast.makeText(context, "Thread count must be 1-8", Toast.LENGTH_SHORT).show()
                    } else if (temp == null || temp < 0f || temp > 2f) {
                        valid = false
                        Toast.makeText(context, "Temperature must be 0.0-2.0", Toast.LENGTH_SHORT).show()
                    }

                    if (valid) {
                        prefs.nCtx = nCtx!!
                        prefs.nThreads = nThreads!!
                        prefs.temperature = temp!!
                        Toast.makeText(context, "Model defaults saved", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save Model Defaults")
                }
            }
        }

        // --- About Section ---
        Spacer(Modifier.height(4.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("android-llm-server", style = MaterialTheme.typography.bodyLarge)
                Text("Version 0.1.0", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A headless LLM inference server for Android. Runs llama.cpp locally and exposes an OpenAI-compatible HTTP API on your LAN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // Regenerate token confirmation dialog
    if (showRegenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenDialog = false },
            title = { Text("Regenerate token?") },
            text = { Text("This will invalidate all existing clients. They will need the new token to authenticate.") },
            confirmButton = {
                TextButton(onClick = {
                    currentToken = authManager.regenerateToken()
                    showRegenDialog = false
                    Toast.makeText(context, "Token regenerated", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

package com.zeeshan.androidllmserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.model.CatalogEntry
import com.zeeshan.androidllmserver.model.DownloadProgress
import com.zeeshan.androidllmserver.model.ModelDownloadManager
import com.zeeshan.androidllmserver.model.ModelFile
import com.zeeshan.androidllmserver.model.ModelRepository
import com.zeeshan.androidllmserver.model.formatBytes
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    modelRepository: ModelRepository,
    catalog: List<CatalogEntry>,
    downloadManager: ModelDownloadManager,
    activeModelPath: String?,
    onLoadModel: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var installedModels by remember { mutableStateOf(modelRepository.listModels()) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadFileName by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var deleteConfirmModel by remember { mutableStateOf<ModelFile?>(null) }
    var customUrl by remember { mutableStateOf("") }

    // Cancel download if we leave the screen
    DisposableEffect(Unit) {
        onDispose {
            if (isDownloading) downloadManager.cancel()
        }
    }

    fun refreshModels() {
        installedModels = modelRepository.listModels()
    }

    fun startDownload(url: String, fileName: String) {
        isDownloading = true
        downloadFileName = fileName
        downloadProgress = 0f
        downloadError = null
        scope.launch {
            downloadManager.download(url, fileName).collect { progress ->
                when (progress) {
                    is DownloadProgress.InProgress -> {
                        downloadProgress = progress.fraction
                        downloadedBytes = progress.downloadedBytes
                        totalBytes = progress.totalBytes
                    }
                    is DownloadProgress.Complete -> {
                        isDownloading = false
                        downloadFileName = ""
                        refreshModels()
                    }
                    is DownloadProgress.Failed -> {
                        isDownloading = false
                        downloadError = progress.error
                        downloadFileName = ""
                    }
                }
            }
        }
    }

    val installedFileNames = installedModels.map { it.name }.toSet()
    val availableCatalog = catalog.filter { it.fileName !in installedFileNames }

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
        ) {
            Text("Models", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // --- Download progress ---
        if (isDownloading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Downloading: $downloadFileName",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(downloadProgress * 100).toInt()}%  -  ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        downloadManager.cancel()
                    }) {
                        Text("Cancel")
                    }
                }
            }
        }

        // --- Download error ---
        downloadError?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Download failed: $error", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { downloadError = null }) { Text("Dismiss") }
                }
            }
        }

        // --- Installed models ---
        Text("Installed Models", style = MaterialTheme.typography.titleMedium)

        if (installedModels.isEmpty()) {
            Text(
                "No models installed. Download one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        installedModels.forEach { model ->
            val isActive = model.path == activeModelPath
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (isActive) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatBytes(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isActive) {
                        Text(
                            "Currently loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onLoadModel(model.path) },
                            enabled = !isActive,
                        ) {
                            Text(if (isActive) "Loaded" else "Load")
                        }
                        OutlinedButton(
                            onClick = { deleteConfirmModel = model },
                            enabled = !isActive,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        // --- Available models (from catalog) ---
        if (availableCatalog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Available Models", style = MaterialTheme.typography.titleMedium)

            availableCatalog.forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatBytes(entry.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { startDownload(entry.url, entry.fileName) },
                            enabled = !isDownloading,
                        ) {
                            Text("Download")
                        }
                    }
                }
            }
        }

        // --- Custom URL download ---
        Spacer(Modifier.height(8.dp))
        Text("Download from URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste a direct link to any .gguf file (e.g. from HuggingFace)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("GGUF URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                val fileName = customUrl.substringAfterLast('/').let { name ->
                    if (name.endsWith(".gguf", ignoreCase = true)) name
                    else "$name.gguf"
                }
                startDownload(customUrl.trim(), fileName)
                customUrl = ""
            },
            enabled = !isDownloading && customUrl.isNotBlank(),
        ) {
            Text("Download from URL")
        }

        // Bottom spacing
        Spacer(Modifier.height(16.dp))
    }

    // --- Delete confirmation dialog ---
    deleteConfirmModel?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteConfirmModel = null },
            title = { Text("Delete model?") },
            text = { Text("Delete ${model.name} (${formatBytes(model.sizeBytes)})? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    modelRepository.deleteModel(model.name)
                    refreshModels()
                    deleteConfirmModel = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmModel = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

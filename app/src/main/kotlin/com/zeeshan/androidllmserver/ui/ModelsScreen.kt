package com.zeeshan.androidllmserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.model.CatalogEntry
import com.zeeshan.androidllmserver.model.DownloadProgress
import com.zeeshan.androidllmserver.model.ModelDownloadManager
import com.zeeshan.androidllmserver.model.ModelFile
import com.zeeshan.androidllmserver.model.ModelRepository
import com.zeeshan.androidllmserver.model.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var showCustomUrlDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models (${installedModels.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCustomUrlDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add model from URL")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Download error banner
            downloadError?.let { error ->
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Download failed: $error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { downloadError = null }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // Empty state banner
            if (installedModels.isEmpty() && !isDownloading) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Download a model to get started",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Choose from the catalog below or tap + to add a custom URL",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Installed section
            if (installedModels.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Installed", style = MaterialTheme.typography.titleMedium)
                }

                items(installedModels, key = { it.path }) { model ->
                    val isActive = model.path == activeModelPath
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                model.name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${model.sizeBytes / 1_000_000} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isActive) {
                                    FilledTonalButton(
                                        onClick = {},
                                        enabled = false,
                                    ) {
                                        Text("Running")
                                    }
                                } else {
                                    Button(onClick = { onLoadModel(model.path) }) {
                                        Text("Load")
                                    }
                                    TextButton(
                                        onClick = { deleteConfirmModel = model },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Available section
            if (availableCatalog.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Available", style = MaterialTheme.typography.titleMedium)
                }

                items(availableCatalog, key = { it.fileName }) { entry ->
                    val isDownloadingThis = isDownloading && downloadFileName == entry.fileName
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${entry.sizeBytes / 1_000_000} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))

                            if (isDownloadingThis) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${(downloadProgress * 100).toInt()}% - ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = { downloadManager.cancel() }) {
                                        Text("Cancel")
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { startDownload(entry.url, entry.fileName) },
                                    enabled = !isDownloading,
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }

            // Downloading non-catalog item progress
            if (isDownloading && availableCatalog.none { it.fileName == downloadFileName } && installedModels.none { it.name == downloadFileName }) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${(downloadProgress * 100).toInt()}% - ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { downloadManager.cancel() }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) } // FAB clearance
        }
    }

    // Custom URL dialog
    if (showCustomUrlDialog) {
        AlertDialog(
            onDismissRequest = { showCustomUrlDialog = false },
            title = { Text("Download from URL") },
            text = {
                Column {
                    Text(
                        "Paste a direct link to any .gguf file (e.g. from HuggingFace)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("GGUF URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileName = customUrl.substringAfterLast('/').let { name ->
                            if (name.endsWith(".gguf", ignoreCase = true)) name
                            else "$name.gguf"
                        }
                        startDownload(customUrl.trim(), fileName)
                        customUrl = ""
                        showCustomUrlDialog = false
                    },
                    enabled = customUrl.isNotBlank(),
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    customUrl = ""
                    showCustomUrlDialog = false
                }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete confirmation dialog
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

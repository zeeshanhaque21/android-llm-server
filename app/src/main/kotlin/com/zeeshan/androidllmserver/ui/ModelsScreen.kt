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
import kotlinx.coroutines.flow.Flow
import com.zeeshan.androidllmserver.model.GgufFileInfo
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
    var isResolving by remember { mutableStateOf(false) }
    var resolvedFiles by remember { mutableStateOf<List<GgufFileInfo>>(emptyList()) }
    var showGgufPickerDialog by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    // Cancel download if we leave the screen
    DisposableEffect(Unit) {
        onDispose {
            if (isDownloading) downloadManager.cancel()
        }
    }

    fun refreshModels() {
        installedModels = modelRepository.listModels()
    }

    fun startDownloadFlow(fileName: String, flow: Flow<DownloadProgress>) {
        isDownloading = true
        downloadFileName = fileName
        downloadProgress = 0f
        downloadError = null
        scope.launch {
            flow.collect { progress ->
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

    fun startDownload(url: String, fileName: String) =
        startDownloadFlow(fileName, downloadManager.download(url, fileName))

    fun startDownloadEntry(entry: CatalogEntry) =
        startDownloadFlow(entry.fileName, downloadManager.downloadEntry(entry))

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val plusEnabled = remember(ctx) { com.zeeshan.androidllmserver.prefs.ServerPreferences(ctx).plusEnabled }
    val installedFileNames = installedModels.map { it.name }.toSet()
    val availableCatalog = catalog.filter { entry ->
        entry.fileName !in installedFileNames &&
        (!entry.plus || plusEnabled)
    }

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
                                    onClick = { startDownloadEntry(entry) },
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

    // Custom URL / repo name dialog
    if (showCustomUrlDialog) {
        val inputIsUrl = customUrl.trim().let {
            it.contains("huggingface.co") || it.startsWith("http://") || it.startsWith("https://")
        }

        AlertDialog(
            onDismissRequest = {
                if (!isResolving) {
                    showCustomUrlDialog = false
                    resolveError = null
                }
            },
            title = { Text("Download Model") },
            text = {
                Column {
                    Text(
                        "Paste a URL or enter a model name (e.g. google/gemma-4-31B-it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            resolveError = null
                        },
                        label = { Text("URL or model name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isResolving,
                    )
                    if (isResolving) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Searching for GGUF files...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    resolveError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = customUrl.trim()
                        if (inputIsUrl) {
                            // Direct URL download
                            val fileName = input.substringAfterLast('/').let { name ->
                                if (name.endsWith(".gguf", ignoreCase = true)) name
                                else "$name.gguf"
                            }
                            startDownload(input, fileName)
                            customUrl = ""
                            resolveError = null
                            showCustomUrlDialog = false
                        } else {
                            // Resolve repo name
                            isResolving = true
                            resolveError = null
                            scope.launch {
                                val files = downloadManager.resolveGgufFiles(input)
                                isResolving = false
                                if (files.isEmpty()) {
                                    resolveError = "No GGUF files found for \"$input\". Try bartowski/$input-GGUF or a direct URL."
                                } else if (files.size == 1) {
                                    val file = files.first()
                                    startDownload(file.downloadUrl, file.fileName)
                                    customUrl = ""
                                    resolveError = null
                                    showCustomUrlDialog = false
                                } else {
                                    resolvedFiles = files
                                    showGgufPickerDialog = true
                                    showCustomUrlDialog = false
                                }
                            }
                        }
                    },
                    enabled = customUrl.isNotBlank() && !isResolving,
                ) {
                    Text(if (inputIsUrl) "Download" else "Find GGUF")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        customUrl = ""
                        resolveError = null
                        showCustomUrlDialog = false
                    },
                    enabled = !isResolving,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // GGUF file picker dialog
    if (showGgufPickerDialog && resolvedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showGgufPickerDialog = false
                resolvedFiles = emptyList()
            },
            title = { Text("Select Quantization") },
            text = {
                Column {
                    Text(
                        "From ${resolvedFiles.first().repoName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.height(300.dp),
                    ) {
                        items(resolvedFiles, key = { it.fileName }) { file ->
                            OutlinedButton(
                                onClick = {
                                    startDownload(file.downloadUrl, file.fileName)
                                    showGgufPickerDialog = false
                                    resolvedFiles = emptyList()
                                    customUrl = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isDownloading,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    Text(
                                        file.fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (file.sizeBytes > 0) {
                                        Text(
                                            formatBytes(file.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showGgufPickerDialog = false
                    resolvedFiles = emptyList()
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

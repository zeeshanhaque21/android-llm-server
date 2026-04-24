package com.zeeshan.androidllmserver.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.audio.AudioRecorder
import com.zeeshan.androidllmserver.llm.LlmBridge
import com.zeeshan.androidllmserver.prefs.ServerPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class ChatMsg(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val thinkingContent: String = "",
    val imageBase64: String? = null,
)

private fun formatChatPrompt(messages: List<ChatMsg>, modelName: String = ""): String {
    val useGemma = modelName.contains("gemma", ignoreCase = true)
    val sb = StringBuilder()
    for (msg in messages) {
        if (useGemma) {
            // Gemma templates map "assistant" → "model" and use start/end_of_turn tokens.
            val role = if (msg.role == "assistant") "model" else msg.role
            sb.append("<start_of_turn>$role\n${msg.content}<end_of_turn>\n")
        } else {
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
    }
    sb.append(if (useGemma) "<start_of_turn>model\n" else "<|im_start|>assistant\n")
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bridge: LlmBridge?,
    sdBridge: com.zeeshan.androidllmserver.sd.SdBridge? = null,
    modelName: String,
    onBack: () -> Unit,
) {
    val isImageModel = sdBridge != null && bridge == null
    val context = LocalContext.current
    val prefs = remember { ServerPreferences(context) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showConfigDialog by remember { mutableStateOf(false) }

    // Image attachment state. attachedImageBytes holds the re-encoded JPEG
    // bytes we hand to libmtmd; attachedImageBase64 is the same bytes base64
    // so the inline thumbnail can render without decoding twice.
    var attachedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Audio attachment state. Format is sniffed from the filename extension;
    // libmtmd also sniffs the byte magic internally so "wav" as a default is
    // harmless when the ext is unknown.
    var attachedAudioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachedAudioName  by remember { mutableStateOf<String?>(null) }
    var attachedAudioFormat by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            attachedImageUri = uri
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    // Resize if too large (max 1024 px longest side) to keep
                    // the mmproj encode fast on mobile.
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val maxDim = 1024
                        val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                            android.graphics.Bitmap.createScaledBitmap(
                                bitmap,
                                (bitmap.width * scale).toInt(),
                                (bitmap.height * scale).toInt(),
                                true,
                            )
                        } else {
                            bitmap
                        }
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                        val encoded = baos.toByteArray()
                        attachedImageBytes = encoded
                        attachedImageBase64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
                    }
                }
            } catch (_: Exception) {
                attachedImageUri = null
                attachedImageBase64 = null
                attachedImageBytes = null
            }
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val displayName = uri.lastPathSegment ?: "audio"
                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (bytes != null) {
                    attachedAudioBytes  = bytes
                    attachedAudioName   = displayName
                    attachedAudioFormat = when (ext) {
                        "mp3", "wav", "flac", "ogg", "m4a" -> ext
                        else -> "wav"
                    }
                }
            } catch (_: Exception) {
                attachedAudioBytes = null
                attachedAudioName  = null
                attachedAudioFormat = null
            }
        }
    }

    // Live-recording state for the in-composer mic button. We keep a single
    // AudioRecorder tied to the composable's remembered lifetime so concurrent
    // taps can't spawn two concurrent recorders.
    val audioRecorder = remember { AudioRecorder() }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // User just said yes; start recording immediately so the tap
            // that triggered the prompt isn't wasted.
            audioRecorder.start()
            isRecordingAudio = audioRecorder.isRecording
            recordingSeconds = 0
        }
    }

    fun toggleAudioRecording() {
        if (isRecordingAudio) {
            val wav = audioRecorder.stop()
            isRecordingAudio = false
            if (wav.isNotEmpty()) {
                attachedAudioBytes  = wav
                attachedAudioName   = "recorded-${recordingSeconds}s.wav"
                attachedAudioFormat = "wav"
            }
            recordingSeconds = 0
            return
        }
        // Not recording yet — check permission, start or request.
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            )
        if (!granted) {
            recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        audioRecorder.start()
        isRecordingAudio = audioRecorder.isRecording
        recordingSeconds = 0
    }

    // Drive the MM:SS counter while recording. Auto-stops at 30 s to match
    // the upstream mtmd audio cap; past 30 s libmtmd rejects the blob.
    LaunchedEffect(isRecordingAudio) {
        while (isRecordingAudio) {
            delay(1000L)
            recordingSeconds += 1
            if (recordingSeconds >= 30) {
                // Auto-stop and attach.
                val wav = audioRecorder.stop()
                isRecordingAudio = false
                if (wav.isNotEmpty()) {
                    attachedAudioBytes  = wav
                    attachedAudioName   = "recorded-30s.wav"
                    attachedAudioFormat = "wav"
                }
                recordingSeconds = 0
            }
        }
    }

    // Cancel any in-flight recording when the screen leaves composition so
    // we don't leak a dangling MediaRecorder hold on the mic.
    DisposableEffect(Unit) {
        onDispose { audioRecorder.cancel() }
    }

    // Local config state
    var configMaxTokens by remember { mutableIntStateOf(2000) }
    var configTopK by remember { mutableIntStateOf(64) }
    var configTopP by remember { mutableFloatStateOf(0.95f) }
    var configTemperature by remember { mutableFloatStateOf(prefs.temperature) }
    var configUseGpu by remember { mutableStateOf(prefs.useGpu) }
    var configEnableThinking by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Auto-scroll only when a new message is added (not on every token)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showConfigDialog) {
        ConfigurationsDialog(
            onDismiss = { showConfigDialog = false },
            onConfirm = { maxTokens, topK, topP, temperature, useGpu, enableThinking ->
                configMaxTokens = maxTokens
                configTopK = topK
                configTopP = topP
                configTemperature = temperature
                configUseGpu = useGpu
                configEnableThinking = enableThinking
                prefs.temperature = temperature
                prefs.useGpu = useGpu
                showConfigDialog = false
            },
            initialMaxTokens = configMaxTokens,
            initialTopK = configTopK,
            initialTopP = configTopP,
            initialTemperature = configTemperature,
            initialUseGpu = configUseGpu,
            initialEnableThinking = configEnableThinking,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = primaryColor,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("AI Chat", color = primaryColor)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Configurations")
                    }
                    IconButton(
                        onClick = { messages.clear() },
                        enabled = messages.isNotEmpty() && !isGenerating,
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "New chat")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Model selector pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modelName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "AI Chat",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Chat with on-device large language models.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Message list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }

                    items(messages, key = null) { msg ->
                        MessageBubble(msg)
                    }

                    item { Spacer(Modifier.height(8.dp)) }
                }
            }

            // Generating indicator
            if (isGenerating) {
                Text(
                    "Generating...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Attachment preview (image thumb + audio chip) above input card
            if (attachedImageBase64 != null || attachedAudioBytes != null) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attachedImageBase64 != null) {
                        val previewBytes = Base64.decode(attachedImageBase64, Base64.DEFAULT)
                        val previewBitmap = BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Attached image preview",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                attachedImageUri = null
                                attachedImageBase64 = null
                                attachedImageBytes = null
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove image",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    if (attachedAudioBytes != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    attachedAudioName ?: "audio",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        attachedAudioBytes = null
                                        attachedAudioName = null
                                        attachedAudioFormat = null
                                    },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove audio",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input area
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val textColor = MaterialTheme.colorScheme.onSurface
                    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        enabled = !isGenerating,
                        textStyle = TextStyle(
                            color = textColor,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                        cursorBrush = SolidColor(primaryColor),
                        maxLines = 4,
                        decorationBox = { innerTextField ->
                            Box {
                                if (inputText.isEmpty()) {
                                    Text(
                                        "Type prompt...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = placeholderColor,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Attach image (gallery)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            IconButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add image",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Record audio (live mic). Long-press/secondary flow
                        // is intentionally omitted — users who want to attach
                        // an existing audio file can POST one to the HTTP API.
                        val audioSupported = bridge?.supportsAudio == true
                        Surface(
                            shape = CircleShape,
                            color = if (isRecordingAudio) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            IconButton(
                                onClick = { toggleAudioRecording() },
                                modifier = Modifier.size(40.dp),
                                enabled = audioSupported,
                            ) {
                                Icon(
                                    if (isRecordingAudio) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = when {
                                        !audioSupported   -> "Audio not supported by this model"
                                        isRecordingAudio  -> "Stop recording"
                                        else              -> "Record audio"
                                    },
                                    tint = if (isRecordingAudio) MaterialTheme.colorScheme.error
                                           else LocalContentColor.current,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        if (isRecordingAudio) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "●  %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // Send button
                        val hasAnyAttachment = attachedImageBytes != null || attachedAudioBytes != null
                        val canSend = !isGenerating &&
                            (inputText.isNotBlank() || hasAnyAttachment) &&
                            (bridge != null || sdBridge != null)
                        Surface(
                            shape = CircleShape,
                            color = if (canSend) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ) {
                            IconButton(
                                onClick = {
                                    val text = inputText.trim()
                                    val imageB64   = attachedImageBase64
                                    val imageBytes = attachedImageBytes
                                    val audioBytes = attachedAudioBytes
                                    val hasAttachments = imageBytes != null || audioBytes != null
                                    if (text.isEmpty() && !hasAttachments) return@IconButton
                                    if (bridge == null && sdBridge == null) return@IconButton

                                    // Stash the media bytes for the generate flow before clearing
                                    // the composer state. The prompt gets one <__media__> marker
                                    // per attached item so mtmd_tokenize interleaves the chunks.
                                    val sendImageBytes = imageBytes
                                    val sendAudioBytes = audioBytes

                                    // User-visible content for the chat bubble (no <__media__>).
                                    val userContent = buildString {
                                        if (imageB64 != null)    append("[Image attached]\n")
                                        if (audioBytes != null)  append("[Audio attached]\n")
                                        append(text)
                                    }
                                    // Content that goes into the model prompt.
                                    val promptUserContent = buildString {
                                        if (sendImageBytes != null) append("<__media__>\n")
                                        if (sendAudioBytes != null) append("<__media__>\n")
                                        append(text)
                                    }

                                    messages.add(ChatMsg(role = "user", content = userContent, imageBase64 = imageB64))
                                    inputText = ""
                                    attachedImageUri = null
                                    attachedImageBase64 = null
                                    attachedImageBytes = null
                                    attachedAudioBytes = null
                                    attachedAudioName = null
                                    attachedAudioFormat = null
                                    isGenerating = true

                                    val systemContent = if (configEnableThinking) {
                                        "You are a helpful assistant. Think step by step before answering. Wrap your reasoning in <think></think> tags."
                                    } else {
                                        "You are a helpful assistant."
                                    }
                                    // Rebuild: system + all prior chat + override the last user
                                    // message with the marker-carrying promptUserContent.
                                    val historyMinusLastUser =
                                        messages.dropLast(1).filter { it.role == "user" || it.role == "assistant" }
                                    val promptMessages = buildList {
                                        add(ChatMsg(role = "system", content = systemContent))
                                        addAll(historyMinusLastUser)
                                        add(ChatMsg(role = "user", content = promptUserContent))
                                    }
                                    val prompt = formatChatPrompt(promptMessages, modelName)

                                    // Collect media bytes in the same order as the markers above.
                                    val mediaBytes = buildList<ByteArray> {
                                        if (sendImageBytes != null) add(sendImageBytes)
                                        if (sendAudioBytes != null) add(sendAudioBytes)
                                    }

                                    // Add empty assistant message for streaming
                                    messages.add(ChatMsg(role = "assistant", content = "", isStreaming = true))
                                    val assistantIndex = messages.lastIndex

                                    scope.launch {
                                        try {
                                          if (isImageModel && sdBridge != null) {
                                            // Image generation mode
                                            messages[assistantIndex] = ChatMsg(
                                                role = "assistant",
                                                content = "Generating image...",
                                                isStreaming = true,
                                            )
                                            val b64 = sdBridge.generate(
                                                prompt = text,
                                                width = 512,
                                                height = 512,
                                                steps = 20,
                                                cfgScale = 7.0f,
                                            )
                                            if (b64.isNotEmpty()) {
                                                messages[assistantIndex] = ChatMsg(
                                                    role = "assistant",
                                                    content = "data:image/png;base64,$b64",
                                                    isStreaming = false,
                                                )
                                            } else {
                                                messages[assistantIndex] = ChatMsg(
                                                    role = "assistant",
                                                    content = "Image generation failed.",
                                                    isStreaming = false,
                                                )
                                            }
                                          } else if (bridge != null) {
                                            // LLM text generation mode
                                            // Buffer-based thinking tag detection that handles
                                            // tokens split across tag boundaries.
                                            var insideThinking = false
                                            val thinkBuf = StringBuilder()
                                            val contentBuf = StringBuilder()
                                            // Raw accumulator to detect tags that span tokens
                                            val rawBuf = StringBuilder()

                                            val tokenFlow = if (mediaBytes.isNotEmpty() && bridge.supportsMultimodal) {
                                                bridge.generateMultimodal(prompt, mediaBytes, maxTokens = configMaxTokens)
                                            } else {
                                                bridge.generate(prompt, maxTokens = configMaxTokens)
                                            }
                                            tokenFlow.collect { token ->
                                                rawBuf.append(token)
                                                val raw = rawBuf.toString()

                                                if (!insideThinking) {
                                                    // Check for partial opening tag at the end
                                                    val openTag = "<think>"
                                                    val openIdx = raw.indexOf(openTag)
                                                    if (openIdx >= 0) {
                                                        // Everything before the tag is content
                                                        contentBuf.append(raw.substring(0, openIdx))
                                                        insideThinking = true
                                                        // Everything after the tag goes to thinking
                                                        val afterTag = raw.substring(openIdx + openTag.length)
                                                        rawBuf.clear()
                                                        rawBuf.append(afterTag)
                                                        // Process any close tag already present
                                                        val closeIdx = afterTag.indexOf("</think>")
                                                        if (closeIdx >= 0) {
                                                            thinkBuf.append(afterTag.substring(0, closeIdx))
                                                            insideThinking = false
                                                            val remainder = afterTag.substring(closeIdx + "</think>".length)
                                                            rawBuf.clear()
                                                            rawBuf.append(remainder)
                                                            contentBuf.append(remainder)
                                                        }
                                                    } else if (raw.endsWith("<") ||
                                                        raw.endsWith("<t") ||
                                                        raw.endsWith("<th") ||
                                                        raw.endsWith("<thi") ||
                                                        raw.endsWith("<thin") ||
                                                        raw.endsWith("<think")
                                                    ) {
                                                        // Partial opening tag at end — wait for more tokens
                                                        return@collect
                                                    } else {
                                                        // No tag present — flush raw to content
                                                        contentBuf.append(raw)
                                                        rawBuf.clear()
                                                    }
                                                } else {
                                                    // Inside thinking — look for close tag
                                                    val closeTag = "</think>"
                                                    val closeIdx = raw.indexOf(closeTag)
                                                    if (closeIdx >= 0) {
                                                        thinkBuf.append(raw.substring(0, closeIdx))
                                                        insideThinking = false
                                                        val remainder = raw.substring(closeIdx + closeTag.length)
                                                        rawBuf.clear()
                                                        rawBuf.append(remainder)
                                                        contentBuf.append(remainder)
                                                    } else if (raw.endsWith("<") ||
                                                        raw.endsWith("</") ||
                                                        raw.endsWith("</t") ||
                                                        raw.endsWith("</th") ||
                                                        raw.endsWith("</thi") ||
                                                        raw.endsWith("</thin") ||
                                                        raw.endsWith("</think")
                                                    ) {
                                                        // Partial close tag at end — wait for more tokens
                                                        return@collect
                                                    } else {
                                                        thinkBuf.append(raw)
                                                        rawBuf.clear()
                                                    }
                                                }

                                                // Update the message in the list
                                                messages[assistantIndex] = ChatMsg(
                                                    role = "assistant",
                                                    content = contentBuf.toString(),
                                                    thinkingContent = thinkBuf.toString(),
                                                    isStreaming = true,
                                                )
                                            }
                                            // Flush any remaining raw buffer
                                            if (rawBuf.isNotEmpty()) {
                                                if (insideThinking) {
                                                    thinkBuf.append(rawBuf)
                                                } else {
                                                    contentBuf.append(rawBuf)
                                                }
                                            }
                                            // Mark streaming done
                                            messages[assistantIndex] = ChatMsg(
                                                role = "assistant",
                                                content = contentBuf.toString(),
                                                thinkingContent = thinkBuf.toString(),
                                                isStreaming = false,
                                            )
                                          } // end else if (bridge != null)
                                        } catch (e: Exception) {
                                            // Show error as system message
                                            val current = messages[assistantIndex]
                                            if (current.content.isEmpty()) {
                                                messages[assistantIndex] = current.copy(
                                                    content = "Error: ${e.message}",
                                                    isStreaming = false,
                                                )
                                            } else {
                                                messages.add(ChatMsg(role = "system", content = "Error: ${e.message}"))
                                            }
                                        } finally {
                                            isGenerating = false
                                        }
                                    }
                                },
                                enabled = canSend,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (canSend) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when {
            isUser -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        // Role label
        Text(
            text = when (msg.role) {
                "user" -> "You"
                "assistant" -> "Assistant"
                else -> "System"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        isUser -> MaterialTheme.colorScheme.primary
                        isSystem -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(12.dp),
        ) {
            Column {
                // Display attached image (user messages)
                if (msg.imageBase64 != null) {
                    val imgBytes = Base64.decode(msg.imageBase64, Base64.DEFAULT)
                    val imgBitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                    if (imgBitmap != null) {
                        Image(
                            bitmap = imgBitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // Display image from assistant (data URI in content)
                if (!isUser && !isSystem && msg.content.startsWith("data:image/")) {
                    val commaIdx = msg.content.indexOf(',')
                    if (commaIdx > 0) {
                        val b64Data = msg.content.substring(commaIdx + 1)
                        val genBytes = Base64.decode(b64Data, Base64.DEFAULT)
                        val genBitmap = BitmapFactory.decodeByteArray(genBytes, 0, genBytes.size)
                        if (genBitmap != null) {
                            Image(
                                bitmap = genBitmap.asImageBitmap(),
                                contentDescription = "Generated image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }

                // Collapsible thinking section for assistant messages
                if (!isUser && !isSystem && msg.thinkingContent.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle thinking",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (msg.isStreaming && msg.content.isEmpty()) "Thinking..." else "Thought process",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }

                    AnimatedVisibility(visible = expanded) {
                        Text(
                            text = if (msg.isStreaming) msg.thinkingContent + "\u2588" else msg.thinkingContent,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                        )
                    }
                }

                // Main content (skip if content is a data:image URI — already rendered above)
                // Strip any residual think tags that leaked through the parser
                val cleanContent = msg.content
                    .replace("<think>", "")
                    .replace("</think>", "")
                    .trimStart()
                val isImageContent = !isUser && !isSystem && cleanContent.startsWith("data:image/")
                val displayText = if (isImageContent) {
                    ""
                } else if (msg.isStreaming && cleanContent.isEmpty() && msg.thinkingContent.isEmpty()) {
                    "..."
                } else if (msg.isStreaming && cleanContent.isNotEmpty()) {
                    cleanContent + "\u2588" // block cursor
                } else {
                    cleanContent
                }

                if (displayText.isNotEmpty()) {
                    Text(
                        text = displayText,
                        color = when {
                            isUser -> MaterialTheme.colorScheme.onPrimary
                            isSystem -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationsDialog(
    onDismiss: () -> Unit,
    onConfirm: (maxTokens: Int, topK: Int, topP: Float, temperature: Float, useGpu: Boolean, enableThinking: Boolean) -> Unit,
    initialMaxTokens: Int,
    initialTopK: Int,
    initialTopP: Float,
    initialTemperature: Float,
    initialUseGpu: Boolean,
    initialEnableThinking: Boolean,
) {
    var maxTokens by remember { mutableIntStateOf(initialMaxTokens) }
    var topK by remember { mutableIntStateOf(initialTopK) }
    var topP by remember { mutableFloatStateOf(initialTopP) }
    var temperature by remember { mutableFloatStateOf(initialTemperature) }
    var useGpu by remember { mutableStateOf(initialUseGpu) }
    var enableThinking by remember { mutableStateOf(initialEnableThinking) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Configurations", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Max tokens
                SliderConfigRow(
                    label = "Max tokens",
                    value = maxTokens.toFloat(),
                    onValueChange = { maxTokens = it.roundToInt() },
                    valueRange = 256f..4096f,
                    displayValue = maxTokens.toString(),
                    onDisplayValueChange = { it.toIntOrNull()?.coerceIn(256, 4096)?.let { v -> maxTokens = v } },
                )

                // TopK
                SliderConfigRow(
                    label = "TopK",
                    value = topK.toFloat(),
                    onValueChange = { topK = it.roundToInt() },
                    valueRange = 1f..100f,
                    displayValue = topK.toString(),
                    onDisplayValueChange = { it.toIntOrNull()?.coerceIn(1, 100)?.let { v -> topK = v } },
                )

                // TopP
                SliderConfigRow(
                    label = "TopP",
                    value = topP,
                    onValueChange = { topP = (it * 100).roundToInt() / 100f },
                    valueRange = 0f..1f,
                    displayValue = "%.2f".format(topP),
                    onDisplayValueChange = { it.toFloatOrNull()?.coerceIn(0f, 1f)?.let { v -> topP = v } },
                )

                // Temperature
                SliderConfigRow(
                    label = "Temperature",
                    value = temperature,
                    onValueChange = { temperature = (it * 100).roundToInt() / 100f },
                    valueRange = 0f..2f,
                    displayValue = "%.2f".format(temperature),
                    onDisplayValueChange = { it.toFloatOrNull()?.coerceIn(0f, 2f)?.let { v -> temperature = v } },
                )

                // Accelerator
                Text(
                    "Accelerator",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = useGpu,
                        onClick = { useGpu = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            if (useGpu) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    ) {
                        Text("GPU")
                    }
                    SegmentedButton(
                        selected = !useGpu,
                        onClick = { useGpu = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            if (!useGpu) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    ) {
                        Text("CPU")
                    }
                }

                // Enable thinking
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Enable thinking",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = enableThinking,
                        onCheckedChange = { enableThinking = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(maxTokens, topK, topP, temperature, useGpu, enableThinking) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SliderConfigRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onDisplayValueChange: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (valueRange.start == valueRange.start.toInt().toFloat()) {
                    valueRange.start.toInt().toString()
                } else {
                    "%.1f".format(valueRange.start)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            OutlinedTextField(
                value = displayValue,
                onValueChange = onDisplayValueChange,
                modifier = Modifier.width(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                singleLine = true,
            )
        }
    }
}

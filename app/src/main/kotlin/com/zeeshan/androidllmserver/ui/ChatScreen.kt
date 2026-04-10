package com.zeeshan.androidllmserver.ui

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.llm.LlmBridge
import com.zeeshan.androidllmserver.prefs.ServerPreferences
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ChatMsg(val role: String, val content: String, val isStreaming: Boolean = false)

private fun formatChatPrompt(messages: List<ChatMsg>): String {
    val sb = StringBuilder()
    for (msg in messages) {
        sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
    }
    sb.append("<|im_start|>assistant\n")
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bridge: LlmBridge?,
    modelName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { ServerPreferences(context) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showConfigDialog by remember { mutableStateOf(false) }

    // Local config state
    var configMaxTokens by remember { mutableIntStateOf(2000) }
    var configTopK by remember { mutableIntStateOf(64) }
    var configTopP by remember { mutableFloatStateOf(0.95f) }
    var configTemperature by remember { mutableFloatStateOf(prefs.temperature) }
    var configUseGpu by remember { mutableStateOf(prefs.useGpu) }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showConfigDialog) {
        ConfigurationsDialog(
            onDismiss = { showConfigDialog = false },
            onConfirm = { maxTokens, topK, topP, temperature, useGpu ->
                configMaxTokens = maxTokens
                configTopK = topK
                configTopP = topP
                configTemperature = temperature
                configUseGpu = useGpu
                prefs.temperature = temperature
                prefs.useGpu = useGpu
                showConfigDialog = false
            },
            initialMaxTokens = configMaxTokens,
            initialTopK = configTopK,
            initialTopP = configTopP,
            initialTemperature = configTemperature,
            initialUseGpu = configUseGpu,
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
                        // "+" button (placeholder)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            IconButton(
                                onClick = { /* placeholder */ },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add attachment",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Send button
                        val canSend = !isGenerating && inputText.isNotBlank() && bridge != null
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
                                    if (text.isEmpty() || bridge == null) return@IconButton

                                    // Add user message
                                    messages.add(ChatMsg(role = "user", content = text))
                                    inputText = ""
                                    isGenerating = true

                                    // Build prompt from all messages, prepending a system message
                                    val promptMessages = buildList {
                                        add(ChatMsg(role = "system", content = "You are a helpful assistant."))
                                        addAll(messages.filter { it.role == "user" || it.role == "assistant" })
                                    }
                                    val prompt = formatChatPrompt(promptMessages)

                                    // Add empty assistant message for streaming
                                    messages.add(ChatMsg(role = "assistant", content = "", isStreaming = true))
                                    val assistantIndex = messages.lastIndex

                                    scope.launch {
                                        try {
                                            bridge.generate(prompt, maxTokens = configMaxTokens).collect { token ->
                                                val current = messages[assistantIndex]
                                                messages[assistantIndex] = current.copy(content = current.content + token)
                                            }
                                            // Mark streaming done
                                            val final_ = messages[assistantIndex]
                                            messages[assistantIndex] = final_.copy(isStreaming = false)
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
            val displayText = if (msg.isStreaming && msg.content.isEmpty()) {
                "..."
            } else if (msg.isStreaming) {
                msg.content + "\u2588" // block cursor
            } else {
                msg.content
            }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationsDialog(
    onDismiss: () -> Unit,
    onConfirm: (maxTokens: Int, topK: Int, topP: Float, temperature: Float, useGpu: Boolean) -> Unit,
    initialMaxTokens: Int,
    initialTopK: Int,
    initialTopP: Float,
    initialTemperature: Float,
    initialUseGpu: Boolean,
) {
    var maxTokens by remember { mutableIntStateOf(initialMaxTokens) }
    var topK by remember { mutableIntStateOf(initialTopK) }
    var topP by remember { mutableFloatStateOf(initialTopP) }
    var temperature by remember { mutableFloatStateOf(initialTemperature) }
    var useGpu by remember { mutableStateOf(initialUseGpu) }
    var enableThinking by remember { mutableStateOf(false) }

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
            TextButton(onClick = { onConfirm(maxTokens, topK, topP, temperature, useGpu) }) {
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

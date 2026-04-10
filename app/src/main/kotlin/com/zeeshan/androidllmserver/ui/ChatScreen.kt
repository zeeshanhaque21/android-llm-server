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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.llm.LlmBridge
import kotlinx.coroutines.launch

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
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat")
                        Text(
                            modelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { messages.clear() },
                        enabled = messages.isNotEmpty() && !isGenerating,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    maxLines = 4,
                    enabled = !isGenerating,
                )
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
                                bridge.generate(prompt, maxTokens = 512).collect { token ->
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
                    enabled = !isGenerating && inputText.isNotBlank() && bridge != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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

package com.zeeshan.androidllmserver

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeeshan.androidllmserver.llm.LlmBridge
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

// Phase 1 smoke test. The model lives in the app's private external
// files dir, which does NOT require any runtime permission (scoped
// storage). Push it with:
//   adb push qwen2.5-1.5b-instruct-q4_k_m.gguf \
//     /sdcard/Android/data/com.zeeshan.androidllmserver/files/
private const val SMOKE_MODEL_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
private const val SMOKE_PROMPT =
    "<|im_start|>user\nSay hello in one short sentence.<|im_end|>\n<|im_start|>assistant\n"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LlmBridge.ensureNativeLoaded()

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    val scope = rememberCoroutineScope()
                    val bridge = remember { LlmBridge() }
                    var status by remember { mutableStateOf("idle") }
                    var output by remember { mutableStateOf("") }
                    val smokeModel = remember {
                        File(getExternalFilesDir(null), SMOKE_MODEL_NAME)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("android-llm-server", style = MaterialTheme.typography.titleLarge)
                        Text("phase 1 smoke test", style = MaterialTheme.typography.bodySmall)
                        Text("model: ${smokeModel.absolutePath}")
                        Text("status: $status")

                        Button(onClick = {
                            scope.launch {
                                output = ""
                                status = "loading model…"
                                runCatching {
                                    bridge.load(smokeModel.absolutePath)
                                    status = "generating…"
                                    bridge.generate(SMOKE_PROMPT, maxTokens = 128).collect { tok ->
                                        output += tok
                                        Log.i(TAG, "tok=$tok")
                                    }
                                    status = "done"
                                }.onFailure {
                                    Log.e(TAG, "smoke test failed", it)
                                    status = "error: ${it.message}"
                                }
                            }
                        }) { Text("Run smoke test") }

                        if (output.isNotEmpty()) {
                            Text(output, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

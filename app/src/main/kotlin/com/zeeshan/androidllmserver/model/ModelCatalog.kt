package com.zeeshan.androidllmserver.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogEntry(
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
    val type: String = "llm",   // "llm" or "image"
    val plus: Boolean = false,  // hidden unless Plus enabled in settings
    // Optional multimodal projector sidecar (vision / audio encoder).
    // When set, the downloader fetches it next to the main model as
    // "<fileName>.mmproj.gguf" and the inference layer loads it to enable
    // image and/or audio inputs.
    val mmprojUrl: String? = null,
    val mmprojSizeBytes: Long = 0L,
)

class ModelCatalog(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadCatalog(): List<CatalogEntry> {
        val text = context.assets.open("model_catalog.json")
            .bufferedReader()
            .use { it.readText() }
        return json.decodeFromString<List<CatalogEntry>>(text)
    }
}

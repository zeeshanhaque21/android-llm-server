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

package com.zeeshan.androidllmserver.model

import android.content.Context
import java.io.File

data class ModelFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
)

class ModelRepository(private val context: Context) {

    fun getModelsDir(): File = context.getExternalFilesDir(null)
        ?: throw IllegalStateException("External files dir unavailable")

    /** List all .gguf files in the models directory. */
    fun listModels(): List<ModelFile> {
        val dir = getModelsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            ?.map { ModelFile(name = it.name, path = it.absolutePath, sizeBytes = it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** Delete a model file. Returns true if the file was deleted. */
    fun deleteModel(fileName: String): Boolean {
        val file = File(getModelsDir(), fileName)
        // Safety: only delete .gguf files inside our directory
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        if (!file.canonicalPath.startsWith(getModelsDir().canonicalPath)) return false
        return file.delete()
    }
}

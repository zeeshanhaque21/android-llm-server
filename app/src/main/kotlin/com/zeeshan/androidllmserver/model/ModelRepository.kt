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

    private val modelExtensions = setOf("gguf", "safetensors", "ckpt")

    /**
     * Sidecar multimodal projector suffix. Co-located with the main GGUF as
     * "<modelFileName>.mmproj.gguf" so multiple models can coexist without
     * their mmproj files clashing.
     */
    private val mmprojSuffix = ".mmproj.gguf"

    /** List all model files (.gguf, .safetensors, .ckpt) in the models directory. */
    fun listModels(): List<ModelFile> {
        val dir = getModelsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in modelExtensions }
            // Hide mmproj sidecars — they're not selectable on their own.
            ?.filterNot { it.name.endsWith(mmprojSuffix) }
            ?.map { ModelFile(name = it.name, path = it.absolutePath, sizeBytes = it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Locate the mmproj sidecar for a given main model file, if one was
     * downloaded alongside it. Returns null when the model has no sidecar.
     */
    fun findMmprojFor(modelFileName: String): File? {
        val f = File(getModelsDir(), modelFileName + mmprojSuffix)
        return if (f.exists() && f.isFile) f else null
    }

    /** Filename convention for the mmproj sidecar associated with a main model. */
    fun mmprojFileNameFor(modelFileName: String): String = modelFileName + mmprojSuffix

    /**
     * Delete a model file and its mmproj sidecar (if present).
     * Returns true if the main file was deleted.
     */
    fun deleteModel(fileName: String): Boolean {
        val file = File(getModelsDir(), fileName)
        if (file.extension.lowercase() !in modelExtensions) return false
        if (!file.canonicalPath.startsWith(getModelsDir().canonicalPath)) return false
        // Best-effort: drop the sidecar too, if any.
        findMmprojFor(fileName)?.delete()
        return file.delete()
    }
}

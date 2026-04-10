package com.zeeshan.androidllmserver.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

sealed class DownloadProgress {
    data class InProgress(
        val fraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadProgress()

    data class Complete(val file: File) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}

class ModelDownloadManager(private val context: Context) {

    private val cancelled = AtomicBoolean(false)

    /**
     * Download a model to getExternalFilesDir, emitting progress.
     * Supports resume via HTTP Range header if a partial file exists.
     */
    fun download(url: String, fileName: String): Flow<DownloadProgress> = flow {
        cancelled.set(false)

        val modelsDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir unavailable")
        val targetFile = File(modelsDir, fileName)
        val partialFile = File(modelsDir, "$fileName.part")

        var connection: HttpURLConnection? = null
        try {
            val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val responseCode = connection.responseCode
            val isResume = responseCode == 206
            // contentLength can be -1 for large files; parse Content-Length header as Long
            val contentLengthHeader = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val totalBytes = if (isResume) {
                existingBytes + contentLengthHeader
            } else {
                // If server doesn't support range, start over
                if (existingBytes > 0 && responseCode == 200) {
                    partialFile.delete()
                }
                contentLengthHeader
            }

            var downloadedBytes = if (isResume) existingBytes else 0L

            emit(DownloadProgress.InProgress(
                fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            ))

            val outputStream = if (isResume) {
                // Append to existing partial file
                java.io.FileOutputStream(partialFile, true)
            } else {
                java.io.FileOutputStream(partialFile)
            }

            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                outputStream.buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastEmitTime = System.currentTimeMillis()

                    while (true) {
                        if (cancelled.get()) {
                            emit(DownloadProgress.Failed("Download cancelled"))
                            return@flow
                        }

                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Emit progress at most every 250ms to avoid flooding the UI
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 250) {
                            lastEmitTime = now
                            emit(DownloadProgress.InProgress(
                                fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                            ))
                        }
                    }
                }
            }

            // Rename .part to final file
            if (targetFile.exists()) targetFile.delete()
            partialFile.renameTo(targetFile)
            emit(DownloadProgress.Complete(targetFile))
        } catch (e: Exception) {
            emit(DownloadProgress.Failed(e.message ?: "Unknown download error"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Cancel an in-progress download. */
    fun cancel() {
        cancelled.set(true)
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}

/** Helper to format bytes as a human-readable string. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

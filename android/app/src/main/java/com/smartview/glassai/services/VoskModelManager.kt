package com.smartview.glassai.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small, open-source Vosk speech model used for
 * offline wake word detection. Unlike Picovoice, Vosk requires no account or
 * API key - the model is fetched once from Alphacephei's public model archive
 * and cached in app-private storage.
 */
object VoskModelManager {

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    fun getModelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(context: Context): Boolean {
        val modelDir = getModelDir(context)
        return File(modelDir, "conf").isDirectory
    }

    /**
     * Downloads the model zip and extracts it into app-private storage.
     * [onProgress] is called with a 0-100 percentage on the calling thread's
     * dispatcher context switched to IO, so callers should hop back to the
     * main thread themselves when updating UI.
     */
    suspend fun downloadAndUnpackModel(
        context: Context,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val zipFile = File(context.cacheDir, "$MODEL_DIR_NAME.zip")
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
                val total = body.contentLength()
                var readBytes = 0L
                zipFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            out.write(buffer, 0, n)
                            readBytes += n
                            if (total > 0) {
                                onProgress(((readBytes * 100) / total).toInt())
                            }
                        }
                    }
                }
            }

            unzip(zipFile, context.filesDir)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            zipFile.delete()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private const val DEFAULT_BUFFER_SIZE = 8192
}

package com.smartview.glassai.services

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Bounded, cancellable download and atomic installation. Never trust a partial model. */
object VoskModelInstaller {
    private const val modelName = "vosk-model-small-en-us-0.15"
    private const val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val expectedSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private val installMutex = Mutex()
    private val required = listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf",
        "graph/HCLr.fst", "graph/Gr.fst", "graph/phones/word_boundary.int")
    private fun valid(dir: File) = required.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } } &&
        File(dir, ".verified").let { it.isFile && it.readText() == expectedSha256 }

    suspend fun ensureInstalled(context: Context): File = installMutex.withLock {
        val target = File(context.filesDir, modelName)
        if (valid(target)) return@withLock target
        val archive = File(context.cacheDir, "$modelName.zip.part")
        val staging = File(context.filesDir, "$modelName-installing")
        val coroutine = currentCoroutineContext()
        val connection = URL(modelUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        try {
            coroutine.ensureActive()
            check(connection.responseCode == 200) { "Model download failed (HTTP ${connection.responseCode})" }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 80L * 1024 * 1024) { "Model download exceeds expected size" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256) {
                "Model checksum mismatch; enable again to retry"
            }
            coroutine.ensureActive()
            staging.deleteRecursively()
            var unpacked = 0L
            ZipInputStream(archive.inputStream()).use { zip ->
                val buffer = ByteArray(32768)
                while (true) {
                    coroutine.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val output = File(staging, entry.name)
                    check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid archive path" }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use { stream ->
                            while (true) {
                                coroutine.ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                unpacked += count
                                check(unpacked <= 200L * 1024 * 1024) { "Model archive exceeds size limit" }
                                stream.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val extracted = File(staging, modelName)
            File(extracted, ".verified").writeText(expectedSha256)
            check(valid(extracted)) { "Incomplete model archive" }
            coroutine.ensureActive()
            target.deleteRecursively()
            check(extracted.renameTo(target)) { "Could not install the offline model" }
            target
        } finally {
            connection.disconnect()
            archive.delete()
            staging.deleteRecursively()
        }
    }
}

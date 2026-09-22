package com.smartview.glassai.services

import android.content.Context
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Downloads the Apache-2.0 Vosk small English model once, then keeps all recognition offline. */
object VoskModelInstaller {
    private const val modelName = "vosk-model-small-en-us-0.15"
    private const val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val expectedSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"

    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, modelName)
        if (File(target, "am/final.mdl").isFile) return target
        val archive = File(context.cacheDir, "$modelName.zip")
        URL(modelUrl).openStream().use { input -> archive.outputStream().use(input::copyTo) }
        check(archive.inputStream().use(::sha256) == expectedSha256) { "Vosk model checksum did not match" }
        val staging = File(context.filesDir, "$modelName-installing")
        staging.deleteRecursively()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(staging, entry.name)
                check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid model archive path" }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        val extracted = File(staging, modelName)
        check(File(extracted, "am/final.mdl").isFile) { "Vosk model archive is incomplete" }
        target.deleteRecursively()
        check(extracted.renameTo(target)) { "Could not install Vosk model" }
        staging.deleteRecursively()
        archive.delete()
        return target
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

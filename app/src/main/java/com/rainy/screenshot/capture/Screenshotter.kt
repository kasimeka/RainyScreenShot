package com.rainy.screenshot.capture

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class Screenshot(val uri: Uri, val png: ByteArray)

/**
 * Reads PNG bytes from `screencap -p` over Shizuku and saves them to
 * DCIM/Screenshots through MediaStore, using the same naming as the system screenshots.
 */
object Screenshotter {

    private const val RELATIVE_DIR = "DCIM/Screenshots"
    private const val MIME_PNG = "image/png"

    private const val SCREENCAP_TIMEOUT_MS = 10_000L

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    suspend fun capture(context: Context): Screenshot? = withContext(Dispatchers.IO) {
        val png = readScreencap() ?: return@withContext null
        val uri = saveToGallery(context, png) ?: return@withContext null
        Screenshot(uri, png)
    }

    private fun readScreencap(): ByteArray? {
        if (!Shizuku.pingBinder()) return null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
        val process = RemoteProcessAdapter.create(arrayOf("sh", "-c", "screencap -p 2>/dev/null"))
        return try {
            val output = ByteArrayOutputStream()
            val reader = Thread {
                runCatching { process.inputStream.use { it.copyTo(output) } }
            }.apply { isDaemon = true; start() }
            reader.join(SCREENCAP_TIMEOUT_MS)
            if (reader.isAlive) return null
            output.toByteArray().takeIf { isPng(it) }
        } finally {
            process.destroy()
        }
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }

    private fun saveToGallery(context: Context, png: ByteArray): Uri? {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(Date(now))
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending) ?: return null

        // Remove the row if writing or publishing fails, so no hidden pending entry is left behind.
        val written = runCatching {
            val stream = resolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { it.write(png) }
            true
        }.getOrDefault(false)
        val published = written && runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            ) > 0
        }.getOrDefault(false)

        if (!published) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }
}

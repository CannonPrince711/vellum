package app.vellum.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max

object FileUtils {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document.pdf"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun copyUriToFile(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't read file")
        input.use { i -> dest.outputStream().use { i.copyTo(it) } }
    }

    fun openOutput(ctx: Context, uri: Uri): OutputStream {
        val cr = ctx.contentResolver
        return try {
            cr.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        } ?: cr.openOutputStream(uri, "w") ?: throw IOException("Can't write to destination")
    }

    fun workDir(ctx: Context) = File(ctx.cacheDir, "work").apply { mkdirs() }
    fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    fun shareFile(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun decodeSampled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val s = max(w, h)
                if (s > maxDim) {
                    val f = maxDim.toFloat() / s
                    decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun baseName(name: String) = name.substringBeforeLast('.', name).ifBlank { "document" }
    fun ensurePdf(name: String) = if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"
}

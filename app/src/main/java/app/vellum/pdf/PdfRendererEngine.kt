package app.vellum.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

data class PageSize(val w: Int, val h: Int)

/** Fast page rasterizer built on the platform PdfRenderer (PDFium). Thread-safe via a mutex. */
class PdfRendererEngine private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    companion object {
        fun open(file: File): PdfRendererEngine {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return PdfRendererEngine(pfd, PdfRenderer(pfd))
            } catch (e: Exception) {
                pfd.close(); throw e
            }
        }

        fun canOpen(file: File): Boolean = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { p ->
                PdfRenderer(p).use { it.pageCount >= 0 }
            }
        } catch (_: Exception) {
            false
        }

        private const val MAX_PIXELS = 8_000_000L
    }

    val pageCount: Int = renderer.pageCount
    val pageSizes: List<PageSize> = List(pageCount) { i ->
        renderer.openPage(i).use { PageSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
    }

    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        if (index !in 0 until pageCount) return null
        val size = pageSizes[index]
        var w = widthPx.coerceIn(64, 4096)
        var h = (w.toLong() * size.h / size.w).toInt().coerceAtLeast(1)
        if (w.toLong() * h > MAX_PIXELS) {
            val f = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))
            w = (w * f).toInt(); h = (h * f).toInt().coerceAtLeast(1)
        }
        val key = "$index@$w"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                renderer.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                cache.put(key, bmp)
                bmp
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            if (closed) return
            closed = true
            cache.evictAll()
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}

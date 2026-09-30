package app.vellum.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class PageText(val text: String, val boxes: List<RectF?>)

/** Lazily extracts text + character boxes per page (normalised to the displayed page). */
class TextIndex(file: File) : Closeable {
    private val doc = PDDocument.load(file)
    private val cache = HashMap<Int, PageText>()

    @Synchronized
    fun page(i: Int): PageText = cache.getOrPut(i) { extract(i) }

    private fun extract(i: Int): PageText {
        val g = PdfOps.PageGeom(doc.getPage(i))
        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        fun sep() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') { sb.append(' '); boxes.add(null) }
        }
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                textPositions?.forEach { tp ->
                    val u = tp.unicode ?: return@forEach
                    val hgt = max(tp.height, tp.fontSizeInPt * 0.7f)
                    val r = RectF(
                        tp.x / g.wd, (tp.y - hgt) / g.hd,
                        (tp.x + tp.width) / g.wd, (tp.y + hgt * 0.22f) / g.hd
                    )
                    for (ch in u) { sb.append(ch); boxes.add(r) }
                }
            }
            override fun writeWordSeparator() = sep()
            override fun writeLineSeparator() = sep()
        }
        stripper.sortByPosition = true
        stripper.startPage = i + 1
        stripper.endPage = i + 1
        stripper.getText(doc)
        return PageText(sb.toString(), boxes)
    }

    override fun close() = doc.close()

    companion object {
        /** Merge per-character boxes into one rectangle per text line. */
        fun mergeRects(chars: List<RectF>): List<RectF> {
            val out = ArrayList<RectF>()
            for (r in chars) {
                val last = out.lastOrNull()
                if (last != null && abs(last.top - r.top) < last.height() * 0.5f) last.union(r) else out.add(RectF(r))
            }
            return out
        }
    }
}

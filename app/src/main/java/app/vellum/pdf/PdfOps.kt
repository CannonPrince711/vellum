package app.vellum.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import app.vellum.util.FileUtils
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class FieldType { TEXT, CHECKBOX, CHOICE, RADIO }
data class FormField(val name: String, val type: FieldType, val value: String, val options: List<String>, val readOnly: Boolean)
data class OutlineEntry(val title: String, val page: Int, val level: Int)
data class DocInfo(
    val title: String, val author: String, val subject: String, val keywords: String,
    val creator: String, val producer: String, val pages: Int, val version: Float, val fileSize: Long
)

/** All document-modifying operations, built on PdfBox-Android. Every function is blocking – call from IO. */
object PdfOps {

    /** Maps "display space" (top-left origin, page rotation applied, in points) to PDF user space. */
    class PageGeom(page: PDPage) {
        private val crop: PDRectangle = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        private val cw = crop.width
        private val ch = crop.height
        val wd = if (rot % 180 == 0) cw else ch
        val hd = if (rot % 180 == 0) ch else cw

        fun displayToUser(): Matrix {
            val llx = crop.lowerLeftX; val lly = crop.lowerLeftY; val ury = crop.upperRightY
            return when (rot) {
                90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
                180 -> Matrix(-1f, 0f, 0f, 1f, llx + cw, lly)
                270 -> Matrix(0f, -1f, -1f, 0f, llx + cw, ury)
                else -> Matrix(1f, 0f, 0f, -1f, llx, ury)
            }
        }
    }

    // ---------- helpers ----------

    fun <T> edit(file: File, block: (PDDocument) -> T): T {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val result = PDDocument.load(file).use { doc ->
            val r = block(doc)
            doc.save(tmp)
            r
        }
        replace(tmp, file)
        return result
    }

    fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true); tmp.delete()
        }
    }

    private val FONT_PATHS = listOf(
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    )

    private fun loadFont(doc: PDDocument): PDFont {
        for (p in FONT_PATHS) {
            val f = File(p)
            if (f.exists()) try {
                return PDType0Font.load(doc, f)
            } catch (_: Throwable) {
            }
        }
        return PDType1Font.HELVETICA
    }

    private fun safeText(font: PDFont, raw: String): String {
        val s = raw.replace("\t", "    ")
        return try {
            font.encode(s); s
        } catch (_: Exception) {
            buildString {
                for (ch in s) {
                    val c = ch.toString()
                    append(try { font.encode(c); c } catch (_: Exception) { "?" })
                }
            }
        }
    }

    private fun widthOf(font: PDFont, s: String, size: Float): Float =
        try { font.getStringWidth(s) / 1000f * size } catch (_: Exception) { s.length * size * 0.55f }

    private fun PDPageContentStream.strokeColor(c: Int) =
        setStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    private fun PDPageContentStream.fillColor(c: Int) =
        setNonStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    /** Keeps inherited attributes when a page is moved or copied. */
    private fun materialize(page: PDPage) {
        page.mediaBox = page.mediaBox
        page.cropBox = page.cropBox
        page.resources = page.resources
        page.rotation = page.rotation
    }

    // ---------- open / security ----------

    fun decryptOrRepair(src: File, password: String?, dest: File) {
        PDDocument.load(src, password ?: "").use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            doc.save(dest)
        }
    }

    fun protectCopy(file: File, userPw: String, ownerPw: String, allowPrint: Boolean, allowCopy: Boolean, out: OutputStream) {
        PDDocument.load(file).use { doc ->
            val ap = AccessPermission()
            ap.setCanPrint(allowPrint)
            ap.setCanExtractContent(allowCopy)
            val policy = StandardProtectionPolicy(ownerPw.ifBlank { UUID.randomUUID().toString() }, userPw, ap)
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
    }

    // ---------- markup ----------

    fun applyAnnotations(file: File, annots: List<Annot>) {
        if (annots.isEmpty()) return
        edit(file) { doc ->
            var font: PDFont? = null
            annots.groupBy { it.page }.forEach { (pi, list) ->
                if (pi !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(pi)
                val g = PageGeom(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(g.displayToUser())
                    for (a in list) {
                        cs.saveGraphicsState()
                        when (a) {
                            is InkAnnot -> drawInk(cs, a, g)
                            is ShapeAnnot -> drawShape(cs, a, g)
                            is TextAnnot -> {
                                val f = font ?: loadFont(doc).also { font = it }
                                drawText(cs, a, g, f)
                            }
                            is ImageAnnot -> {
                                val img = LosslessFactory.createFromImage(doc, a.bitmap)
                                val x = a.pos.x * g.wd; val y = a.pos.y * g.hd
                                val w = a.w * g.wd; val h = a.h * g.hd
                                cs.transform(Matrix(w, 0f, 0f, -h, x, y + h))
                                cs.drawImage(img, 0f, 0f, 1f, 1f)
                            }
                        }
                        cs.restoreGraphicsState()
                    }
                    cs.restoreGraphicsState()
                }
            }
        }
    }

    private fun drawInk(cs: PDPageContentStream, a: InkAnnot, g: PageGeom) {
        if (a.points.isEmpty()) return
        cs.strokeColor(a.color)
        cs.setLineWidth(a.width * g.wd)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        if (a.highlighter) {
            val gs = PDExtendedGraphicsState()
            gs.setStrokingAlphaConstant(0.35f)
            cs.setGraphicsStateParameters(gs)
        }
        val p0 = a.points[0]
        cs.moveTo(p0.x * g.wd, p0.y * g.hd)
        if (a.points.size == 1) cs.lineTo(p0.x * g.wd + 0.01f, p0.y * g.hd)
        for (i in 1 until a.points.size) cs.lineTo(a.points[i].x * g.wd, a.points[i].y * g.hd)
        cs.stroke()
    }

    private fun drawShape(cs: PDPageContentStream, a: ShapeAnnot, g: PageGeom) {
        cs.strokeColor(a.color)
        val lw = a.width * g.wd
        cs.setLineWidth(lw)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        val x1 = a.start.x * g.wd; val y1 = a.start.y * g.hd
        val x2 = a.end.x * g.wd; val y2 = a.end.y * g.hd
        when (a.kind) {
            ShapeKind.RECT -> cs.addRect(min(x1, x2), min(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
            ShapeKind.ELLIPSE -> {
                val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
                val rx = kotlin.math.abs(x2 - x1) / 2; val ry = kotlin.math.abs(y2 - y1) / 2
                val k = 0.5523f
                cs.moveTo(cx + rx, cy)
                cs.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
                cs.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
                cs.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
                cs.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
                cs.closePath()
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                cs.moveTo(x1, y1); cs.lineTo(x2, y2)
                if (a.kind == ShapeKind.ARROW) {
                    val len = max(lw * 4f, g.wd * 0.03f)
                    val ang = atan2(y2 - y1, x2 - x1)
                    val t = AnnotGeometry.ARROW_ANGLE
                    cs.moveTo(x2 - len * cos(ang - t), y2 - len * sin(ang - t)); cs.lineTo(x2, y2)
                    cs.lineTo(x2 - len * cos(ang + t), y2 - len * sin(ang + t))
                }
            }
        }
        cs.stroke()
    }

    private fun drawText(cs: PDPageContentStream, a: TextAnnot, g: PageGeom, font: PDFont) {
        val size = a.size * g.wd
        val x = a.pos.x * g.wd; val y = a.pos.y * g.hd
        cs.fillColor(a.color)
        cs.beginText()
        cs.setFont(font, size)
        a.text.split('\n').forEachIndexed { i, line ->
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, y + size * 0.9f + i * size * 1.2f))
            cs.showText(safeText(font, line))
        }
        cs.endText()
    }

    // ---------- page management ----------

    fun rotatePages(file: File, pages: List<Int>, delta: Int) = edit(file) { doc ->
        pages.forEach { i ->
            val p = doc.getPage(i)
            p.rotation = (((p.rotation + delta) % 360) + 360) % 360
        }
    }

    fun deletePages(file: File, pages: List<Int>) = edit(file) { doc ->
        pages.distinct().sortedDescending().forEach { doc.removePage(it) }
    }

    fun movePage(file: File, from: Int, to: Int) = edit(file) { doc ->
        val tree = doc.pages
        val page = tree.get(from)
        materialize(page)
        tree.remove(from)
        if (to >= tree.count) tree.add(page) else tree.insertBefore(page, tree.get(to))
    }

    fun insertBlankAfter(file: File, index: Int) = edit(file) { doc ->
        val ref = doc.getPage(index.coerceIn(0, doc.numberOfPages - 1))
        val box = ref.mediaBox
        val blank = PDPage(PDRectangle(box.width, box.height))
        blank.rotation = ref.rotation
        if (index >= doc.numberOfPages - 1) doc.addPage(blank) else doc.pages.insertAfter(blank, doc.getPage(index))
    }

    fun extractPages(file: File, pages: List<Int>, out: OutputStream) {
        PDDocument.load(file).use { src ->
            PDDocument().use { dest ->
                pages.sorted().forEach { i ->
                    val p = src.getPage(i)
                    materialize(p)
                    dest.importPage(p)
                }
                dest.save(out)
            }
        }
    }

    fun addPageNumbers(file: File) = edit(file) { doc ->
        val font = loadFont(doc)
        val n = doc.numberOfPages
        for (i in 0 until n) {
            val page = doc.getPage(i)
            val g = PageGeom(page)
            val text = "${i + 1} / $n"
            val size = (g.wd / 55f).coerceIn(8f, 14f)
            val w = widthOf(font, text, size)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.transform(g.displayToUser())
                cs.fillColor(0xFF555555.toInt())
                cs.beginText()
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, (g.wd - w) / 2f, g.hd - size * 1.8f))
                cs.showText(text)
                cs.endText()
            }
        }
    }

    fun watermark(file: File, text: String, color: Int, opacity: Float, scale: Float) = edit(file) { doc ->
        val font = loadFont(doc)
        val t = safeText(font, text)
        val gs = PDExtendedGraphicsState()
        gs.setNonStrokingAlphaConstant(opacity)
        for (page in doc.pages) {
            val g = PageGeom(page)
            val diag = hypot(g.wd, g.hd)
            val unit = widthOf(font, t, 1f).coerceAtLeast(0.1f)
            val size = min(diag * 0.7f / unit, g.hd * 0.25f) * scale
            val tw = unit * size
            val cap = size * 0.7f
            val ang = atan2(g.hd, g.wd)
            val c = cos(ang); val s = sin(ang)
            val x0 = g.wd / 2 - c * tw / 2 + s * cap / 2
            val y0 = g.hd / 2 + s * tw / 2 + c * cap / 2
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.transform(g.displayToUser())
                cs.setGraphicsStateParameters(gs)
                cs.fillColor(color)
                cs.beginText()
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix(c, -s, -s, -c, x0, y0))
                cs.showText(t)
                cs.endText()
            }
        }
    }

    // ---------- combine / create ----------

    private fun mergeStreams(sources: List<InputStream>, out: File) {
        FileOutputStream(out).use { fos ->
            val m = PDFMergerUtility()
            sources.forEach { m.addSource(it) }
            m.destinationStream = fos
            m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
        }
    }

    fun merge(ctx: Context, uris: List<Uri>, out: File) {
        val streams = uris.map { ctx.contentResolver.openInputStream(it) ?: throw IOException("Can't read a selected file") }
        try { mergeStreams(streams, out) } finally { streams.forEach { runCatching { it.close() } } }
    }

    fun appendPdf(ctx: Context, file: File, uri: Uri) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val extra = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't read file")
        val base = file.inputStream()
        try { mergeStreams(listOf(base, extra), tmp) } finally { base.close(); extra.close() }
        replace(tmp, file)
    }

    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: File) {
        PDDocument().use { doc ->
            for (u in uris) {
                val bmp = FileUtils.decodeSampled(ctx, u, 2200) ?: continue
                val w = 595f
                val h = w * bmp.height / bmp.width
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp)
                else JPEGFactory.createFromImage(doc, bmp, 0.88f)
                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w, h) }
            }
            if (doc.numberOfPages == 0) throw IOException("No readable images")
            doc.save(out)
        }
    }

    // ---------- forms ----------

    fun readForm(file: File): List<FormField> = PDDocument.load(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return@use emptyList()
        form.fieldTree.mapNotNull { f ->
            val name = f.fullyQualifiedName ?: return@mapNotNull null
            when (f) {
                is PDCheckBox -> FormField(name, FieldType.CHECKBOX, if (f.isChecked) "true" else "false", emptyList(), f.isReadOnly)
                is PDRadioButton -> FormField(name, FieldType.RADIO, f.value ?: "", f.onValues.toList(), f.isReadOnly)
                is PDTextField -> FormField(name, FieldType.TEXT, f.value ?: "", emptyList(), f.isReadOnly)
                is PDChoice -> FormField(name, FieldType.CHOICE, f.value.firstOrNull() ?: "", f.options, f.isReadOnly)
                else -> null
            }
        }
    }

    fun writeForm(file: File, values: Map<String, String>, flatten: Boolean) = edit(file) { doc ->
        val form = doc.documentCatalog.acroForm ?: return@edit
        for ((name, v) in values) {
            val f = form.getField(name) ?: continue
            try {
                when (f) {
                    is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                    is PDRadioButton -> if (v.isNotEmpty()) f.setValue(v)
                    is PDTextField -> f.setValue(v)
                    is PDChoice -> if (v.isNotEmpty()) f.setValue(v)
                }
            } catch (_: Exception) {
            }
        }
        if (flatten) form.flatten()
        Unit
    }

    // ---------- info / outline ----------

    fun readInfo(file: File): DocInfo = PDDocument.load(file).use { d ->
        val i = d.documentInformation
        DocInfo(
            i.title ?: "", i.author ?: "", i.subject ?: "", i.keywords ?: "",
            i.creator ?: "", i.producer ?: "", d.numberOfPages, d.version, file.length()
        )
    }

    fun writeInfo(file: File, info: DocInfo) = edit(file) { d ->
        val i = d.documentInformation
        i.title = info.title.ifBlank { null }
        i.author = info.author.ifBlank { null }
        i.subject = info.subject.ifBlank { null }
        i.keywords = info.keywords.ifBlank { null }
        d.documentInformation = i
    }

    fun outline(file: File): List<OutlineEntry> = PDDocument.load(file).use { doc ->
        val res = ArrayList<OutlineEntry>()
        val root = doc.documentCatalog.documentOutline ?: return@use res
        fun walk(node: PDOutlineNode, level: Int) {
            for (item in node.children()) {
                if (res.size > 2000) return
                val page = try {
                    item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } ?: -1
                } catch (_: Exception) { -1 }
                res.add(OutlineEntry(item.title ?: "Untitled", page, level))
                if (level < 8) walk(item, level + 1)
            }
        }
        walk(root, 0)
        res
    }
}

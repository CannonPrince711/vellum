package app.vellum.ui.viewer

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.print.PrintManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.data.RecentFiles
import app.vellum.pdf.Annot
import app.vellum.pdf.DocInfo
import app.vellum.pdf.FormField
import app.vellum.pdf.ImageAnnot
import app.vellum.pdf.OutlineEntry
import app.vellum.pdf.PdfOps
import app.vellum.pdf.PdfRendererEngine
import app.vellum.pdf.ShapeKind
import app.vellum.pdf.TextAnnot
import app.vellum.pdf.TextIndex
import app.vellum.pdf.translated
import app.vellum.util.FileUtils
import app.vellum.util.PdfPrintAdapter
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

enum class Tool { NONE, MOVE, PEN, HIGHLIGHTER, SHAPE, TEXT, SIGNATURE, ERASER }

val INK_PALETTE = listOf(
    0xFF1C2533.toInt(), // ink
    0xFFD9480F.toInt(), // vermilion
    0xFF1F5FBF.toInt(), // cobalt
    0xFF3F6B4F.toInt(), // moss
    0xFF7B3FA0.toInt(), // plum
    0xFFFFD43B.toInt(), // highlighter yellow
    0xFF8CE99A.toInt(), // highlighter green
    0xFFFFA8D5.toInt(), // highlighter pink
    0xFFFFFFFF.toInt(),
)
private val DEFAULT_INK = INK_PALETTE[0]
private val DEFAULT_HIGHLIGHT = INK_PALETTE[5]

data class SearchHit(val page: Int, val rects: List<RectF>)
data class PasswordRequest(val file: File, val name: String, val uri: Uri?, val wrong: Boolean)
class ExportRequest(val name: String, val writer: suspend (OutputStream) -> Unit, val onDone: (Uri) -> Unit = {}) {
    var launched = false
}

private sealed class UndoEntry {
    class Added(val a: Annot) : UndoEntry()
    class Removed(val a: Annot, val index: Int) : UndoEntry()
    /** A mark was moved or its text edited: swap between the two versions. */
    class Replaced(val before: Annot, val after: Annot) : UndoEntry()
    class Snapshot(val file: File, val annots: List<Annot>) : UndoEntry()
}

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val recentStore = RecentFiles(app)
    var recentFiles by mutableStateOf(recentStore.load()); private set

    // ---- document ----
    var engine by mutableStateOf<PdfRendererEngine?>(null); private set
    var sessionId by mutableIntStateOf(0); private set
    var docName by mutableStateOf(""); private set
    private var sourceUri: Uri? = null
    var dirty by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val scrollRequests = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    var passwordRequest by mutableStateOf<PasswordRequest?>(null); private set
    var pendingOpen by mutableStateOf<Uri?>(null); private set
    var exportRequest by mutableStateOf<ExportRequest?>(null); private set
    var closeAfterSave = false

    // ---- viewing ----
    var nightMode by mutableStateOf(false)
    var keepScreenOn by mutableStateOf(false)
    var scale by mutableFloatStateOf(1f)
    var currentPage by mutableIntStateOf(0)

    // ---- markup ----
    var tool by mutableStateOf(Tool.NONE); private set
    var shapeKind by mutableStateOf(ShapeKind.RECT)
    var color by mutableIntStateOf(DEFAULT_INK)
    var size by mutableFloatStateOf(0.3f)
    val annots = mutableStateListOf<Annot>()
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set
    var signature by mutableStateOf<Bitmap?>(null)
    var showSignaturePad by mutableStateOf(false)
    var textRequest by mutableStateOf<Pair<Int, Offset>?>(null)
    /** The text mark being re-written (tap on existing text). */
    var textEditing by mutableStateOf<TextAnnot?>(null)
    /** Id of the mark currently being dragged, for the selection outline. */
    var movingId by mutableStateOf<Long?>(null); private set
    private var moveOriginal: Annot? = null
    private var idCounter = 0L

    // ---- search ----
    var searchQuery by mutableStateOf(""); private set
    val searchHits = mutableStateListOf<SearchHit>()
    var searchIndex by mutableIntStateOf(-1); private set
    var searching by mutableStateOf(false); private set
    var searchProgress by mutableFloatStateOf(0f); private set
    private var searchJob: Job? = null
    private var textIndex: TextIndex? = null

    // ---- panels ----
    var formFields by mutableStateOf<List<FormField>?>(null)
    var docInfo by mutableStateOf<DocInfo?>(null)
    var outline by mutableStateOf<List<OutlineEntry>?>(null)

    private val workFile get() = File(FileUtils.workDir(ctx), "current.pdf")
    private fun message(s: String) { messages.tryEmit(s) }
    private fun baseName() = FileUtils.baseName(docName)

    // =====================================================================
    // Opening / closing
    // =====================================================================

    fun requestOpen(uri: Uri) {
        if (engine != null && dirty) pendingOpen = uri else open(uri)
    }

    fun resolvePendingOpen(discard: Boolean) {
        val u = pendingOpen
        pendingOpen = null
        if (discard && u != null) open(u)
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            busy = "Opening"
            try {
                val name = withContext(Dispatchers.IO) { FileUtils.displayName(ctx, uri) }
                val imported = File(FileUtils.workDir(ctx), "import.pdf")
                withContext(Dispatchers.IO) { FileUtils.copyUriToFile(ctx, uri, imported) }
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Exception) {
                    try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }
                openImported(imported, name, uri, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Couldn't open this file — ${e.localizedMessage ?: "unknown error"}")
            } finally {
                busy = null
            }
        }
    }

    fun openRecent(uriString: String) {
        val uri = Uri.parse(uriString)
        val readable = try {
            ctx.contentResolver.openInputStream(uri)?.close(); true
        } catch (_: Exception) { false }
        if (readable) requestOpen(uri) else {
            recentFiles = recentStore.remove(uriString)
            message("That file has moved or access expired — open it again from your files")
        }
    }

    fun removeRecent(uriString: String) { recentFiles = recentStore.remove(uriString) }

    private suspend fun openImported(src: File, name: String, uri: Uri?, password: String?) {
        var file = src
        if (!withContext(Dispatchers.IO) { PdfRendererEngine.canOpen(src) }) {
            val fixed = File(FileUtils.workDir(ctx), "decrypted.pdf")
            try {
                withContext(Dispatchers.IO) { PdfOps.decryptOrRepair(src, password, fixed) }
            } catch (e: InvalidPasswordException) {
                passwordRequest = PasswordRequest(src, name, uri, wrong = password != null)
                return
            }
            if (!withContext(Dispatchers.IO) { PdfRendererEngine.canOpen(fixed) }) throw IOException("this PDF can't be displayed")
            file = fixed
            if (password != null) message("Unlocked. Saving writes an unlocked copy — use Protect to re-lock.")
        }
        loadWorking(file, name, uri)
        if (uri != null) recentFiles = recentStore.add(uri.toString(), name)
    }

    fun submitPassword(pw: String) {
        val req = passwordRequest ?: return
        passwordRequest = null
        viewModelScope.launch {
            busy = "Unlocking"
            try { openImported(req.file, req.name, req.uri, pw) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("Couldn't unlock — ${e.localizedMessage}") }
            finally { busy = null }
        }
    }

    fun cancelPassword() { passwordRequest = null }

    private suspend fun loadWorking(src: File, name: String, uri: Uri?) {
        closeInternal()
        withContext(Dispatchers.IO) {
            if (src.absolutePath != workFile.absolutePath) src.copyTo(workFile, overwrite = true)
            FileUtils.workDir(ctx).listFiles()?.filter { it.name.startsWith("snap_") }?.forEach { it.delete() }
        }
        engine = withContext(Dispatchers.IO) { PdfRendererEngine.open(workFile) }
        docName = name; sourceUri = uri; dirty = false; scale = 1f; currentPage = 0
        tool = Tool.NONE
        annots.clear(); undoStack.clear(); redoStack.clear(); updateCounts()
        resetSearch()
        sessionId++
    }

    private suspend fun closeInternal() {
        engine?.close()
        engine = null
        textIndex?.let { t -> withContext(Dispatchers.IO) { runCatching { t.close() } } }
        textIndex = null
    }

    fun closeDocument() {
        viewModelScope.launch {
            closeInternal()
            docName = ""; sourceUri = null; dirty = false
            annots.clear(); undoStack.clear(); redoStack.clear(); updateCounts()
            resetSearch(); tool = Tool.NONE
        }
    }

    private fun createNew(label: String, name: String, producer: suspend (File) -> Unit) {
        viewModelScope.launch {
            busy = label
            try {
                val out = File(FileUtils.workDir(ctx), "new.pdf")
                withContext(Dispatchers.IO) { producer(out) }
                loadWorking(out, name, null)
                dirty = true
                message("$name is ready — save it to keep it")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("Couldn't create $name — ${e.localizedMessage}") }
            finally { busy = null }
        }
    }

    fun merge(uris: List<Uri>) = createNew("Binding ${uris.size} documents", "Merged.pdf") { PdfOps.merge(ctx, uris, it) }
    fun imagesToPdf(uris: List<Uri>) = createNew("Setting ${uris.size} images", "Images.pdf") { PdfOps.imagesToPdf(ctx, uris, it) }

    private suspend fun reloadEngine() {
        val old = engine
        engine = withContext(Dispatchers.IO) { PdfRendererEngine.open(workFile) }
        old?.close()
        textIndex?.let { t -> withContext(Dispatchers.IO) { runCatching { t.close() } } }
        textIndex = null
        searchJob?.cancel(); searchHits.clear(); searchIndex = -1
    }

    // =====================================================================
    // Structural edits with file snapshots (undoable)
    // =====================================================================

    private fun snapshot(list: List<Annot>): UndoEntry.Snapshot {
        val f = File(FileUtils.workDir(ctx), "snap_${System.nanoTime()}.pdf")
        workFile.copyTo(f, overwrite = true)
        return UndoEntry.Snapshot(f, list)
    }

    private fun replaceWorkFile(from: File) {
        val tmp = File(workFile.parentFile, "restore.tmp")
        from.copyTo(tmp, overwrite = true)
        PdfOps.replace(tmp, workFile)
    }

    private suspend fun runStructural(label: String, block: suspend (File) -> Unit): Boolean {
        if (engine == null) return false
        busy = label
        val pending = annots.toList()
        val snap = withContext(Dispatchers.IO) { snapshot(pending) }
        return try {
            withContext(Dispatchers.IO) {
                PdfOps.applyAnnotations(workFile, pending)
                block(workFile)
            }
            annots.clear()
            pushUndo(snap)
            dirty = true
            reloadEngine()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { replaceWorkFile(snap.file); snap.file.delete() }
            reloadEngine()
            message("That didn't work — ${e.localizedMessage ?: e.javaClass.simpleName}")
            false
        } finally {
            busy = null
        }
    }

    private suspend fun commitPending(): Boolean =
        if (annots.isEmpty()) true else runStructural("Inking your marks") { }

    private fun structural(label: String, block: suspend (File) -> Unit) {
        viewModelScope.launch { runStructural(label, block) }
    }

    fun rotatePages(pages: List<Int>, delta: Int) = structural("Turning pages") { PdfOps.rotatePages(it, pages, delta) }

    fun deletePages(pages: List<Int>) {
        val n = engine?.pageCount ?: return
        if (pages.distinct().size >= n) { message("A document needs at least one page"); return }
        structural("Removing pages") { PdfOps.deletePages(it, pages) }
    }

    fun movePage(from: Int, to: Int) = structural("Reordering") { PdfOps.movePage(it, from, to) }
    fun insertBlank(after: Int) = structural("Adding a blank page") { PdfOps.insertBlankAfter(it, after) }
    fun appendPdf(uri: Uri) = structural("Binding pages in") { PdfOps.appendPdf(ctx, it, uri) }
    fun addPageNumbers() = structural("Numbering pages") { PdfOps.addPageNumbers(it) }
    fun addWatermark(text: String, color: Int, opacity: Float, scale: Float) =
        structural("Stamping watermark") { PdfOps.watermark(it, text, color, opacity, scale) }

    fun loadForm() {
        viewModelScope.launch {
            busy = "Reading form"
            try {
                val f = withContext(Dispatchers.IO) { PdfOps.readForm(workFile) }
                if (f.isEmpty()) message("No fillable fields in this document — use the Text tool to write on it") else formFields = f
            } catch (e: Exception) { message("Couldn't read form — ${e.localizedMessage}") }
            finally { busy = null }
        }
    }

    fun saveForm(values: Map<String, String>, flatten: Boolean) {
        formFields = null
        structural("Filling in") { PdfOps.writeForm(it, values, flatten) }
    }

    fun loadInfo() {
        viewModelScope.launch {
            try { docInfo = withContext(Dispatchers.IO) { PdfOps.readInfo(workFile) } }
            catch (e: Exception) { message("Couldn't read properties — ${e.localizedMessage}") }
        }
    }

    fun saveInfo(info: DocInfo) {
        docInfo = null
        structural("Updating properties") { PdfOps.writeInfo(it, info) }
    }

    fun loadOutline() {
        viewModelScope.launch {
            try {
                val o = withContext(Dispatchers.IO) { PdfOps.outline(workFile) }
                if (o.isEmpty()) message("This document has no bookmarks") else outline = o
            } catch (e: Exception) { message("Couldn't read bookmarks — ${e.localizedMessage}") }
        }
    }

    // =====================================================================
    // Markup
    // =====================================================================

    fun selectTool(t: Tool) {
        val next = if (tool == t) Tool.NONE else t
        if (next == Tool.HIGHLIGHTER && color == DEFAULT_INK) color = DEFAULT_HIGHLIGHT
        if (next != Tool.HIGHLIGHTER && tool == Tool.HIGHLIGHTER && color == DEFAULT_HIGHLIGHT) color = DEFAULT_INK
        if (next == Tool.SIGNATURE && signature == null) showSignaturePad = true
        tool = next
    }

    fun strokeWidthNorm(t: Tool = tool) = if (t == Tool.HIGHLIGHTER) 0.012f + size * 0.035f else 0.0015f + size * 0.012f
    fun textSizeNorm() = 0.014f + size * 0.05f
    fun newId() = ++idCounter

    fun addAnnot(a: Annot) {
        annots.add(a); pushUndo(UndoEntry.Added(a)); dirty = true
    }

    fun removeAnnot(a: Annot) {
        val i = annots.indexOfFirst { it.id == a.id }
        if (i >= 0) { annots.removeAt(i); pushUndo(UndoEntry.Removed(a, i)); dirty = true }
    }

    fun requestText(page: Int, pos: Offset) { textRequest = page to pos }

    fun addText(text: String) {
        val (page, pos) = textRequest ?: return
        textRequest = null
        if (text.isBlank()) return
        addAnnot(TextAnnot(newId(), page, pos, text.trimEnd(), color, textSizeNorm()))
    }

    fun editText(t: TextAnnot) { textEditing = t }

    fun finishTextEdit(text: String) {
        val old = textEditing ?: return
        textEditing = null
        if (text.isBlank()) { removeAnnot(old); return }
        if (text.trimEnd() == old.text) return
        val new = old.copy(text = text.trimEnd())
        swap(old.id, new)
        pushUndo(UndoEntry.Replaced(old, new)); dirty = true
    }

    // ---- moving marks ----

    private fun swap(id: Long, replacement: Annot) {
        val i = annots.indexOfFirst { it.id == id }
        if (i >= 0) annots[i] = replacement
    }

    fun beginMove(a: Annot) {
        moveOriginal = annots.firstOrNull { it.id == a.id } ?: return
        movingId = a.id
    }

    /** dx/dy are in normalised page units. */
    fun dragMove(dx: Float, dy: Float) {
        val id = movingId ?: return
        val cur = annots.firstOrNull { it.id == id } ?: return
        swap(id, cur.translated(dx, dy))
    }

    fun endMove() {
        val id = movingId
        val before = moveOriginal
        movingId = null; moveOriginal = null
        if (id == null || before == null) return
        val after = annots.firstOrNull { it.id == id } ?: return
        if (after != before) { pushUndo(UndoEntry.Replaced(before, after)); dirty = true }
    }

    fun placeSignature(page: Int, center: Offset) {
        val bmp = signature ?: run { showSignaturePad = true; return }
        val ps = engine?.pageSizes?.getOrNull(page) ?: return
        val w = 0.34f
        val h = w * ps.w * bmp.height / bmp.width / ps.h
        val x = (center.x - w / 2).coerceIn(0f, max(0f, 1f - w))
        val y = (center.y - h / 2).coerceIn(0f, max(0f, 1f - h))
        addAnnot(ImageAnnot(newId(), page, Offset(x, y), w, h, bmp))
    }

    private fun pushUndo(e: UndoEntry) {
        undoStack.addLast(e)
        if (undoStack.size > 40) (undoStack.removeFirst() as? UndoEntry.Snapshot)?.file?.delete()
        redoStack.forEach { (it as? UndoEntry.Snapshot)?.file?.delete() }
        redoStack.clear()
        updateCounts()
    }

    private fun updateCounts() { undoCount = undoStack.size; redoCount = redoStack.size }

    private suspend fun restoreSnapshot(s: UndoEntry.Snapshot) {
        withContext(Dispatchers.IO) { replaceWorkFile(s.file); s.file.delete() }
        annots.clear(); annots.addAll(s.annots)
        reloadEngine()
    }

    fun undo() {
        if (busy != null) return
        val e = undoStack.removeLastOrNull() ?: return
        when (e) {
            is UndoEntry.Added -> { annots.removeAll { it.id == e.a.id }; redoStack.addLast(e) }
            is UndoEntry.Removed -> { annots.add(min(e.index, annots.size), e.a); redoStack.addLast(e) }
            is UndoEntry.Replaced -> { swap(e.after.id, e.before); redoStack.addLast(e) }
            is UndoEntry.Snapshot -> viewModelScope.launch {
                busy = "Undoing"
                try {
                    val cur = withContext(Dispatchers.IO) { snapshot(annots.toList()) }
                    restoreSnapshot(e); redoStack.addLast(cur); updateCounts()
                } finally { busy = null }
            }
        }
        dirty = true
        updateCounts()
    }

    fun redo() {
        if (busy != null) return
        val e = redoStack.removeLastOrNull() ?: return
        when (e) {
            is UndoEntry.Added -> { annots.add(e.a); undoStack.addLast(e) }
            is UndoEntry.Removed -> { annots.removeAll { it.id == e.a.id }; undoStack.addLast(e) }
            is UndoEntry.Replaced -> { swap(e.before.id, e.after); undoStack.addLast(e) }
            is UndoEntry.Snapshot -> viewModelScope.launch {
                busy = "Redoing"
                try {
                    val cur = withContext(Dispatchers.IO) { snapshot(annots.toList()) }
                    restoreSnapshot(e); undoStack.addLast(cur); updateCounts()
                } finally { busy = null }
            }
        }
        dirty = true
        updateCounts()
    }

    // =====================================================================
    // Saving / exporting
    // =====================================================================

    fun save(closeAfter: Boolean = false) {
        viewModelScope.launch {
            if (!commitPending()) return@launch
            val uri = sourceUri
            if (uri == null) { startSaveAs(closeAfter); return@launch }
            busy = "Saving"
            val ok = withContext(Dispatchers.IO) {
                try {
                    FileUtils.openOutput(ctx, uri).use { o -> workFile.inputStream().use { it.copyTo(o) } }; true
                } catch (_: Exception) { false }
            }
            busy = null
            if (ok) {
                dirty = false
                message("Saved")
                if (closeAfter) closeDocument()
            } else {
                message("The original is read-only — choose where to save a copy")
                startSaveAs(closeAfter)
            }
        }
    }

    fun saveAs() {
        viewModelScope.launch { if (commitPending()) startSaveAs(false) }
    }

    private fun startSaveAs(closeAfter: Boolean) {
        exportRequest = ExportRequest(FileUtils.ensurePdf(docName.ifBlank { "document" }), { out ->
            workFile.inputStream().use { it.copyTo(out) }
        }) { uri ->
            sourceUri = uri
            docName = FileUtils.displayName(ctx, uri)
            dirty = false
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            recentFiles = recentStore.add(uri.toString(), docName)
            if (closeAfter) closeDocument()
        }
    }

    fun extractPages(pages: List<Int>) {
        viewModelScope.launch {
            if (!commitPending()) return@launch
            val sel = pages.sorted()
            exportRequest = ExportRequest("${baseName()}_pages.pdf", { out -> PdfOps.extractPages(workFile, sel, out) })
        }
    }

    fun exportProtected(userPw: String, ownerPw: String, allowPrint: Boolean, allowCopy: Boolean) {
        viewModelScope.launch {
            if (!commitPending()) return@launch
            exportRequest = ExportRequest("${baseName()}_locked.pdf", { out ->
                PdfOps.protectCopy(workFile, userPw, ownerPw, allowPrint, allowCopy, out)
            })
        }
    }

    fun markExportLaunched() { exportRequest?.launched = true }

    fun onExportResult(uri: Uri?) {
        val req = exportRequest ?: return
        exportRequest = null
        if (uri == null) { closeAfterSave = false; return }
        viewModelScope.launch {
            busy = "Writing"
            try {
                withContext(Dispatchers.IO) { FileUtils.openOutput(ctx, uri).use { req.writer(it) } }
                req.onDone(uri)
                message("Saved ${FileUtils.displayName(ctx, uri)}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message("Couldn't save — ${e.localizedMessage}") }
            finally { busy = null }
        }
    }

    private suspend fun outputCopy(): File? {
        if (!commitPending()) return null
        return withContext(Dispatchers.IO) {
            File(FileUtils.shareDir(ctx), FileUtils.ensurePdf(docName.ifBlank { "document" })).also { workFile.copyTo(it, overwrite = true) }
        }
    }

    fun share() {
        viewModelScope.launch { outputCopy()?.let { FileUtils.shareFile(ctx, it, "application/pdf", "Send $docName") } }
    }

    fun print(activity: Context) {
        viewModelScope.launch {
            val f = outputCopy() ?: return@launch
            val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(docName, PdfPrintAdapter(f, docName), null)
        }
    }

    fun exportPageImage(page: Int) {
        viewModelScope.launch {
            if (!commitPending()) return@launch
            val e = engine ?: return@launch
            busy = "Developing page ${page + 1}"
            try {
                val bmp = e.render(page, e.pageSizes[page].w * 3) ?: return@launch
                val f = withContext(Dispatchers.IO) {
                    File(FileUtils.shareDir(ctx), "${baseName()}_page${page + 1}.png").also { out ->
                        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                FileUtils.shareFile(ctx, f, "image/png", "Send page ${page + 1}")
            } catch (ex: Exception) { message("Couldn't export — ${ex.localizedMessage}") }
            finally { busy = null }
        }
    }

    private suspend fun index(): TextIndex =
        textIndex ?: withContext(Dispatchers.IO) { TextIndex(workFile) }.also { textIndex = it }

    fun copyPageText(page: Int) {
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { index().page(page).text.trim() }
                if (t.isEmpty()) { message("No selectable text on this page (it may be a scan)"); return@launch }
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Page ${page + 1}", t))
                message("Copied text of page ${page + 1}")
            } catch (e: Exception) { message("Couldn't read text — ${e.localizedMessage}") }
        }
    }

    // =====================================================================
    // Search
    // =====================================================================

    fun scrollTo(page: Int) { scrollRequests.tryEmit(page) }

    private fun resetSearch() {
        searchJob?.cancel(); searchHits.clear(); searchIndex = -1; searchQuery = ""; searching = false
    }

    fun clearSearch() = resetSearch()

    fun search(query: String) {
        searchJob?.cancel()
        searchHits.clear(); searchIndex = -1; searchQuery = query
        val q = query.trim().lowercase()
        val e = engine ?: return
        if (q.isEmpty()) return
        searchJob = viewModelScope.launch {
            searching = true; searchProgress = 0f
            try {
                val idx = index()
                for (p in 0 until e.pageCount) {
                    val pt = withContext(Dispatchers.IO) { idx.page(p) }
                    val t = pt.text.lowercase()
                    var from = 0
                    while (true) {
                        val k = t.indexOf(q, from)
                        if (k < 0) break
                        val end = min(k + q.length, pt.boxes.size)
                        val start = min(k, end)
                        val rects = TextIndex.mergeRects(pt.boxes.subList(start, end).filterNotNull())
                        searchHits.add(SearchHit(p, rects))
                        if (searchIndex < 0) { searchIndex = 0; scrollTo(p) }
                        from = k + max(1, q.length)
                    }
                    searchProgress = (p + 1f) / e.pageCount
                }
                if (searchHits.isEmpty()) message("No matches for “${query.trim()}”")
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) { message("Search failed — ${ex.localizedMessage}") }
            finally { searching = false }
        }
    }

    fun nextHit(delta: Int) {
        if (searchHits.isEmpty()) return
        searchIndex = ((searchIndex + delta) % searchHits.size + searchHits.size) % searchHits.size
        scrollTo(searchHits[searchIndex].page)
    }

    override fun onCleared() {
        textIndex?.let { runCatching { it.close() } }
    }
}

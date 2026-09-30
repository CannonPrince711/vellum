package app.vellum.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.vellum.pdf.PdfRendererEngine
import app.vellum.ui.theme.LocalVellum
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(vm: ViewerViewModel, engine: PdfRendererEngine) {
    val v = LocalVellum.current
    val ctx = LocalContext.current
    val listState = key(vm.sessionId) { rememberLazyListState() }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(false) }
    var showProtect by remember { mutableStateOf(false) }
    var showClose by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var spineAwake by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        vm.scrollRequests.collect { p ->
            val count = vm.engine?.pageCount ?: 0
            if (count > 0) listState.scrollToItem(p.coerceIn(0, count - 1))
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }.collect { (i, scrolling) ->
            vm.currentPage = i
            if (scrolling) spineAwake = true
        }
    }
    // the spine dozes off after a few idle seconds
    LaunchedEffect(spineAwake, listState.isScrollInProgress) {
        if (spineAwake && !listState.isScrollInProgress) { delay(2600); spineAwake = false }
    }

    val appendLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::appendPdf) }

    fun tryClose() { if (vm.dirty) showClose = true else vm.closeDocument() }

    BackHandler {
        when {
            searchOpen -> { searchOpen = false; vm.clearSearch() }
            vm.tool != Tool.NONE -> vm.selectTool(Tool.NONE)
            else -> tryClose()
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(if (vm.nightMode) androidx.compose.ui.graphics.Color(0xFF0E1012) else v.desk)) {
        DocumentView(
            vm, engine, listState,
            topInset = statusTop + 72.dp, bottomInset = navBottom + 60.dp,
            onTap = { chrome = !chrome; spineAwake = chrome }
        )

        PageSpine(
            current = vm.currentPage, count = engine.pageCount,
            visible = spineAwake,
            onScrub = { vm.scrollTo(it); spineAwake = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = statusTop + 84.dp, bottom = navBottom + 110.dp)
        )

        AnimatedVisibility(
            visible = chrome || searchOpen,
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        ) {
            if (searchOpen) {
                SearchCapsule(vm) { searchOpen = false; vm.clearSearch() }
            } else {
                val pct = (vm.scale * 100).roundToInt()
                HeaderCapsule(
                    title = vm.docName,
                    subtitle = buildString {
                        append("${engine.pageCount} pages")
                        if (vm.annots.isNotEmpty()) append(" · ${vm.annots.size} new marks")
                        if (pct != 100) append(" · $pct%")
                    },
                    dirty = vm.dirty,
                    onBack = { tryClose() },
                    onSearch = { searchOpen = true },
                    onPages = { showPages = true },
                ) {
                    Box {
                        MenuTrigger { menuOpen = true }
                        DropdownMenu(
                            expanded = menuOpen, onDismissRequest = { menuOpen = false },
                            offset = DpOffset(0.dp, 8.dp),
                            shape = RoundedCornerShape(20.dp),
                            containerColor = v.sheet,
                        ) {
                            val page = vm.currentPage
                            fun act(f: () -> Unit): () -> Unit = { menuOpen = false; f() }
                            MenuLabel("Keep")
                            Item(Icons.Default.Save, "Save", act { vm.save() })
                            Item(Icons.Default.SaveAs, "Save a copy…", act { vm.saveAs() })
                            Item(Icons.Default.Share, "Send", act { vm.share() })
                            Item(Icons.Default.Print, "Print", act { vm.print(ctx) })
                            Item(Icons.Default.Lock, "Lock a copy…", act { showProtect = true })
                            HorizontalDivider(color = v.rule.copy(alpha = 0.5f))
                            MenuLabel("Navigate")
                            Item(Icons.Default.Numbers, "Turn to page…", act { showGoTo = true })
                            Item(Icons.Default.Bookmarks, "Contents", act { vm.loadOutline() })
                            if (vm.scale != 1f) Item(Icons.Default.ZoomOutMap, "Fit width", act { vm.scale = 1f })
                            HorizontalDivider(color = v.rule.copy(alpha = 0.5f))
                            MenuLabel("Work on it")
                            Item(Icons.Default.EditNote, "Fill in form…", act { vm.loadForm() })
                            Item(Icons.Default.WaterDrop, "Watermark…", act { showWatermark = true })
                            Item(Icons.Default.Info, "Colophon…", act { vm.loadInfo() })
                            Item(Icons.Default.Image, "Page ${page + 1} as image", act { vm.exportPageImage(page) })
                            Item(Icons.Default.ContentCopy, "Copy text of page ${page + 1}", act { vm.copyPageText(page) })
                            HorizontalDivider(color = v.rule.copy(alpha = 0.5f))
                            MenuLabel("Reading")
                            Item(Icons.Default.DarkMode, if (vm.nightMode) "Daylight pages" else "Night pages", act { vm.nightMode = !vm.nightMode })
                            Item(Icons.Default.Lightbulb, if (vm.keepScreenOn) "Let screen sleep" else "Keep screen awake", act { vm.keepScreenOn = !vm.keepScreenOn })
                            Item(Icons.Default.Close, "Close document", act { tryClose() })
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && !searchOpen,
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)
        ) { ToolDock(vm) }
    }

    // ---------------- sheets & dialogs ----------------
    if (showPages) PagesSheet(vm, engine, vm.currentPage, onDismiss = { showPages = false }) {
        appendLauncher.launch(arrayOf("application/pdf"))
    }
    vm.outline?.let { entries ->
        OutlineSheet(entries, onPick = { vm.scrollTo(it); vm.outline = null }, onDismiss = { vm.outline = null })
    }
    if (showGoTo) GoToPageDialog(engine.pageCount, { vm.scrollTo(it); showGoTo = false }) { showGoTo = false }
    if (showWatermark) WatermarkDialog({ t, c, o, s -> showWatermark = false; vm.addWatermark(t, c, o, s) }) { showWatermark = false }
    if (showProtect) ProtectDialog({ pw, owner, p, c -> showProtect = false; vm.exportProtected(pw, owner, p, c) }) { showProtect = false }
    if (showClose) ConfirmCloseDialog(
        onSave = { showClose = false; vm.save(closeAfter = true) },
        onDiscard = { showClose = false; vm.closeDocument() },
        onDismiss = { showClose = false }
    )
    vm.textRequest?.let { TextInputDialog({ vm.addText(it) }, { vm.textRequest = null }) }
    vm.textEditing?.let { t ->
        key(t.id) { TextInputDialog({ vm.finishTextEdit(it) }, { vm.textEditing = null }, initial = t.text) }
    }
    if (vm.showSignaturePad) SignaturePadDialog(
        inkColor = if (vm.color == INK_PALETTE[5] || vm.color == INK_PALETTE[6] || vm.color == INK_PALETTE[7] || vm.color == INK_PALETTE[8]) INK_PALETTE[0] else vm.color,
        onDone = { vm.signature = it; vm.showSignaturePad = false },
        onDismiss = {
            vm.showSignaturePad = false
            if (vm.signature == null && vm.tool == Tool.SIGNATURE) vm.selectTool(Tool.NONE)
        }
    )
    vm.formFields?.let { FormDialog(it, { values, flat -> vm.saveForm(values, flat) }) { vm.formFields = null } }
    vm.docInfo?.let { InfoDialog(it, { info -> vm.saveInfo(info) }) { vm.docInfo = null } }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalVellum.current.accent,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    val v = LocalVellum.current
    DropdownMenuItem(
        text = { Text(label, color = v.ink) },
        leadingIcon = { Icon(icon, null, tint = v.inkSoft) },
        onClick = onClick
    )
}


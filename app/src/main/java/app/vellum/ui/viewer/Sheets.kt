package app.vellum.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.pdf.OutlineEntry
import app.vellum.pdf.PdfRendererEngine
import app.vellum.ui.theme.Display
import app.vellum.ui.theme.LocalVellum
import app.vellum.ui.theme.Marginalia

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(
    vm: ViewerViewModel,
    engine: PdfRendererEngine,
    currentPage: Int,
    onDismiss: () -> Unit,
    onAppendPdf: () -> Unit,
) {
    val v = LocalVellum.current
    val selected = remember { mutableStateListOf<Int>() }
    val n = engine.pageCount
    LaunchedEffect(n) { selected.removeAll { it >= n } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = v.desk,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (selected.isEmpty()) "The pages" else "${selected.size} chosen",
                    style = MaterialTheme.typography.headlineMedium, color = v.ink, modifier = Modifier.weight(1f)
                )
                Text(
                    if (selected.isEmpty()) "hold a page to choose" else "tap to add more",
                    style = Marginalia, color = v.inkSoft, modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selected.isEmpty()) {
                    Chip(Icons.Default.SelectAll, "Choose all") { selected.clear(); selected.addAll(0 until n) }
                    Chip(Icons.Default.AddBox, "Blank after ${currentPage + 1}") { vm.insertBlank(currentPage) }
                    Chip(Icons.Default.PostAdd, "Bind in a PDF") { onAppendPdf() }
                    Chip(Icons.Default.FormatListNumbered, "Number pages") { vm.addPageNumbers() }
                } else {
                    val sel = selected.sorted()
                    Chip(Icons.Default.RotateLeft, "Turn left") { vm.rotatePages(sel, -90) }
                    Chip(Icons.Default.RotateRight, "Turn right") { vm.rotatePages(sel, 90) }
                    if (sel.size == 1) {
                        val i = sel[0]
                        if (i > 0) Chip(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier") {
                            vm.movePage(i, i - 1); selected.clear(); selected.add(i - 1)
                        }
                        if (i < n - 1) Chip(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later") {
                            vm.movePage(i, i + 1); selected.clear(); selected.add(i + 1)
                        }
                    }
                    Chip(Icons.Default.Download, "Extract") { vm.extractPages(sel) }
                    Chip(Icons.Default.Delete, "Remove", danger = true) { vm.deletePages(sel); selected.clear() }
                    Chip(Icons.Default.Close, "Clear") { selected.clear() }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(n, key = { it }) { i ->
                    Thumb(
                        engine, i, selected = i in selected, current = i == currentPage,
                        onClick = {
                            if (selected.isEmpty()) { vm.scrollTo(i); onDismiss() }
                            else if (i in selected) selected.removeAll { it == i } else selected.add(i)
                        },
                        onLongClick = { if (i in selected) selected.removeAll { it == i } else selected.add(i) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val v = LocalVellum.current
    val tint = if (danger) v.accent else v.ink
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(v.sheet)
            .border(1.dp, if (danger) v.accent.copy(alpha = 0.5f) else v.rule, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(
    engine: PdfRendererEngine, i: Int, selected: Boolean, current: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    val v = LocalVellum.current
    val ps = engine.pageSizes[i]
    var bmp by remember(engine, i) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(engine, i) { engine.render(i, 280)?.let { bmp = it.asImageBitmap() } }
    Column(
        Modifier.padding(8.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ps.w.toFloat() / ps.h)
                .border(if (selected) 3.dp else 1.dp, if (selected) v.accent else v.rule, RoundedCornerShape(4.dp))
                .padding(if (selected) 3.dp else 1.dp)
                .background(Color.White)
        ) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(v.accent),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${i + 1}", fontFamily = Display, fontSize = 14.sp,
            color = if (current) v.accent else v.inkSoft
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(entries: List<OutlineEntry>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = v.desk,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Text("Contents", style = MaterialTheme.typography.headlineMedium, color = v.ink, modifier = Modifier.padding(horizontal = 22.dp))
        LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 40.dp)) {
            itemsIndexed(entries) { _, e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = e.page >= 0) { onPick(e.page) }
                        .padding(start = 12.dp + (e.level * 16).dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        e.title,
                        style = if (e.level == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color = v.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    // dotted leader to the page number, like a printed table of contents
                    Text(" ····· ", color = v.rule, maxLines = 1)
                    Text(if (e.page >= 0) "${e.page + 1}" else "–", fontFamily = Display, color = v.accent, fontSize = 15.sp)
                }
            }
        }
    }
}

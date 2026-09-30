package app.vellum.ui.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.data.RecentFile
import app.vellum.ui.theme.Display
import app.vellum.ui.theme.LocalVellum
import app.vellum.ui.theme.Marginalia
import app.vellum.ui.viewer.ViewerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: ViewerViewModel) {
    val v = LocalVellum.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::requestOpen) }
    val merge = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.merge(it)
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.imagesToPdf(it)
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        Modifier.fillMaxSize().background(v.desk),
        contentPadding = PaddingValues(
            start = 22.dp, end = 22.dp,
            top = insets.calculateTopPadding() + 28.dp,
            bottom = insets.calculateBottomPadding() + 40.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()),
                    style = Marginalia, color = v.inkSoft
                )
                Text(
                    buildAnnotatedString {
                        append("Vellum")
                        withStyle(SpanStyle(color = v.accent)) { append(".") }
                    },
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, lineHeight = 56.sp),
                    color = v.ink
                )
                Text("Read, mark up, sign and bind your documents.", style = MaterialTheme.typography.bodyMedium, color = v.inkSoft)
                Spacer(Modifier.height(18.dp))
            }
        }

        item { OpenSlab { open.launch(arrayOf("application/pdf")) } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stamp(Icons.Default.MergeType, "Bind", "Merge several PDFs", Modifier.weight(1f)) {
                    merge.launch(arrayOf("application/pdf"))
                }
                Stamp(Icons.Default.Image, "Develop", "Photos into a PDF", Modifier.weight(1f)) {
                    images.launch(arrayOf("image/*"))
                }
            }
        }

        item {
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("On the desk", style = MaterialTheme.typography.headlineSmall, color = v.ink)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(v.rule))
            }
        }

        if (vm.recentFiles.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptySheets()
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing here yet.", style = MaterialTheme.typography.titleLarge, color = v.ink)
                    Text("Documents you open will rest here.", style = Marginalia, color = v.inkSoft)
                }
            }
        } else {
            itemsIndexed(vm.recentFiles, key = { _, r -> r.uri }) { i, r ->
                IndexCard(r, tilt = if (i % 2 == 0) -0.6f else 0.5f,
                    onOpen = { vm.openRecent(r.uri) }, onRemove = { vm.removeRecent(r.uri) })
            }
        }
    }
}

@Composable
private fun OpenSlab(onClick: () -> Unit) {
    val ink = Color(0xFF1C2533)
    val paper = Color(0xFFF3EEE3)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(26.dp), spotColor = Color(0x991C2533))
            .clip(RoundedCornerShape(26.dp))
            .background(ink)
            .clickable(onClick = onClick)
    ) {
        // faint ruled lines, like a notebook page
        Canvas(Modifier.matchParentSize()) {
            var y = 28f
            while (y < size.height) {
                drawLine(paper.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), 2f)
                y += 34f
            }
            drawLine(Color(0xFFD9480F).copy(alpha = 0.55f), Offset(56f, 0f), Offset(56f, size.height), 3f)
        }
        Row(Modifier.padding(start = 76.dp, end = 20.dp, top = 26.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open a document", fontFamily = Display, fontSize = 26.sp, color = paper)
                Text("From your phone, Drive or anywhere", style = Marginalia, color = paper.copy(alpha = 0.7f))
            }
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFD9480F)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White) }
        }
    }
}

@Composable
private fun Stamp(icon: ImageVector, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    val v = LocalVellum.current
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(v.sheet)
            .border(1.5.dp, v.rule, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(v.accentSoft),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = v.accent) }
        Spacer(Modifier.height(14.dp))
        Text(title, fontFamily = Display, fontSize = 22.sp, color = v.ink)
        Text(caption, style = Marginalia, color = v.inkSoft)
    }
}

@Composable
private fun IndexCard(r: RecentFile, tilt: Float, onOpen: () -> Unit, onRemove: () -> Unit) {
    val v = LocalVellum.current
    val fold = 22.dp
    Box(
        Modifier
            .fillMaxWidth()
            .rotate(tilt)
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(v.sheet)
            .clickable(onClick = onOpen)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val f = fold.toPx()
            // dog-eared corner
            val p = Path().apply {
                moveTo(size.width - f, 0f); lineTo(size.width, f); lineTo(size.width - f, f); close()
            }
            drawPath(p, v.rule)
            val bg = Path().apply { moveTo(size.width - f, 0f); lineTo(size.width, 0f); lineTo(size.width, f); close() }
            drawPath(bg, v.desk)
            drawLine(v.accent, Offset(0f, 0f), Offset(0f, size.height), 8f)
        }
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    fontFamily = Display, fontSize = 19.sp, color = v.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = Marginalia, color = v.inkSoft
                )
            }
            Box(
                Modifier.padding(top = 10.dp).size(38.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, "Remove from desk", tint = v.inkSoft, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun EmptySheets() {
    val v = LocalVellum.current
    Box(Modifier.size(120.dp, 110.dp), contentAlignment = Alignment.Center) {
        listOf(-9f to 0.5f, 4f to 0.75f, 0f to 1f).forEach { (deg, a) ->
            Box(
                Modifier.size(72.dp, 92.dp).rotate(deg)
                    .shadow(3.dp, RoundedCornerShape(4.dp))
                    .background(v.sheet.copy(alpha = a), RoundedCornerShape(4.dp))
            )
        }
        Canvas(Modifier.size(72.dp, 92.dp)) {
            for (k in 0 until 5) {
                val y = size.height * (0.28f + k * 0.13f)
                drawLine(v.rule, Offset(size.width * 0.18f, y), Offset(size.width * (if (k == 4) 0.55f else 0.82f), y), 3f)
            }
            drawCircle(v.accent, 6f, Offset(size.width * 0.8f, size.height * 0.14f))
        }
    }
}

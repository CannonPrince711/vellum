package app.vellum.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.vellum.pdf.Annot
import app.vellum.pdf.AnnotGeometry
import app.vellum.pdf.ImageAnnot
import app.vellum.pdf.InkAnnot
import app.vellum.pdf.PdfRendererEngine
import app.vellum.pdf.ShapeAnnot
import app.vellum.pdf.ShapeKind
import app.vellum.pdf.TextAnnot
import app.vellum.ui.theme.Ink
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.92f, 0f, 0f, 0f, 240f,
            0f, -0.92f, 0f, 0f, 236f,
            0f, 0f, -0.92f, 0f, 228f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

@Composable
fun DocumentView(
    vm: ViewerViewModel,
    engine: PdfRendererEngine,
    listState: LazyListState,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
) {
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    var pendingH by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                // Two-finger pinch zoom around the gesture centroid; one finger falls through to scrolling/drawing.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var hTarget = hScroll.value.toFloat()
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            val zoom = ev.calculateZoom()
                            if (zoom != 1f) {
                                val old = vm.scale
                                val new = (old * zoom).coerceIn(1f, 5f)
                                val f = new / old
                                if (f != 1f) {
                                    val c = ev.calculateCentroid(useCurrent = true)
                                    hTarget = ((hTarget + c.x) * f - c.x).coerceAtLeast(0f)
                                    vm.scale = new
                                    pendingH = hTarget.roundToInt()
                                    listState.dispatchRawDelta((listState.firstVisibleItemScrollOffset + c.y) * (f - 1f))
                                }
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        val margin = 14.dp
        val pageW = (maxWidth - margin * 2) * vm.scale
        val contentW = pageW + margin * 2
        val renderPx = with(density) { pageW.toPx() }.let { ((it / 128f).roundToInt().coerceAtLeast(1)) * 128 }
        LaunchedEffect(vm.scale) { pendingH?.let { hScroll.scrollTo(it); pendingH = null } }
        // Only freehand tools lock scrolling; Move/Text/Sign leave one-finger scrolling on empty paper.
        val drawing = vm.tool == Tool.PEN || vm.tool == Tool.HIGHLIGHTER || vm.tool == Tool.SHAPE || vm.tool == Tool.ERASER

        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = !drawing && vm.scale > 1f)) {
            LazyColumn(
                state = listState,
                userScrollEnabled = !drawing,
                modifier = Modifier.width(contentW).fillMaxHeight(),
                contentPadding = PaddingValues(top = topInset + 12.dp, bottom = bottomInset + 96.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(count = engine.pageCount, key = { it }) { i ->
                    PageItem(
                        index = i, engine = engine, pageWidth = pageW, renderPx = renderPx, vm = vm,
                        onPageTap = onTap,
                        onPageDoubleTap = { vm.scale = if (vm.scale > 1.05f) 1f else 2.5f },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    engine: PdfRendererEngine,
    pageWidth: androidx.compose.ui.unit.Dp,
    renderPx: Int,
    vm: ViewerViewModel,
    onPageTap: () -> Unit,
    onPageDoubleTap: () -> Unit,
) {
    val ps = engine.pageSizes[index]
    var bmp by remember(engine, index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(engine, index, renderPx) {
        engine.render(index, renderPx)?.let { bmp = it.asImageBitmap() }
    }
    val night = vm.nightMode
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(pageWidth)
                .aspectRatio(ps.w.toFloat() / ps.h)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(2.dp), ambientColor = Color(0x551C2533), spotColor = Color(0x661C2533))
                .background(if (night) Color(0xFF1B1D20) else Color.White)
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    b, contentDescription = "Page ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (night) InvertFilter else null
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), strokeWidth = 2.dp, color = Ink.Vermilion)
            }
            AnnotationLayer(index, vm, onPageTap, onPageDoubleTap)
        }
    }
}

@Composable
private fun AnnotationLayer(page: Int, vm: ViewerViewModel, onPageTap: () -> Unit, onPageDoubleTap: () -> Unit) {
    val tm = rememberTextMeasurer()
    val live = remember { mutableStateListOf<Offset>() }
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }
    val tool = vm.tool
    val tolPx = with(LocalDensity.current) { 14.dp.toPx() }

    val gestures = when (tool) {
        Tool.NONE -> Modifier.pointerInput(tool) {
            detectTapGestures(onTap = { onPageTap() }, onDoubleTap = { onPageDoubleTap() })
        }
        Tool.PEN, Tool.HIGHLIGHTER -> Modifier.pointerInput(tool, page) {
            fun n(p: Offset) = Offset(p.x / size.width, p.y / size.height)
            detectDragGestures(
                onDragStart = { live.clear(); live.add(n(it)) },
                onDragEnd = {
                    if (live.isNotEmpty()) vm.addAnnot(
                        InkAnnot(vm.newId(), page, live.toList(), vm.color, vm.strokeWidthNorm(), vm.tool == Tool.HIGHLIGHTER)
                    )
                    live.clear()
                },
                onDragCancel = { live.clear() }
            ) { change, _ -> change.consume(); live.add(n(change.position)) }
        }
        Tool.SHAPE -> Modifier.pointerInput(tool, page) {
            fun n(p: Offset) = Offset(p.x / size.width, p.y / size.height)
            detectDragGestures(
                onDragStart = { shapeStart = n(it); shapeEnd = n(it) },
                onDragEnd = {
                    val s = shapeStart; val e = shapeEnd
                    if (s != null && e != null && (abs(s.x - e.x) > 0.005f || abs(s.y - e.y) > 0.005f)) {
                        vm.addAnnot(ShapeAnnot(vm.newId(), page, vm.shapeKind, s, e, vm.color, vm.strokeWidthNorm()))
                    }
                    shapeStart = null; shapeEnd = null
                },
                onDragCancel = { shapeStart = null; shapeEnd = null }
            ) { change, _ -> change.consume(); shapeEnd = n(change.position) }
        }
        Tool.MOVE -> Modifier
            .dragMarks(page, vm, tolPx) { true }
            .pointerInput(tool, page) {
                detectTapGestures(
                    onTap = { p ->
                        val t = textAt(vm, page, p, size.width.toFloat(), size.height.toFloat(), tolPx)
                        if (t != null) vm.editText(t) else onPageTap()
                    },
                    onDoubleTap = { onPageDoubleTap() }
                )
            }
        Tool.TEXT -> Modifier
            .dragMarks(page, vm, tolPx) { it is TextAnnot }
            .pointerInput(tool, page) {
                detectTapGestures { p ->
                    val t = textAt(vm, page, p, size.width.toFloat(), size.height.toFloat(), tolPx)
                    if (t != null) vm.editText(t) else vm.requestText(page, Offset(p.x / size.width, p.y / size.height))
                }
            }
        Tool.SIGNATURE -> Modifier
            .dragMarks(page, vm, tolPx) { it is ImageAnnot }
            .pointerInput(tool, page) {
                detectTapGestures { p ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val onSignature = vm.annots.any { it.page == page && it is ImageAnnot && AnnotGeometry.hit(it, p, w, h, 0f) }
                    if (!onSignature) vm.placeSignature(page, Offset(p.x / w, p.y / h))
                }
            }
        Tool.ERASER -> Modifier
            .pointerInput(tool, page) {
                detectTapGestures { p ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    vm.annots.lastOrNull { it.page == page && AnnotGeometry.hit(it, p, w, h, tolPx) }?.let(vm::removeAnnot)
                }
            }
            .pointerInput(tool, page) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    vm.annots.lastOrNull { it.page == page && AnnotGeometry.hit(it, change.position, w, h, tolPx) }?.let(vm::removeAnnot)
                }
            }
    }

    Canvas(Modifier.fillMaxSize().then(gestures)) {
        vm.searchHits.forEachIndexed { i, hit ->
            if (hit.page == page) hit.rects.forEach { r ->
                drawRect(
                    color = if (i == vm.searchIndex) Ink.Vermilion.copy(alpha = 0.38f) else Color(0xFFFFD43B).copy(alpha = 0.42f),
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size(r.width() * size.width, r.height() * size.height)
                )
            }
        }
        for (a in vm.annots) if (a.page == page) drawAnnot(a, tm)
        // Movable marks get a faint dashed frame; the one being dragged gets a vermilion frame.
        val showFrames = tool == Tool.MOVE || tool == Tool.TEXT || tool == Tool.SIGNATURE
        for (a in vm.annots) {
            if (a.page != page) continue
            val active = a.id == vm.movingId
            val movable = when (tool) {
                Tool.MOVE -> true
                Tool.TEXT -> a is TextAnnot
                Tool.SIGNATURE -> a is ImageAnnot
                else -> false
            }
            if (!active && !(showFrames && movable)) continue
            val r = AnnotGeometry.bounds(a, size.width, size.height).inflate(6.dp.toPx())
            drawRoundRect(
                color = if (active) Ink.Vermilion else Ink.Graphite.copy(alpha = 0.7f),
                topLeft = r.topLeft, size = r.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(
                    width = (if (active) 2.dp else 1.dp).toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
            )
        }
        if (live.isNotEmpty()) drawInk(live, vm.color, vm.strokeWidthNorm(), tool == Tool.HIGHLIGHTER)
        val s = shapeStart; val e = shapeEnd
        if (s != null && e != null) drawShape(vm.shapeKind, s, e, vm.color, vm.strokeWidthNorm())
    }
}

private fun textAt(vm: ViewerViewModel, page: Int, p: Offset, w: Float, h: Float, tol: Float): TextAnnot? =
    vm.annots.lastOrNull { it.page == page && it is TextAnnot && AnnotGeometry.hit(it, p, w, h, tol) } as TextAnnot?

/**
 * Press on a mark and drag to move it. Presses on empty paper are left alone, so the page still scrolls,
 * and a press without movement still counts as a tap for the next gesture handler.
 */
private fun Modifier.dragMarks(page: Int, vm: ViewerViewModel, tolPx: Float, canMove: (Annot) -> Boolean) =
    pointerInput(vm.tool, page) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val target = vm.annots.lastOrNull {
                it.page == page && canMove(it) && AnnotGeometry.hit(it, down.position, w, h, tolPx)
            } ?: return@awaitEachGesture
            val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            vm.beginMove(target)
            vm.dragMove((first.position.x - down.position.x) / w, (first.position.y - down.position.y) / h)
            drag(first.id) { change ->
                val d = change.positionChange()
                change.consume()
                vm.dragMove(d.x / w, d.y / h)
            }
            vm.endMove()
        }
    }

private fun DrawScope.drawInk(points: List<Offset>, color: Int, width: Float, highlighter: Boolean) {
    val w = size.width; val h = size.height
    val c = Color(color)
    val sw = width * w
    if (points.size == 1) {
        drawCircle(c, sw / 2, Offset(points[0].x * w, points[0].y * h), alpha = if (highlighter) 0.35f else 1f)
        return
    }
    val path = Path().apply {
        moveTo(points[0].x * w, points[0].y * h)
        for (i in 1 until points.size) lineTo(points[i].x * w, points[i].y * h)
    }
    drawPath(
        path, c, alpha = if (highlighter) 0.35f else 1f,
        style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
    )
}

private fun DrawScope.drawShape(kind: ShapeKind, s: Offset, e: Offset, color: Int, width: Float) {
    val w = size.width; val h = size.height
    val a = Offset(s.x * w, s.y * h); val b = Offset(e.x * w, e.y * h)
    val c = Color(color); val sw = width * w
    val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val tl = Offset(min(a.x, b.x), min(a.y, b.y))
    val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
    when (kind) {
        ShapeKind.RECT -> drawRect(c, tl, sz, style = stroke)
        ShapeKind.ELLIPSE -> drawOval(c, tl, sz, style = stroke)
        ShapeKind.LINE -> drawLine(c, a, b, sw, cap = StrokeCap.Round)
        ShapeKind.ARROW -> {
            drawLine(c, a, b, sw, cap = StrokeCap.Round)
            val (p1, p2) = AnnotGeometry.arrowHead(a, b, max(sw * 4f, w * 0.03f))
            drawLine(c, p1, b, sw, cap = StrokeCap.Round)
            drawLine(c, p2, b, sw, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawAnnot(a: Annot, tm: TextMeasurer) {
    when (a) {
        is InkAnnot -> drawInk(a.points, a.color, a.width, a.highlighter)
        is ShapeAnnot -> drawShape(a.kind, a.start, a.end, a.color, a.width)
        is TextAnnot -> {
            val px = a.size * size.width
            drawText(
                tm, a.text,
                topLeft = Offset(a.pos.x * size.width, a.pos.y * size.height),
                style = TextStyle(color = Color(a.color), fontSize = px.toSp(), lineHeight = (px * 1.2f).toSp()),
                softWrap = false
            )
        }
        is ImageAnnot -> drawImage(
            a.bitmap.asImageBitmapCached(),
            dstOffset = IntOffset((a.pos.x * size.width).roundToInt(), (a.pos.y * size.height).roundToInt()),
            dstSize = IntSize((a.w * size.width).roundToInt().coerceAtLeast(1), (a.h * size.height).roundToInt().coerceAtLeast(1))
        )
    }
}

private val imageCache = java.util.WeakHashMap<Bitmap, ImageBitmap>()
private fun Bitmap.asImageBitmapCached(): ImageBitmap = imageCache.getOrPut(this) { asImageBitmap() }

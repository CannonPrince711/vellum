package app.vellum.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.pdf.ShapeKind
import app.vellum.ui.theme.Display
import app.vellum.ui.theme.LocalVellum
import app.vellum.ui.theme.Marginalia
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/*  Floating header capsule                                            */
/* ------------------------------------------------------------------ */

@Composable
fun HeaderCapsule(
    title: String,
    subtitle: String,
    dirty: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPages: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val v = LocalVellum.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = v.sheet,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, v.rule.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Row(Modifier.height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (dirty) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(v.accent))
                    }
                }
                Text(subtitle, style = Marginalia, color = v.inkSoft, maxLines = 1)
            }
            RoundIcon(Icons.Default.Search, "Search", onSearch)
            RoundIcon(Icons.Default.GridView, "Pages", onPages)
            menu()
        }
    }
}

@Composable
fun SearchCapsule(vm: ViewerViewModel, onClose: () -> Unit) {
    val v = LocalVellum.current
    var q by remember { mutableStateOf(vm.searchQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        shape = RoundedCornerShape(28.dp), color = v.sheet, shadowElevation = 10.dp,
        border = BorderStroke(1.dp, v.accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column {
            Row(Modifier.height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Default.Close, "Close search", onClose)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (q.isEmpty()) Text("Find in document…", style = Marginalia.copy(fontSize = 16.sp), color = v.inkSoft)
                    BasicTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = v.ink),
                        cursorBrush = SolidColor(v.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (vm.searchHits.isNotEmpty()) {
                    Text(
                        "${vm.searchIndex + 1}/${vm.searchHits.size}",
                        style = MaterialTheme.typography.labelMedium, color = v.accent
                    )
                }
                RoundIcon(Icons.Default.KeyboardArrowUp, "Previous") { vm.nextHit(-1) }
                RoundIcon(Icons.Default.KeyboardArrowDown, "Next") {
                    if (vm.searchHits.isEmpty() || q != vm.searchQuery) vm.search(q) else vm.nextHit(1)
                }
            }
            if (vm.searching) {
                LinearProgressIndicator(
                    progress = { vm.searchProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = v.accent, trackColor = Color.Transparent
                )
            }
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val v = LocalVellum.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = v.ink, modifier = Modifier.size(22.dp)) }
}

@Composable
fun MenuTrigger(onClick: () -> Unit) = RoundIcon(Icons.Default.MoreVert, "More", onClick)

/* ------------------------------------------------------------------ */
/*  Tool dock + options tray                                           */
/* ------------------------------------------------------------------ */

private data class ToolSpec(val tool: Tool, val icon: ImageVector, val label: String)

private val TOOLS = listOf(
    ToolSpec(Tool.NONE, Icons.Default.PanTool, "Read"),
    ToolSpec(Tool.MOVE, Icons.Default.OpenWith, "Move"),
    ToolSpec(Tool.PEN, Icons.Default.Draw, "Pen"),
    ToolSpec(Tool.HIGHLIGHTER, Icons.Default.BorderColor, "Marker"),
    ToolSpec(Tool.SHAPE, Icons.Default.Category, "Shapes"),
    ToolSpec(Tool.TEXT, Icons.Default.TextFields, "Text"),
    ToolSpec(Tool.SIGNATURE, Icons.Default.Gesture, "Sign"),
    ToolSpec(Tool.ERASER, Icons.Default.AutoFixNormal, "Erase"),
)

@Composable
fun ToolDock(vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val v = LocalVellum.current
    val dockBg = if (MaterialTheme.colorScheme.background.luminanceIsLight()) Color(0xFF1C2533) else Color(0xFF2A2F37)
    val dockInk = Color(0xFFF3EEE3)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = vm.tool != Tool.NONE,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) { OptionsTray(vm) }
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(34.dp), color = dockBg, shadowElevation = 16.dp,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TOOLS.forEach { spec ->
                    val active = vm.tool == spec.tool
                    val blot by animateFloatAsState(if (active) 1f else 0f, label = "blot")
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { vm.selectTool(spec.tool) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            // the "ink blot" behind the active tool
                            Canvas(Modifier.size(40.dp)) {
                                if (blot > 0f) {
                                    val r = size.minDimension / 2 * blot
                                    drawCircle(v.accent, r)
                                    drawCircle(v.accent.copy(alpha = 0.35f), r * 0.42f, center + Offset(r * 0.78f, -r * 0.62f))
                                }
                            }
                            Icon(spec.icon, spec.label, tint = dockInk, modifier = Modifier.size(21.dp))
                        }
                        Text(
                            spec.label, color = dockInk.copy(alpha = if (active) 1f else 0.6f),
                            fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(34.dp).background(dockInk.copy(alpha = 0.2f)))
                DockAction(Icons.AutoMirrored.Filled.Undo, "Undo", vm.undoCount > 0, dockInk) { vm.undo() }
                DockAction(Icons.AutoMirrored.Filled.Redo, "Redo", vm.redoCount > 0, dockInk) { vm.redo() }
            }
        }
    }
}

private fun Color.luminanceIsLight() = (red * 0.299f + green * 0.587f + blue * 0.114f) > 0.5f

@Composable
private fun DockAction(icon: ImageVector, desc: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(21.dp)) }
}

@Composable
private fun OptionsTray(vm: ViewerViewModel) {
    val v = LocalVellum.current
    Surface(
        shape = RoundedCornerShape(24.dp), color = v.sheet, shadowElevation = 12.dp,
        border = BorderStroke(1.dp, v.rule.copy(alpha = 0.6f)),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (vm.tool) {
                Tool.SIGNATURE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap to place your signature, drag it to move it.", style = Marginalia, color = v.inkSoft, modifier = Modifier.weight(1f))
                    Text(
                        "Redraw", color = v.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.showSignaturePad = true }.padding(8.dp)
                    )
                }
                Tool.ERASER -> Text("Tap or sweep across new marks to lift them off.", style = Marginalia, color = v.inkSoft)
                Tool.MOVE -> Text("Drag any new mark to move it. Tap text to rewrite it.", style = Marginalia, color = v.inkSoft)
                else -> {
                    if (vm.tool == Tool.SHAPE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            listOf(
                                ShapeKind.RECT to Icons.Default.CropSquare,
                                ShapeKind.ELLIPSE to Icons.Default.RadioButtonUnchecked,
                                ShapeKind.LINE to Icons.Default.HorizontalRule,
                                ShapeKind.ARROW to Icons.Default.NorthEast,
                            ).forEach { (k, ic) ->
                                val sel = vm.shapeKind == k
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) v.accentSoft else Color.Transparent)
                                        .clickable { vm.shapeKind = k },
                                    contentAlignment = Alignment.Center
                                ) { Icon(ic, k.name, tint = if (sel) v.accent else v.ink) }
                            }
                        }
                    }
                    if (vm.tool == Tool.TEXT) {
                        Text("Tap to write · drag text to move it · tap text to edit.", style = Marginalia, color = v.inkSoft, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        INK_PALETTE.forEach { c -> InkDrop(Color(c), vm.color == c) { vm.color = c } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.tool == Tool.TEXT) "Aa" else "Nib", style = Marginalia, color = v.inkSoft, modifier = Modifier.width(34.dp))
                        Slider(
                            value = vm.size, onValueChange = { vm.size = it },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = v.accent, activeTrackColor = v.accent, inactiveTrackColor = v.rule)
                        )
                        val preview = with(LocalDensity.current) { (vm.strokeWidthNorm() * 360.dp.toPx()).coerceIn(2f, 30f).toDp() }
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(preview).clip(CircleShape).background(Color(vm.color).copy(alpha = if (vm.tool == Tool.HIGHLIGHTER) 0.5f else 1f)))
                        }
                    }
                }
            }
        }
    }
}

/** A paint swatch drawn as an ink drop (teardrop), not a plain circle. */
@Composable
private fun InkDrop(color: Color, selected: Boolean, onClick: () -> Unit) {
    val v = LocalVellum.current
    val lift by animateDpAsState(if (selected) (-4).dp else 0.dp, label = "lift")
    Canvas(
        Modifier
            .offset(y = lift)
            .size(30.dp, 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        val w = size.width; val h = size.height
        val r = w * 0.36f
        val cx = w / 2; val cy = h - r - 3f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, 3f)
            cubicTo(cx + r * 0.2f, cy - r * 1.1f, cx + r, cy - r * 0.6f, cx + r, cy)
            cubicTo(cx + r, cy + r * 1.33f, cx - r, cy + r * 1.33f, cx - r, cy)
            cubicTo(cx - r, cy - r * 0.6f, cx - r * 0.2f, cy - r * 1.1f, cx, 3f)
            close()
        }
        drawPath(path, color)
        drawPath(path, v.rule, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        if (selected) drawPath(path, v.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
    }
}

/* ------------------------------------------------------------------ */
/*  Page spine — a draggable scrubber on the right edge                */
/* ------------------------------------------------------------------ */

@Composable
fun PageSpine(current: Int, count: Int, visible: Boolean, onScrub: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val v = LocalVellum.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val show = visible || dragging
    val alpha by animateFloatAsState(if (show) 1f else 0f, label = "spine")
    if (alpha == 0f) return
    BoxWithConstraints(modifier.width(56.dp).fillMaxHeight().graphicsLayer { this.alpha = alpha }) {
        val trackH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val thumbH = with(density) { 36.dp.toPx() }
        val frac = if (dragging) dragFrac else current.toFloat() / (count - 1)
        val y = ((trackH - thumbH) * frac).roundToInt()
        // hairline track
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 10.dp).width(2.dp).fillMaxHeight()
                .background(v.ink.copy(alpha = 0.10f * alpha))
        )
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(40.dp).pointerInput(count) {
                fun toPage(py: Float): Int {
                    dragFrac = ((py - thumbH / 2) / (trackH - thumbH)).coerceIn(0f, 1f)
                    return (dragFrac * (count - 1)).roundToInt()
                }
                detectDragGestures(
                    onDragStart = { dragging = true; onScrub(toPage(it.y)) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { ch, _ -> ch.consume(); onScrub(toPage(ch.position.y)) }
            }
        )
        // Thumb: plain Box (no pointer handlers) so drags fall through to the scrub area beneath.
        val thumbShape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 4.dp, bottomEnd = 4.dp)
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.height(36.dp)
                .shadow(6.dp, thumbShape)
                .background(if (dragging) v.accent else v.ink, thumbShape),
            contentAlignment = Alignment.Center
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${current + 1}", color = if (dragging) Color.White else v.sheet,
                    fontFamily = Display, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
                if (dragging) Text(" / $count", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
            }
        }
    }
}


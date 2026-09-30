package app.vellum.ui.viewer

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.vellum.pdf.DocInfo
import app.vellum.pdf.FieldType
import app.vellum.pdf.FormField
import app.vellum.ui.theme.LocalVellum
import app.vellum.ui.theme.Marginalia
import app.vellum.util.FileUtils
import kotlin.math.max
import kotlin.math.min

/** Shared dialog chrome: serif title, marginalia subtitle, vermilion confirm. */
@Composable
fun VellumDialog(
    title: String,
    subtitle: String? = null,
    confirm: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    extraAction: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val v = LocalVellum.current
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = properties,
        containerColor = v.sheet,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = v.ink)
                if (subtitle != null) Text(subtitle, style = Marginalia, color = v.inkSoft, modifier = Modifier.padding(top = 4.dp))
            }
        },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm, color = if (confirmEnabled) v.accent else v.inkSoft) }
        },
        dismissButton = {
            Row {
                extraAction?.invoke()
                TextButton(onClick = onDismiss) { Text(dismiss, color = v.inkSoft) }
            }
        }
    )
}

@Composable
private fun field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true, password: Boolean = false, number: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = singleLine, shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            number -> KeyboardType.Number; password -> KeyboardType.Password; else -> KeyboardType.Text
        }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

@Composable
fun GoToPageDialog(count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf("") }
    val n = t.toIntOrNull()
    val ok = n != null && n in 1..count
    VellumDialog("Turn to page", "1 – $count", "Go", ok, { onGo(n!! - 1) }, onDismiss) {
        field(t, { t = it.filter(Char::isDigit).take(6) }, "Page number", number = true)
    }
}

@Composable
fun TextInputDialog(onDone: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var t by remember { mutableStateOf(initial) }
    val editing = initial.isNotEmpty()
    VellumDialog(
        if (editing) "Rewrite text" else "Write on the page",
        if (editing) "Clear it all to remove this text" else "Colour and size come from the tray",
        if (editing) "Update" else "Place", editing || t.isNotBlank(), { onDone(t) }, onDismiss
    ) {
        field(t, { t = it }, "Text", singleLine = false)
    }
}

@Composable
fun PasswordDialog(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    VellumDialog(
        "This one's locked", if (wrong) "That password didn't fit. Try again." else name,
        "Unlock", pw.isNotEmpty(), { onSubmit(pw) }, onDismiss
    ) { field(pw, { pw = it }, "Password", password = true) }
}

@Composable
fun WatermarkDialog(onApply: (String, Int, Float, Float) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.18f) }
    var scale by remember { mutableFloatStateOf(0.8f) }
    val colors = listOf(0xFFD9480F.toInt(), 0xFF1C2533.toInt(), 0xFF1F5FBF.toInt(), 0xFF8A8478.toInt())
    var color by remember { mutableIntStateOf(colors[0]) }
    VellumDialog("Stamp a watermark", "Set diagonally across every page", "Stamp", text.isNotBlank(), { onApply(text, color, opacity, scale) }, onDismiss) {
        Column {
            field(text, { text = it }, "Text")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                colors.forEach { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .border(3.dp, if (color == c) v.accent.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                            .clickable { color = c }
                    )
                }
            }
            LabeledSlider("Opacity", opacity, 0.05f..0.6f) { opacity = it }
            LabeledSlider("Size", scale, 0.3f..1.2f) { scale = it }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val v = LocalVellum.current
    Column {
        Text(label, style = Marginalia, color = v.inkSoft)
        Slider(
            value, onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = v.accent, activeTrackColor = v.accent, inactiveTrackColor = v.rule)
        )
    }
}

@Composable
fun ProtectDialog(onApply: (String, String, Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(false) }
    val ok = pw.length >= 4 && pw == confirm
    VellumDialog(
        "Lock a copy", "Saves a password-protected copy (AES-128). Your open document stays editable.",
        "Save locked copy", ok, { onApply(pw, owner, print, copy) }, onDismiss
    ) {
        Column {
            field(pw, { pw = it }, "Password to open (min 4)", password = true)
            field(confirm, { confirm = it }, "Repeat password", password = true)
            if (confirm.isNotEmpty() && confirm != pw) Text("Passwords don't match", color = v.accent, style = Marginalia)
            field(owner, { owner = it }, "Owner password (optional)", password = true)
            CheckRow("Allow printing", print) { print = it }
            CheckRow("Allow copying text", copy) { copy = it }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val v = LocalVellum.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = v.accent))
        Text(label, color = v.ink)
    }
}

@Composable
fun InfoDialog(info: DocInfo, onSave: (DocInfo) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    var title by remember { mutableStateOf(info.title) }
    var author by remember { mutableStateOf(info.author) }
    var subject by remember { mutableStateOf(info.subject) }
    var keywords by remember { mutableStateOf(info.keywords) }
    VellumDialog(
        "Colophon",
        "${info.pages} pages · PDF ${info.version} · ${FileUtils.formatSize(info.fileSize)}",
        "Save", true, { onSave(info.copy(title = title, author = author, subject = subject, keywords = keywords)) }, onDismiss
    ) {
        Column(Modifier.heightIn(max = 460.dp)) {
            LazyColumn {
                item {
                    field(title, { title = it }, "Title")
                    field(author, { author = it }, "Author")
                    field(subject, { subject = it }, "Subject")
                    field(keywords, { keywords = it }, "Keywords")
                    if (info.creator.isNotBlank()) Text("Made with ${info.creator}", style = Marginalia, color = v.inkSoft, modifier = Modifier.padding(top = 6.dp))
                    if (info.producer.isNotBlank()) Text("Produced by ${info.producer}", style = Marginalia, color = v.inkSoft)
                }
            }
        }
    }
}

@Composable
fun FormDialog(fields: List<FormField>, onSave: (Map<String, String>, Boolean) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.name, it.value) } } }
    var flatten by remember { mutableStateOf(false) }
    VellumDialog(
        "Fill in the form", "${fields.size} fields", "Apply", true,
        { onSave(values.filter { (k, value) -> fields.first { it.name == k }.value != value }, flatten) }, onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(fields, key = { it.name }) { f ->
                    val label = f.name.substringAfterLast('.')
                    val value = values[f.name] ?: ""
                    when (f.type) {
                        FieldType.TEXT -> OutlinedTextField(
                            value, { values[f.name] = it }, label = { Text(label) }, enabled = !f.readOnly,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        )
                        FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(label, modifier = Modifier.weight(1f), color = v.ink)
                            Switch(
                                value == "true", { values[f.name] = it.toString() }, enabled = !f.readOnly,
                                colors = SwitchDefaults.colors(checkedTrackColor = v.accent)
                            )
                        }
                        FieldType.CHOICE, FieldType.RADIO -> ChoiceRow(label, value, f.options, !f.readOnly) { values[f.name] = it }
                    }
                }
            }
            CheckRow("Flatten (make fields permanent)", flatten) { flatten = it }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    val v = LocalVellum.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = v.ink)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, v.rule)) {
                Text(value.takeIf { it.isNotBlank() && it != "Off" } ?: "Choose", color = v.ink)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
            }
        }
    }
}

@Composable
fun ConfirmCloseDialog(onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    VellumDialog(
        "Keep your changes?", "Unsaved marks and edits will be lost otherwise.",
        "Save", true, onSave, onDismiss,
        extraAction = { TextButton(onClick = onDiscard) { Text("Discard", color = v.inkSoft) } }
    ) { }
}

@Composable
fun SignaturePadDialog(inkColor: Int, onDone: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val v = LocalVellum.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    VellumDialog(
        "Your signature", "Sign above the line", "Use it", strokes.isNotEmpty(),
        { onDone(renderSignature(strokes, inkColor)) }, onDismiss,
        extraAction = { TextButton(onClick = { strokes.clear() }) { Text("Clear", color = v.inkSoft) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFBF8F2))
                .border(1.dp, v.rule, RoundedCornerShape(18.dp))
                .onSizeChanged { padSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current.clear(); current.add(it) },
                        onDragEnd = { if (current.isNotEmpty()) strokes.add(current.toList()); current.clear() },
                        onDragCancel = { if (current.isNotEmpty()) strokes.add(current.toList()); current.clear() },
                    ) { ch, _ -> ch.consume(); current.add(ch.position) }
                }
        ) {
            val baseY = size.height * 0.74f
            drawLine(Color(0xFF8A8478), Offset(24f, baseY), Offset(size.width - 24f, baseY), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            drawLine(Color(0xFFD9480F), Offset(24f, baseY - 22f), Offset(46f, baseY), 3f)
            drawLine(Color(0xFFD9480F), Offset(46f, baseY - 22f), Offset(24f, baseY), 3f)
            (strokes + listOf(current.toList())).forEach { pts ->
                if (pts.size > 1) {
                    val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(p, Color(inkColor), style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

private fun renderSignature(strokes: List<List<Offset>>, color: Int): Bitmap {
    val all = strokes.flatten()
    val pad = 12f
    val minX = all.minOf { it.x } - pad; val minY = all.minOf { it.y } - pad
    val maxX = all.maxOf { it.x } + pad; val maxY = all.maxOf { it.y } + pad
    val scale = 2f
    val w = max(1, ((maxX - minX) * scale).toInt()); val h = max(1, ((maxY - minY) * scale).toInt())
    val bmp = Bitmap.createBitmap(min(w, 3000), min(h, 1500), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 7f * scale
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { pts ->
        val path = android.graphics.Path()
        pts.forEachIndexed { i, p ->
            val x = (p.x - minX) * scale; val y = (p.y - minY) * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (pts.size == 1) path.lineTo((pts[0].x - minX) * scale + 0.5f, (pts[0].y - minY) * scale)
        c.drawPath(path, paint)
    }
    return bmp
}

@Suppress("unused")
private fun Color.argb() = toArgb()

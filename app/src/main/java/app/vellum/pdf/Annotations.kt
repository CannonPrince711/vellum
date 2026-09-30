package app.vellum.pdf

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ShapeKind { RECT, ELLIPSE, LINE, ARROW }

/**
 * Pending (not yet written) markup. All coordinates are normalised to the displayed page:
 * x in 0..1 of page width, y in 0..1 of page height (top-left origin). Widths/sizes are relative to page width.
 */
sealed class Annot {
    abstract val id: Long
    abstract val page: Int
}

data class InkAnnot(
    override val id: Long, override val page: Int,
    val points: List<Offset>, val color: Int, val width: Float, val highlighter: Boolean
) : Annot()

data class ShapeAnnot(
    override val id: Long, override val page: Int,
    val kind: ShapeKind, val start: Offset, val end: Offset, val color: Int, val width: Float
) : Annot()

data class TextAnnot(
    override val id: Long, override val page: Int,
    val pos: Offset, val text: String, val color: Int, val size: Float
) : Annot()

data class ImageAnnot(
    override val id: Long, override val page: Int,
    val pos: Offset, val w: Float, val h: Float, val bitmap: Bitmap
) : Annot()

/** Returns a copy of this mark shifted by (dx, dy) in normalised page units, kept on the page. */
fun Annot.translated(dx: Float, dy: Float): Annot {
    fun o(p: Offset) = Offset(p.x + dx, p.y + dy)
    return when (this) {
        is InkAnnot -> copy(points = points.map(::o))
        is ShapeAnnot -> copy(start = o(start), end = o(end))
        is TextAnnot -> copy(pos = Offset((pos.x + dx).coerceIn(0f, 0.98f), (pos.y + dy).coerceIn(0f, 0.98f)))
        is ImageAnnot -> copy(pos = Offset((pos.x + dx).coerceIn(0f, max(0f, 1f - w)), (pos.y + dy).coerceIn(0f, max(0f, 1f - h))))
    }
}

object AnnotGeometry {
    const val ARROW_ANGLE = 0.45f

    /** Approximate bounding box in pixels for a page drawn at w x h. */
    fun bounds(a: Annot, w: Float, h: Float): androidx.compose.ui.geometry.Rect {
        fun px(o: Offset) = Offset(o.x * w, o.y * h)
        return when (a) {
            is InkAnnot -> {
                val pts = a.points.map(::px)
                val r = a.width * w / 2
                androidx.compose.ui.geometry.Rect(
                    pts.minOf { it.x } - r, pts.minOf { it.y } - r, pts.maxOf { it.x } + r, pts.maxOf { it.y } + r
                )
            }
            is ShapeAnnot -> {
                val s = px(a.start); val e = px(a.end); val r = a.width * w / 2
                androidx.compose.ui.geometry.Rect(min(s.x, e.x) - r, min(s.y, e.y) - r, max(s.x, e.x) + r, max(s.y, e.y) + r)
            }
            is TextAnnot -> {
                val lines = a.text.split('\n')
                val fs = a.size * w
                val o = px(a.pos)
                androidx.compose.ui.geometry.Rect(
                    o.x, o.y,
                    o.x + (lines.maxOfOrNull { it.length } ?: 1) * fs * 0.6f,
                    o.y + lines.size * fs * 1.2f
                )
            }
            is ImageAnnot -> {
                val o = px(a.pos)
                androidx.compose.ui.geometry.Rect(o.x, o.y, o.x + a.w * w, o.y + a.h * h)
            }
        }
    }

    /** Returns the two arrow-head end points for a line s->e with head length len. */
    fun arrowHead(s: Offset, e: Offset, len: Float): Pair<Offset, Offset> {
        val a = atan2(e.y - s.y, e.x - s.x)
        val p1 = Offset(e.x - len * cos(a - ARROW_ANGLE), e.y - len * sin(a - ARROW_ANGLE))
        val p2 = Offset(e.x - len * cos(a + ARROW_ANGLE), e.y - len * sin(a + ARROW_ANGLE))
        return p1 to p2
    }

    fun hit(a: Annot, p: Offset, w: Float, h: Float, tol: Float): Boolean {
        fun px(o: Offset) = Offset(o.x * w, o.y * h)
        return when (a) {
            is InkAnnot -> {
                val r = tol + a.width * w / 2
                val pts = a.points.map(::px)
                if (pts.size == 1) dist(pts[0], p) < r else pts.zipWithNext().any { (s, e) -> segDist(p, s, e) < r }
            }
            is ShapeAnnot -> {
                val s = px(a.start); val e = px(a.end); val r = tol + a.width * w / 2
                when (a.kind) {
                    ShapeKind.LINE, ShapeKind.ARROW -> segDist(p, s, e) < r
                    else -> p.x in (min(s.x, e.x) - r)..(max(s.x, e.x) + r) && p.y in (min(s.y, e.y) - r)..(max(s.y, e.y) + r)
                }
            }
            is TextAnnot -> {
                val lines = a.text.split('\n')
                val fs = a.size * w
                val tw = (lines.maxOfOrNull { it.length } ?: 1) * fs * 0.6f
                val th = lines.size * fs * 1.2f
                val o = px(a.pos)
                p.x in (o.x - tol)..(o.x + tw + tol) && p.y in (o.y - tol)..(o.y + th + tol)
            }
            is ImageAnnot -> {
                val o = px(a.pos)
                p.x in o.x..(o.x + a.w * w) && p.y in o.y..(o.y + a.h * h)
            }
        }
    }

    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)

    private fun segDist(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return dist(p, Offset(a.x + t * dx, a.y + t * dy))
    }
}

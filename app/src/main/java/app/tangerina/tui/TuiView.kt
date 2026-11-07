package app.tangerina.tui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * View que desenha um [Grid] de caracteres monoespaçados com aparência de terminal.
 * Caracteres de desenho de caixa e blocos são desenhados vetorialmente para
 * que as linhas fiquem contínuas independentemente da fonte do aparelho.
 */
class TuiView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onGrid(cols: Int, rows: Int)
        fun onTap(cx: Int, cy: Int)
        fun onLong(cx: Int, cy: Int)
        fun onDrag(dx: Float, dy: Float)
        fun onScale(f: Float)
    }

    val grid = Grid()
    var listener: Listener? = null

    var fontSp = 15f
    var scanlines = true
    var glow = true
    var night = false

    var cellW = 10f; private set
    var cellH = 20f; private set
    private var base = 15f
    private var offX = 0f
    private var offY = 0f

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val fp = Paint().apply { style = Paint.Style.FILL }
    private val sp = Paint().apply { style = Paint.Style.FILL }

    init {
        isHapticFeedbackEnabled = true
        metrics()
    }

    private fun metrics() {
        tp.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics)
        cellW = tp.measureText("M")
        val fm = tp.fontMetrics
        val th = fm.descent - fm.ascent
        cellH = ceil(th * 1.05f)
        base = -fm.ascent + (cellH - th) / 2f
    }

    fun applyFont() {
        metrics()
        relayout(width, height)
        invalidate()
    }

    private fun relayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val c = max(12, (w / cellW).toInt())
        val r = max(10, (h / cellH).toInt())
        offX = (w - c * cellW) / 2f
        offY = (h - r * cellH) / 2f
        grid.resize(c, r)
        listener?.onGrid(c, r)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        metrics(); relayout(w, h)
    }

    private fun tc(c: Int): Int {
        if (!night) return c
        val r = ((c shr 16) and 255) * 55 / 100
        val g = ((c shr 8) and 255) * 55 / 100
        val b = (c and 255) * 55 / 100
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    override fun onDraw(canvas: Canvas) {
        val g = grid
        canvas.drawColor(tc(P.BG))
        val cols = g.cols
        // fundos (agrupados em faixas)
        for (y in 0 until g.rows) {
            var x = 0
            val row = y * cols
            while (x < cols) {
                val b = g.bg[row + x]
                var e = x + 1
                while (e < cols && g.bg[row + e] == b) e++
                if (b != P.BG) {
                    fp.color = tc(b)
                    canvas.drawRect(offX + x * cellW, offY + y * cellH, offX + e * cellW + 0.6f, offY + (y + 1) * cellH + 0.6f, fp)
                }
                x = e
            }
        }
        // glifos
        var last = 0
        val thin = max(1.5f, cellW * 0.13f)
        val glowR = cellW * 0.45f
        for (y in 0 until g.rows) {
            val y0 = offY + y * cellH
            for (x in 0 until cols) {
                val i = y * cols + x
                val c = g.ch[i]
                if (c == ' ') continue
                val col = tc(g.fg[i])
                if (col != last) {
                    last = col
                    tp.color = col; lp.color = col
                    if (glow) {
                        val sh = (col and 0x00FFFFFF) or 0x77000000
                        tp.setShadowLayer(glowR, 0f, 0f, sh)
                        lp.setShadowLayer(glowR, 0f, 0f, sh)
                    } else {
                        tp.clearShadowLayer(); lp.clearShadowLayer()
                    }
                }
                val x0 = offX + x * cellW
                if (!special(canvas, c, x0, y0, col, thin)) {
                    canvas.drawText(g.ch, i, 1, x0, y0 + base, tp)
                }
            }
        }
        if (scanlines) {
            sp.color = 0x38000000
            val step = max(3f, cellH / 6f)
            var yy = 0f
            val hh = height.toFloat()
            val ww = width.toFloat()
            while (yy < hh) {
                canvas.drawRect(0f, yy, ww, yy + step * 0.4f, sp)
                yy += step
            }
        }
    }

    /** Pesos u,d,l,r: 0 nenhum, 1 fino, 2 grosso, 3 duplo. */
    private fun boxSpec(c: Char): Int = when (c) {
        '─' -> w(0, 0, 1, 1); '│' -> w(1, 1, 0, 0)
        '┌', '╭' -> w(0, 1, 0, 1); '┐', '╮' -> w(0, 1, 1, 0)
        '└', '╰' -> w(1, 0, 0, 1); '┘', '╯' -> w(1, 0, 1, 0)
        '├' -> w(1, 1, 0, 1); '┤' -> w(1, 1, 1, 0)
        '┬' -> w(0, 1, 1, 1); '┴' -> w(1, 0, 1, 1)
        '┼' -> w(1, 1, 1, 1)
        '━' -> w(0, 0, 2, 2); '┃' -> w(2, 2, 0, 0); '╋' -> w(2, 2, 2, 2)
        '═' -> w(0, 0, 3, 3); '║' -> w(3, 3, 0, 0); '╬' -> w(3, 3, 3, 3)
        '╔' -> w(0, 3, 0, 3); '╗' -> w(0, 3, 3, 0); '╚' -> w(3, 0, 0, 3); '╝' -> w(3, 0, 3, 0)
        '╪' -> w(1, 1, 3, 3); '╫' -> w(3, 3, 1, 1)
        else -> -1
    }

    private fun w(u: Int, d: Int, l: Int, r: Int) = (u shl 6) or (d shl 4) or (l shl 2) or r

    private fun special(cv: Canvas, c: Char, x0: Float, y0: Float, col: Int, thin: Float): Boolean {
        val x1 = x0 + cellW; val y1 = y0 + cellH
        when (c) {
            '█' -> { fp.color = col; cv.drawRect(x0, y0, x1 + 0.6f, y1 + 0.6f, fp); return true }
            '▀' -> { fp.color = col; cv.drawRect(x0, y0, x1 + 0.6f, y0 + cellH / 2, fp); return true }
            '▄' -> { fp.color = col; cv.drawRect(x0, y0 + cellH / 2, x1 + 0.6f, y1 + 0.6f, fp); return true }
            '▌' -> { fp.color = col; cv.drawRect(x0, y0, x0 + cellW / 2, y1 + 0.6f, fp); return true }
            '▐' -> { fp.color = col; cv.drawRect(x0 + cellW / 2, y0, x1 + 0.6f, y1 + 0.6f, fp); return true }
            '░', '▒', '▓' -> {
                val a = when (c) { '░' -> 0x40; '▒' -> 0x80; else -> 0xC0 }
                fp.color = (col and 0x00FFFFFF) or (a shl 24)
                cv.drawRect(x0, y0, x1 + 0.6f, y1 + 0.6f, fp); return true
            }
            '╱' -> { lp.strokeWidth = thin; cv.drawLine(x0, y1, x1, y0, lp); return true }
            '╲' -> { lp.strokeWidth = thin; cv.drawLine(x0, y0, x1, y1, lp); return true }
        }
        val s = boxSpec(c)
        if (s < 0) return false
        val cx = x0 + cellW / 2; val cy = y0 + cellH / 2
        val u = (s shr 6) and 3; val d = (s shr 4) and 3; val l = (s shr 2) and 3; val r = s and 3
        val o = thin * 1.2f
        fun seg(wt: Int, ax: Float, ay: Float, bx: Float, by: Float, vertical: Boolean) {
            when (wt) {
                1 -> { lp.strokeWidth = thin; cv.drawLine(ax, ay, bx, by, lp) }
                2 -> { lp.strokeWidth = thin * 2.3f; cv.drawLine(ax, ay, bx, by, lp) }
                3 -> {
                    lp.strokeWidth = thin * 0.85f
                    if (vertical) {
                        cv.drawLine(ax - o, ay, bx - o, by, lp); cv.drawLine(ax + o, ay, bx + o, by, lp)
                    } else {
                        cv.drawLine(ax, ay - o, bx, by - o, lp); cv.drawLine(ax, ay + o, bx, by + o, lp)
                    }
                }
            }
        }
        if (u > 0) seg(u, cx, y0, cx, cy, true)
        if (d > 0) seg(d, cx, cy, cx, y1, true)
        if (l > 0) seg(l, x0, cy, cx, cy, false)
        if (r > 0) seg(r, cx, cy, x1, cy, false)
        return true
    }

    // ---------------------------------------------------------------- toque

    private fun col(px: Float) = ((px - offX) / cellW).toInt()
    private fun row(py: Float) = ((py - offY) / cellH).toInt()
    private var scaling = false

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            listener?.onTap(col(e.x), row(e.y)); return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onLong(col(e.x), row(e.y))
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaling && e2.pointerCount == 1) listener?.onDrag(distanceX, distanceY)
            return true
        }
    })

    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { listener?.onScale(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return true
    }
}

package app.tangerina.tui

/** Valor sentinela: manter a cor atual da célula. */
const val KEEP = 1

/** Buffer de caracteres (como uma tela de terminal). */
class Grid {
    var cols = 0; private set
    var rows = 0; private set
    var ch = CharArray(0); private set
    var fg = IntArray(0); private set
    var bg = IntArray(0); private set

    // Traços girados (ruas): ângulo em graus (NaN = célula comum) e deslocamento
    // do ponto por onde a linha passa, em frações da célula a partir do centro.
    // Um segundo traço por célula permite cruzamentos e curvas.
    var ang = FloatArray(0); private set
    var ox = FloatArray(0); private set
    var oy = FloatArray(0); private set
    var ch2 = CharArray(0); private set
    var fg2 = IntArray(0); private set
    var ang2 = FloatArray(0); private set
    var ox2 = FloatArray(0); private set
    var oy2 = FloatArray(0); private set

    fun resize(c: Int, r: Int) {
        if (c == cols && r == rows) return
        cols = c; rows = r
        val n = c * r
        ch = CharArray(n); fg = IntArray(n); bg = IntArray(n)
        ang = FloatArray(n); ox = FloatArray(n); oy = FloatArray(n)
        ch2 = CharArray(n); fg2 = IntArray(n); ang2 = FloatArray(n); ox2 = FloatArray(n); oy2 = FloatArray(n)
        clear()
    }

    fun clear(f: Int = P.FG, b: Int = P.BG) {
        ch.fill(' '); fg.fill(f); bg.fill(b)
        ang.fill(Float.NaN); ang2.fill(Float.NaN)
    }

    fun hasStroke(x: Int, y: Int) = ok(x, y) && !ang[y * cols + x].isNaN()
    fun hasStroke2(x: Int, y: Int) = ok(x, y) && !ang2[y * cols + x].isNaN()
    fun strokeAng(x: Int, y: Int) = ang[y * cols + x]

    fun setStroke(x: Int, y: Int, c: Char, f: Int, a: Float, dx: Float, dy: Float) {
        if (!ok(x, y)) return
        val i = y * cols + x
        ch[i] = c; fg[i] = f; ang[i] = a; ox[i] = dx; oy[i] = dy
    }

    fun setStroke2(x: Int, y: Int, c: Char, f: Int, a: Float, dx: Float, dy: Float) {
        if (!ok(x, y)) return
        val i = y * cols + x
        ch2[i] = c; fg2[i] = f; ang2[i] = a; ox2[i] = dx; oy2[i] = dy
    }

    /** Move o traço principal para o segundo slot. */
    fun pushDown(x: Int, y: Int) {
        if (!ok(x, y)) return
        val i = y * cols + x
        ch2[i] = ch[i]; fg2[i] = fg[i]; ang2[i] = ang[i]; ox2[i] = ox[i]; oy2[i] = oy[i]
    }

    fun ok(x: Int, y: Int) = x in 0 until cols && y in 0 until rows

    fun get(x: Int, y: Int): Char = if (ok(x, y)) ch[y * cols + x] else ' '

    fun set(x: Int, y: Int, c: Char, f: Int = KEEP, b: Int = KEEP) {
        if (!ok(x, y)) return
        val i = y * cols + x
        ch[i] = c
        ang[i] = Float.NaN; ang2[i] = Float.NaN
        if (f != KEEP) fg[i] = f
        if (b != KEEP) bg[i] = b
    }

    fun text(x: Int, y: Int, s: String, f: Int = P.FG, b: Int = KEEP, max: Int = Int.MAX_VALUE): Int {
        var cx = x
        for ((n, c) in s.withIndex()) {
            if (n >= max) break
            set(cx, y, c, f, b); cx++
        }
        return cx
    }

    fun textR(xRight: Int, y: Int, s: String, f: Int = P.FG, b: Int = KEEP) =
        text(xRight - s.length + 1, y, s, f, b)

    fun center(y: Int, s: String, f: Int = P.FG, b: Int = KEEP, x0: Int = 0, w: Int = cols) {
        val t = if (s.length > w) s.substring(0, w) else s
        text(x0 + (w - t.length) / 2, y, t, f, b)
    }

    fun hline(x: Int, y: Int, w: Int, c: Char = '─', f: Int = P.DIM) {
        for (i in 0 until w) set(x + i, y, c, f)
    }

    fun vline(x: Int, y: Int, h: Int, c: Char = '│', f: Int = P.DIM) {
        for (i in 0 until h) set(x, y + i, c, f)
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, c: Char = ' ', f: Int = KEEP, b: Int = KEEP) {
        for (j in 0 until h) for (i in 0 until w) set(x + i, y + j, c, f, b)
    }

    fun box(x: Int, y: Int, w: Int, h: Int, f: Int = P.DIM, title: String? = null, tf: Int = P.HI) {
        if (w < 2 || h < 2) return
        hline(x + 1, y, w - 2, '─', f); hline(x + 1, y + h - 1, w - 2, '─', f)
        vline(x, y + 1, h - 2, '│', f); vline(x + w - 1, y + 1, h - 2, '│', f)
        set(x, y, '┌', f); set(x + w - 1, y, '┐', f)
        set(x, y + h - 1, '└', f); set(x + w - 1, y + h - 1, '┘', f)
        if (title != null && w > 6) {
            val t = " $title "
            set(x + 1, y, '┤', f)
            text(x + 2, y, t, tf, max = w - 5)
            set(x + 2 + minOf(t.length, w - 5), y, '├', f)
        }
    }

    /** Linha de separação com título dentro de uma caixa. */
    fun rule(x: Int, y: Int, w: Int, title: String? = null, f: Int = P.DIM, tf: Int = P.HI) {
        set(x, y, '├', f); set(x + w - 1, y, '┤', f)
        hline(x + 1, y, w - 2, '─', f)
        if (title != null) text(x + 2, y, " $title ", tf, max = w - 4)
    }
}

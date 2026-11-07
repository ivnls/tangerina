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

    fun resize(c: Int, r: Int) {
        if (c == cols && r == rows) return
        cols = c; rows = r
        ch = CharArray(c * r); fg = IntArray(c * r); bg = IntArray(c * r)
        clear()
    }

    fun clear(f: Int = P.FG, b: Int = P.BG) {
        ch.fill(' '); fg.fill(f); bg.fill(b)
    }

    fun ok(x: Int, y: Int) = x in 0 until cols && y in 0 until rows

    fun get(x: Int, y: Int): Char = if (ok(x, y)) ch[y * cols + x] else ' '

    fun set(x: Int, y: Int, c: Char, f: Int = KEEP, b: Int = KEEP) {
        if (!ok(x, y)) return
        val i = y * cols + x
        ch[i] = c
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

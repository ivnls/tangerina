package app.tangerina.tui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Rasteriza dados vetoriais OSM numa região do [Grid] como arte ASCII/box-drawing. */
class MapRenderer {
    private lateinit var g: Grid
    private var rx = 0; private var ry = 0; private var w = 0; private var h = 0
    private var scale = 1.0; private var ox = 0.0; private var oy = 0.0
    private var cellW = 1f; private var cellH = 2f
    var zoom = 16.0; private set

    private var prio = IntArray(0)
    private var owner = IntArray(0)
    private var lbl = BooleanArray(0)
    private val seen = HashSet<Long>()
    private val named = HashSet<String>()
    private var px = DoubleArray(512)
    private var py = DoubleArray(512)
    private val xsTmp = ArrayList<Double>()
    private var fid = 0

    var missing = 0; private set
    var tooMany = false; private set

    fun setup(grid: Grid, x: Int, y: Int, width: Int, height: Int,
              lat: Double, lon: Double, z: Double, cw: Float, ch: Float, tilePx: Double) {
        g = grid; rx = x; ry = y; w = width; h = height
        zoom = z; cellW = cw; cellH = ch
        scale = tilePx * 2.0.pow(z)
        ox = Geo.nx(lon) * scale - (w / 2.0) * cellW
        oy = Geo.ny(lat) * scale - (h / 2.0) * cellH
        val n = w * h
        if (prio.size < n) { prio = IntArray(n); owner = IntArray(n); lbl = BooleanArray(n) }
        prio.fill(0, 0, n); owner.fill(0, 0, n); lbl.fill(false, 0, n)
    }

    fun colOf(nx: Double) = rx + (nx * scale - ox) / cellW
    fun rowOf(ny: Double) = ry + (ny * scale - oy) / cellH
    fun nxOfCol(c: Double) = (ox + (c - rx) * cellW) / scale
    fun nyOfRow(r: Double) = (oy + (r - ry) * cellH) / scale
    fun inside(c: Int, r: Int) = c >= rx && c < rx + w && r >= ry && r < ry + h

    // -------------------------------------------------------------- dados

    fun drawData(store: TileStore) {
        seen.clear(); named.clear(); missing = 0
        val n = 1 shl TileStore.Z
        val nx0 = nxOfCol(rx.toDouble()); val nx1 = nxOfCol((rx + w).toDouble())
        val ny0 = nyOfRow(ry.toDouble()); val ny1 = nyOfRow((ry + h).toDouble())
        val tx0 = floor(nx0 * n).toInt().coerceIn(0, n - 1); val tx1 = floor(nx1 * n).toInt().coerceIn(0, n - 1)
        val ty0 = floor(ny0 * n).toInt().coerceIn(0, n - 1); val ty1 = floor(ny1 * n).toInt().coerceIn(0, n - 1)
        val count = (tx1 - tx0 + 1).toLong() * (ty1 - ty0 + 1)
        tooMany = zoom < 13.5 || count > 30
        val tiles = ArrayList<Tile>()
        if (tooMany) {
            for ((k, t) in store.snapshot()) {
                val x = TileStore.kx(k); val y = TileStore.ky(k)
                if (x in tx0..tx1 && y in ty0..ty1) tiles.add(t)
            }
        } else {
            val want = HashSet<Long>()
            // do centro para fora, para carregar primeiro o que está no meio da tela
            val cx = (tx0 + tx1) / 2.0; val cy = (ty0 + ty1) / 2.0
            val order = ArrayList<Long>()
            for (x in tx0..tx1) for (y in ty0..ty1) order.add(TileStore.key(x, y))
            order.sortBy { val dx = TileStore.kx(it) - cx; val dy = TileStore.ky(it) - cy; dx * dx + dy * dy }
            want.addAll(order)
            store.wanted = want
            for (k in order) {
                val t = store.get(TileStore.kx(k), TileStore.ky(k))
                if (t != null) tiles.add(t) else missing++
            }
        }
        drawTiles(tiles)
    }

    fun drawTiles(tiles: List<Tile>) {
        if (tiles.isEmpty()) return
        seen.clear(); named.clear()
        for (kind in 0 until K.POI) {
            if (!visible(kind)) continue
            for (t in tiles) for (f in t.byKind[kind]) {
                if (!cull(f) || !seen.add(f.id)) continue
                fid++
                when (kind) {
                    K.GREEN, K.WATER, K.BUILDING -> area(f)
                    else -> line(f)
                }
            }
        }
        // rótulos: lugares, depois vias
        for (t in tiles) for (f in t.byKind[K.PLACE]) place(f)
        if (zoom >= 14.5) for (kind in intArrayOf(K.MOTORWAY, K.MAJOR, K.MINOR, K.RIVER, K.SERVICE)) {
            if (kind == K.SERVICE && zoom < 17.5) continue
            if (kind == K.MINOR && zoom < 15.5) continue
            for (t in tiles) for (f in t.byKind[kind]) if (f.name != null && cull(f)) roadLabel(f)
        }
        if (zoom >= 16) for (t in tiles) for (f in t.byKind[K.POI]) poi(f)
    }

    private fun visible(kind: Int) = when (kind) {
        K.BUILDING -> zoom >= 16.5
        K.PATH -> zoom >= 16
        K.SERVICE -> zoom >= 15
        K.STREAM -> zoom >= 14.5
        K.MINOR -> zoom >= 14
        else -> true
    }

    private fun cull(f: Feature): Boolean {
        val nx0 = nxOfCol(rx.toDouble()); val nx1 = nxOfCol((rx + w).toDouble())
        val ny0 = nyOfRow(ry.toDouble()); val ny1 = nyOfRow((ry + h).toDouble())
        return !(f.maxX < nx0 || f.minX > nx1 || f.maxY < ny0 || f.minY > ny1)
    }

    private fun project(f: Feature): Int {
        val n = f.xs.size
        if (px.size < n) { px = DoubleArray(n * 2); py = DoubleArray(n * 2) }
        for (i in 0 until n) { px[i] = colOf(f.xs[i]); py[i] = rowOf(f.ys[i]) }
        return n
    }

    // ------------------------------------------------------------- áreas

    private fun area(f: Feature) {
        val n = project(f)
        var mnx = Double.MAX_VALUE; var mxx = -Double.MAX_VALUE; var mny = Double.MAX_VALUE; var mxy = -Double.MAX_VALUE
        for (i in 0 until n) { mnx = min(mnx, px[i]); mxx = max(mxx, px[i]); mny = min(mny, py[i]); mxy = max(mxy, py[i]) }
        val p = when (f.kind) { K.GREEN -> 1; K.WATER -> 2; else -> 3 }
        if (mxx - mnx < 1.0 && mxy - mny < 1.0) {
            if (f.kind == K.WATER) plotArea(floor((mnx + mxx) / 2).toInt(), floor((mny + mxy) / 2).toInt(), f.kind, p)
            return
        }
        val r0 = max(ry, floor(mny).toInt()); val r1 = min(ry + h - 1, ceil(mxy).toInt())
        for (r in r0..r1) {
            val yc = r + 0.5
            xsTmp.clear()
            var j = n - 1
            for (i in 0 until n) {
                val y1 = py[j]; val y2 = py[i]
                if ((y1 <= yc && y2 > yc) || (y2 <= yc && y1 > yc)) {
                    xsTmp.add(px[j] + (yc - y1) / (y2 - y1) * (px[i] - px[j]))
                }
                j = i
            }
            if (xsTmp.size < 2) continue
            xsTmp.sort()
            var k = 0
            while (k + 1 < xsTmp.size) {
                val a = max(rx, ceil(xsTmp[k] - 0.5).toInt())
                val b = min(rx + w - 1, floor(xsTmp[k + 1] - 0.5).toInt())
                for (c in a..b) plotArea(c, r, f.kind, p)
                k += 2
            }
        }
    }

    private fun plotArea(c: Int, r: Int, kind: Int, p: Int) {
        if (!inside(c, r)) return
        val i = (r - ry) * w + (c - rx)
        if (prio[i] >= p) return
        prio[i] = p
        when (kind) {
            K.WATER -> g.set(c, r, if ((c + r * 2) % 4 == 0) '~' else ' ', P.WATER, P.WATER_BG)
            K.GREEN -> g.set(c, r, if ((c * 3 + r * 5) % 7 == 0) '"' else ' ', P.GREEN_FG, P.GREEN_BG)
            else -> g.set(c, r, ' ', KEEP, P.BUILD_BG)
        }
    }

    // ------------------------------------------------------------- linhas

    /** Estilo de via: um único caractere, girado no ângulo real do segmento. */
    private class Style(val c: Char, val color: Int, val p: Int)

    private val styles = arrayOfNulls<Style>(K.COUNT).also {
        it[K.PATH] = Style('·', P.DIM, 4)
        it[K.SERVICE] = Style('─', P.DIM, 5)
        it[K.STREAM] = Style('~', P.WATER, 6)
        it[K.MINOR] = Style('─', P.FG, 7)
        it[K.RAIL] = Style('╪', P.DIM, 8)
        it[K.RIVER] = Style('≈', P.WATER, 9)
        it[K.MAJOR] = Style('━', P.HI, 10)
        it[K.MOTORWAY] = Style('═', P.HI, 11)
    }

    private fun line(f: Feature) {
        val st = styles[f.kind] ?: return
        val n = project(f)
        for (i in 0 until n - 1) seg(px[i], py[i], px[i + 1], py[i + 1], st)
    }

    private fun seg(x1: Double, y1: Double, x2: Double, y2: Double, st: Style) {
        // recorte Liang–Barsky
        val xmin = rx - 1.0; val xmax = rx + w + 1.0; val ymin = ry - 1.0; val ymax = ry + h + 1.0
        val dx = x2 - x1; val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) return
        var t0 = 0.0; var t1 = 1.0
        val pp = doubleArrayOf(-dx, dx, -dy, dy)
        val qq = doubleArrayOf(x1 - xmin, xmax - x1, y1 - ymin, ymax - y1)
        for (k in 0..3) {
            if (pp[k] == 0.0) { if (qq[k] < 0) return }
            else {
                val t = qq[k] / pp[k]
                if (pp[k] < 0) { if (t > t1) return; if (t > t0) t0 = t }
                else { if (t < t0) return; if (t < t1) t1 = t }
            }
        }
        val ax = x1 + t0 * dx; val ay = y1 + t0 * dy
        val bx = x1 + t1 * dx; val by = y1 + t1 * dy
        // ângulo em pixels de tela (células não são quadradas)
        val vx = dx * cellW; val vy = dy * cellH
        val ang = Math.toDegrees(atan2(vy, vx)).toFloat()
        val vv = vx * vx + vy * vy
        val steps = (max(abs(bx - ax), abs(by - ay)) * 4).toInt() + 1
        var lc = Int.MIN_VALUE; var lr = Int.MIN_VALUE
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val c = floor(ax + (bx - ax) * t).toInt(); val r = floor(ay + (by - ay) * t).toInt()
            if (c == lc && r == lr) continue
            lc = c; lr = r
            // ponto do segmento mais próximo do centro da célula
            val cx = c + 0.5; val cy = r + 0.5
            val tt = (((cx - x1) * cellW * vx + (cy - y1) * cellH * vy) / vv).coerceIn(0.0, 1.0)
            // sem limitar a ±0.5: se a via só raspa o canto, o recorte cuida disso
            val ox = (x1 + tt * dx - cx).coerceIn(-1.5, 1.5).toFloat()
            val oy = (y1 + tt * dy - cy).coerceIn(-1.5, 1.5).toFloat()
            plotLine(c, r, st, ang, ox, oy)
        }
    }

    private fun angClose(a: Float, b: Float, lim: Float): Boolean {
        val d = abs(((a - b) % 180f + 180f) % 180f)
        return d < lim || d > 180f - lim
    }

    private fun plotLine(c: Int, r: Int, st: Style, ang: Float, ox: Float, oy: Float) {
        if (!inside(c, r)) return
        val i = (r - ry) * w + (c - rx)
        val p = st.p
        val line = g.hasStroke(c, r)
        val free2 = !g.hasStroke2(c, r)
        if (prio[i] > p || (prio[i] == p && line)) {
            // célula já tem via: vira cruzamento/curva no segundo traço
            // na mesma via só vira 2º traço se a curva for forte (evita "ganchos")
            val lim = if (owner[i] == fid) 30f else 8f
            if (line && free2 && !angClose(g.strokeAng(c, r), ang, lim)) g.setStroke2(c, r, st.c, st.color, ang, ox, oy)
            return
        }
        if (line && free2) g.pushDown(c, r)
        prio[i] = p; owner[i] = fid
        g.setStroke(c, r, st.c, st.color, ang, ox, oy)
    }

    // ------------------------------------------------------------- rótulos

    private fun free(c: Int, r: Int, len: Int): Boolean {
        if (r < ry || r >= ry + h || c - 1 < rx || c + len >= rx + w) return false
        for (k in -1..len) if (lbl[(r - ry) * w + (c + k - rx)]) return false
        return true
    }

    private fun mark(c: Int, r: Int, len: Int) {
        for (k in -1..len) { val cc = c + k; if (inside(cc, r)) lbl[(r - ry) * w + (cc - rx)] = true }
    }

    private fun place(f: Feature) {
        val minZ = when (f.rank) { 0 -> 0.0; 1 -> 11.0; 2 -> 13.0; else -> 15.0 }
        if (zoom < minZ || f.name == null) return
        val name = " ${f.name.uppercase()} "
        val c = floor(colOf(f.xs[0])).toInt() - name.length / 2
        val r = floor(rowOf(f.ys[0])).toInt()
        if (!free(c, r, name.length)) return
        val inv = f.rank <= 1
        g.text(c, r, name, if (inv) P.BG else P.HI, if (inv) P.FG else P.PANEL)
        mark(c, r, name.length)
        for (k in -1..name.length) { val cc = c + k; if (inside(cc, r)) prio[(r - ry) * w + (cc - rx)] = 30 }
    }

    private fun roadLabel(f: Feature) {
        val name = f.name ?: return
        if (named.contains(name)) return
        val n = project(f)
        var bestLen = 0.0; var bmx = 0.0; var bmy = 0.0
        for (i in 0 until n - 1) {
            val dx = px[i + 1] - px[i]; val dy = py[i + 1] - py[i]
            if (abs(dy * cellH) > abs(dx * cellW) * 0.35 || dx == 0.0) continue
            // só o trecho visível do segmento
            val a = max(rx + 1.0, min(px[i], px[i + 1])); val b = min(rx + w - 2.0, max(px[i], px[i + 1]))
            if (b - a <= bestLen) continue
            val m = (a + b) / 2
            val y = py[i] + (m - px[i]) / dx * dy
            if (y < ry || y >= ry + h) continue
            bestLen = b - a; bmx = m; bmy = y
        }
        val text = if (zoom >= 17) name else abbreviate(name)
        if (bestLen < text.length + 2) return
        // desloca o rótulo ao longo do trecho para não alinhar todos na mesma coluna
        val slack = (bestLen - text.length - 2).toInt()
        val off = if (slack > 1) (name.hashCode() and 0x7fffffff) % slack - slack / 2 else 0
        val c = floor(bmx - text.length / 2.0).toInt() + off; val r = floor(bmy).toInt()
        if (!free(c, r, text.length)) return
        g.text(c, r, text, if (f.kind == K.RIVER) P.WATER else P.HI, P.BG)
        mark(c, r, text.length)
        named.add(name)
    }

    private fun abbreviate(s: String): String = s
        .replace("Avenida ", "Av. ").replace("Rua ", "R. ").replace("Rodovia ", "Rod. ")
        .replace("Travessa ", "Tv. ").replace("Alameda ", "Al. ").replace("Estrada ", "Estr. ")
        .replace("Praça ", "Pç. ").replace("Street", "St").replace("Avenue", "Av").replace("Road", "Rd")

    private fun poi(f: Feature) {
        val c = floor(colOf(f.xs[0])).toInt(); val r = floor(rowOf(f.ys[0])).toInt()
        if (!inside(c, r)) return
        val i = (r - ry) * w + (c - rx)
        if (prio[i] >= 15 || lbl[i]) return
        prio[i] = 15
        g.set(c, r, f.sym, P.HI, P.PANEL)
        val name = f.name
        if (zoom >= 18 && name != null) {
            val t = if (name.length > 18) name.take(17) + "…" else name
            if (free(c + 2, r, t.length)) { g.text(c + 2, r, t, P.DIM, P.BG); mark(c + 2, r, t.length) }
        }
    }

    // ---------------------------------------------------- overlays (app)

    fun plotOver(c: Double, r: Double, ch: Char, fgc: Int, bgc: Int = KEEP): Boolean {
        val ci = floor(c).toInt(); val ri = floor(r).toInt()
        if (!inside(ci, ri)) return false
        g.set(ci, ri, ch, fgc, bgc); return true
    }

    fun textOver(c: Int, r: Int, s: String, fgc: Int, bgc: Int = P.BG) {
        if (r < ry || r >= ry + h) return
        for ((k, ch) in s.withIndex()) if (inside(c + k, r)) g.set(c + k, r, ch, fgc, bgc)
    }

    fun lineOver(x1: Double, y1: Double, x2: Double, y2: Double, ch: Char, fgc: Int, every: Int = 2) {
        val dx = x2 - x1; val dy = y2 - y1
        var t0 = 0.0; var t1 = 1.0
        val pp = doubleArrayOf(-dx, dx, -dy, dy)
        val qq = doubleArrayOf(x1 - rx, rx + w - x1, y1 - ry, ry + h - y1)
        for (k in 0..3) {
            if (pp[k] == 0.0) { if (qq[k] < 0) return }
            else {
                val t = qq[k] / pp[k]
                if (pp[k] < 0) { if (t > t1) return; if (t > t0) t0 = t }
                else { if (t < t0) return; if (t < t1) t1 = t }
            }
        }
        val ax = x1 + t0 * dx; val ay = y1 + t0 * dy
        val bx = x1 + t1 * dx; val by = y1 + t1 * dy
        val steps = (max(abs(bx - ax), abs(by - ay))).toInt() + 1
        var lastC = Int.MIN_VALUE; var lastR = Int.MIN_VALUE; var k = 0
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val ci = floor(ax + (bx - ax) * t).toInt(); val ri = floor(ay + (by - ay) * t).toInt()
            if (ci == lastC && ri == lastR) continue
            lastC = ci; lastR = ri
            if (k++ % every == 0 && inside(ci, ri)) g.set(ci, ri, ch, fgc)
        }
    }
}

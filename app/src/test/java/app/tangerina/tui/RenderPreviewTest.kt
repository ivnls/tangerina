package app.tangerina.tui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Renderiza um mapa sintético para validar o rasterizador e gerar uma prévia PNG. */
class RenderPreviewTest {
    private val lat0 = -23.5610; private val lon0 = -46.6560

    private fun way(id: Long, kind: Int, name: String?, vararg ll: Double): Feature {
        val n = ll.size / 2
        return Feature(id, kind, DoubleArray(n) { Geo.nx(ll[it * 2 + 1]) }, DoubleArray(n) { Geo.ny(ll[it * 2]) }, name, ' ', 0)
    }

    private fun tile(): Tile {
        val k = Array(K.COUNT) { ArrayList<Feature>() }
        var id = 1L
        val d = 0.0009
        // grade de ruas
        for (i in -6..6) {
            k[K.MINOR].add(way(id++, K.MINOR, "Rua ${'A' + i + 6}", lat0 + i * d, lon0 - 0.01, lat0 + i * d, lon0 + 0.01))
            k[K.MINOR].add(way(id++, K.MINOR, null, lat0 - 0.01, lon0 + i * d * 1.3, lat0 + 0.01, lon0 + i * d * 1.3))
        }
        k[K.MAJOR].add(way(id++, K.MAJOR, "Avenida Paulista", lat0 - 0.0050, lon0 - 0.01, lat0 - 0.0047, lon0 + 0.01))
        k[K.MAJOR].add(way(id++, K.MAJOR, "Av. Diagonal", lat0 - 0.006, lon0 - 0.008, lat0 + 0.004, lon0 + 0.006))
        k[K.MOTORWAY].add(way(id++, K.MOTORWAY, "Marginal", lat0 + 0.0045, lon0 - 0.01, lat0 + 0.0045, lon0 + 0.01))
        k[K.RIVER].add(way(id++, K.RIVER, "Rio Tamanduateí", lat0 - 0.0072, lon0 - 0.01, lat0 - 0.0062, lon0, lat0 - 0.0075, lon0 + 0.01))
        k[K.GREEN].add(way(id++, K.GREEN, "Parque", lat0 + 0.0012, lon0 - 0.0055, lat0 + 0.0012, lon0 - 0.0025, lat0 + 0.0032, lon0 - 0.0025, lat0 + 0.0032, lon0 - 0.0055))
        k[K.WATER].add(way(id++, K.WATER, "Lago", lat0 + 0.0016, lon0 + 0.003, lat0 + 0.0024, lon0 + 0.0052, lat0 + 0.0034, lon0 + 0.0040, lat0 + 0.0026, lon0 + 0.0028))
        run {
            val pts = ArrayList<Double>()
            for (j in 0..40) { val t = j / 40.0; pts.add(lat0 - 0.0030 + 0.0045 * t); pts.add(lon0 - 0.0075 + 0.0060 * Math.sin(t * Math.PI * 1.2)) }
            k[K.MAJOR].add(way(id++, K.MAJOR, "Av. Curva", *pts.toDoubleArray()))
        }
        k[K.RAIL].add(way(id++, K.RAIL, null, lat0 - 0.01, lon0 + 0.0072, lat0 + 0.01, lon0 + 0.0060))
        for (j in 0..4) {
            val la = lat0 - 0.0040 + j * 0.0004; val lo = lon0 - 0.0062
            k[K.BUILDING].add(way(id++, K.BUILDING, null, la, lo, la, lo + 0.0007, la + 0.00025, lo + 0.0007, la + 0.00025, lo))
        }
        k[K.POI].add(Feature(id++, K.POI, doubleArrayOf(Geo.nx(lon0 + 0.0019)), doubleArrayOf(Geo.ny(lat0 - 0.0019)), "Hospital", 'H', 0))
        k[K.POI].add(Feature(id++, K.POI, doubleArrayOf(Geo.nx(lon0 - 0.0013)), doubleArrayOf(Geo.ny(lat0 + 0.0005)), "Café", 'C', 0))
        k[K.PLACE].add(Feature(id++, K.PLACE, doubleArrayOf(Geo.nx(lon0 + 0.0035)), doubleArrayOf(Geo.ny(lat0 + 0.0008)), "Bela Vista", ' ', 2))
        return Tile(k)
    }

    @Test
    fun renderMap() {
        val cols = 46; val rows = 40
        val g = Grid(); g.resize(cols, rows)
        val mr = MapRenderer()
        val cw = 23f; val ch = 46f
        mr.setup(g, 0, 2, cols, rows - 4, lat0 + 0.0008, lon0 - 0.0008, 15.9, cw, ch, 256.0 * 2.75)
        mr.drawTiles(listOf(tile()))
        mr.plotOver(mr.colOf(Geo.nx(lon0)), mr.rowOf(Geo.ny(lat0)), '▲', P.BG, P.HI)
        // barras da UI
        g.fillRect(0, 0, cols, 1, ' ', P.BG, P.FG); g.text(1, 0, "▓ TANGERINA", P.BG); g.textR(cols - 1, 0, "3D 9/21 14:32:07 ", P.BG)
        val tabs = listOf("MAPA", "GPS", "SAT", "WPT", "NAV", "BUSCA", "CFG")
        for ((i, t) in tabs.withIndex()) {
            val x0 = i * cols / tabs.size; val x1 = (i + 1) * cols / tabs.size; val w = x1 - x0 - 1
            val on = i == 0
            g.fillRect(x0, 1, w, 1, ' ', if (on) P.BG else P.DIM, if (on) P.FG else P.PANEL)
            g.text(x0 + (w - t.length) / 2, 1, t, if (on) P.BG else P.DIM, if (on) P.FG else P.PANEL)
        }
        g.text(0, 2, " Z15.9 ", P.BG, P.DIM); g.text(cols - 3, 2, " N ", P.BG, P.ALERT)
        g.text(0, rows - 2, "▌-23.56100 -46.65600 ±4m 32km/h", P.FG)
        val keys = listOf("+", "−", "◉SEG", "MARCAR", "BAIXAR", "OM")
        for ((i, t) in keys.withIndex()) {
            val x0 = i * cols / keys.size; val x1 = (i + 1) * cols / keys.size; val w = x1 - x0 - 1
            g.fillRect(x0, rows - 1, w, 1, ' ', P.HI, P.PANEL); g.text(x0 + (w - t.length) / 2, rows - 1, t, P.HI, P.PANEL)
        }
        val txt = (0 until rows).joinToString("\n") { y -> String(g.ch, y * cols, cols) }
        println(txt)
        assertTrue(txt.contains("Paulista") && txt.contains("▲"))
        html(g, File("build/preview_map.html"))
    }

    private fun html(g: Grid, out: File) {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        val cw = 14f; val chh = 28f; val thin = 2.2f
        val sb = StringBuilder()
        sb.append("<html><body style='margin:0;background:#0B0602'><svg id='t' xmlns='http://www.w3.org/2000/svg' width='${g.cols * cw}' height='${g.rows * chh}' ")
            .append("font-family='DejaVu Sans Mono,monospace' font-weight='bold' font-size='22'>")
        for (y in 0 until g.rows) for (x in 0 until g.cols) {
            val i = y * g.cols + x
            sb.append("<rect x='${x * cw}' y='${y * chh}' width='${cw + 0.6f}' height='${chh + 0.6f}' fill='${hex(g.bg[i])}'/>")
        }
        val seg = FloatArray(4)
        fun line(ax: Float, ay: Float, bx: Float, by: Float, w: Float, col: String) =
            sb.append("<line x1='$ax' y1='$ay' x2='$bx' y2='$by' stroke='$col' stroke-width='$w'/>")
        fun stroke(c: Char, a: Float, ox: Float, oy: Float, x0: Float, y0: Float, col: String) {
            val px = x0 + cw * (0.5f + ox); val py = y0 + chh * (0.5f + oy)
            val r = Math.toRadians(a.toDouble()); val dx = Math.cos(r).toFloat(); val dy = Math.sin(r).toFloat()
            if (!Stroke.isLine(c)) {
                sb.append("<text x='$px' y='${py + 8}' fill='$col' text-anchor='middle' transform='rotate(${Stroke.upright(a)} $px $py)'>$c</text>")
                return
            }
            fun ln(qx: Float, qy: Float, w: Float) {
                if (Stroke.clip(qx, qy, dx, dy, x0 - 0.6f, y0 - 0.6f, x0 + cw + 0.6f, y0 + chh + 0.6f, seg)) line(seg[0], seg[1], seg[2], seg[3], w, col)
            }
            when (c) {
                '━' -> ln(px, py, thin * 2.4f)
                '═' -> { val o = thin * 1.3f; ln(px - dy * o, py + dx * o, thin * 0.9f); ln(px + dy * o, py - dx * o, thin * 0.9f) }
                '╪' -> { ln(px, py, thin); val k = cw * 0.32f; line(px - dy * k, py + dx * k, px + dy * k, py - dx * k, thin, col) }
                else -> ln(px, py, thin)
            }
        }
        for (y in 0 until g.rows) for (x in 0 until g.cols) {
            val i = y * g.cols + x
            val c = g.ch[i]
            val x0 = x * cw; val y0 = y * chh
            if (!g.ang[i].isNaN()) {
                stroke(c, g.ang[i], g.ox[i], g.oy[i], x0, y0, hex(g.fg[i]))
                if (!g.ang2[i].isNaN()) stroke(g.ch2[i], g.ang2[i], g.ox2[i], g.oy2[i], x0, y0, hex(g.fg2[i]))
                continue
            }
            if (c == ' ') continue
            val col = hex(g.fg[i])
            when (c) {
                '▌' -> sb.append("<rect x='$x0' y='$y0' width='${cw / 2}' height='$chh' fill='$col'/>")
                '─' -> line(x0, y0 + chh / 2, x0 + cw, y0 + chh / 2, thin, col)
                '├', '┤' -> { line(x0 + cw / 2, y0, x0 + cw / 2, y0 + chh, thin, col); line(x0 + cw / 2, y0 + chh / 2, if (c == '├') x0 + cw else x0, y0 + chh / 2, thin, col) }
                else -> {
                    val e = when (c) { '<' -> "&lt;"; '>' -> "&gt;"; '&' -> "&amp;"; else -> c.toString() }
                    sb.append("<text x='${x0 + cw / 2}' y='${y0 + 21}' fill='$col' text-anchor='middle'>$e</text>")
                }
            }
        }
        var yy = 0f
        while (yy < g.rows * chh) { sb.append("<rect x='0' y='$yy' width='${g.cols * cw}' height='2' fill='black' fill-opacity='0.22'/>"); yy += 5 }
        sb.append("</svg></body></html>")
        out.parentFile.mkdirs(); out.writeText(sb.toString())
    }
}

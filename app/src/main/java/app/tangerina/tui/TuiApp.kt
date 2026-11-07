package app.tangerina.tui

import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val loose: Boolean, val act: () -> Unit)
class Waypoint(var name: String, val lat: Double, val lon: Double, val time: Long = System.currentTimeMillis())
class Sat(val cons: Char, val svid: Int, val cn0: Float, val az: Float, val el: Float, val used: Boolean)
class FindResult(val name: String, val detail: String, val lat: Double, val lon: Double)

/** Estado e telas da interface TUI. */
class TuiApp(private val act: MainActivity, private val view: TuiView) : TuiView.Listener {

    companion object {
        const val MAP = 0; const val GPS = 1; const val SAT = 2; const val WPT = 3
        const val NAV = 4; const val FIND = 5; const val CFG = 6
        val TABS = arrayOf("MAPA", "GPS", "SAT", "WPT", "NAV", "BUSCA", "CFG")
        val MODES = arrayOf("vehicle", "pedestrian", "bicycle", "transit")
        val MODE_NAMES = arrayOf("CARRO", "A PÉ", "BIKE", "TRANSP.")
        val FONTS = floatArrayOf(11f, 13f, 15f, 17f, 20f, 24f)
        val ARROWS = charArrayOf('▲', '◥', '▶', '◢', '▼', '◣', '◀', '◤')
        private val BIG = hashMapOf(
            '0' to arrayOf("███", "█ █", "█ █", "█ █", "███"),
            '1' to arrayOf("██ ", " █ ", " █ ", " █ ", "███"),
            '2' to arrayOf("███", "  █", "███", "█  ", "███"),
            '3' to arrayOf("███", "  █", " ██", "  █", "███"),
            '4' to arrayOf("█ █", "█ █", "███", "  █", "  █"),
            '5' to arrayOf("███", "█  ", "███", "  █", "███"),
            '6' to arrayOf("███", "█  ", "███", "█ █", "███"),
            '7' to arrayOf("███", "  █", "  █", "  █", "  █"),
            '8' to arrayOf("███", "█ █", "███", "█ █", "███"),
            '9' to arrayOf("███", "█ █", "███", "  █", "███"),
            '.' to arrayOf(" ", " ", " ", " ", "█"),
            '-' to arrayOf("   ", "   ", "███", "   ", "   "),
            ':' to arrayOf(" ", "█", " ", "█", " "),
        )
    }

    private val g get() = view.grid
    private val hits = ArrayList<Hit>()
    private val keys = ArrayList<Pair<String, () -> Unit>>()
    private val prefs = act.getSharedPreferences("tangerina", Context.MODE_PRIVATE)
    val store = TileStore(File(act.filesDir, "tiles")) { redraw() }
    private val mr = MapRenderer()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ------------------------------------------------------------ estado
    var screen = MAP; private set
    var loc: Location? = null
    private var sats: List<Sat> = emptyList()
    private var compass: Float? = null
    private var lastCompassDraw = 0L
    private var mapLat = -23.55052; private var mapLon = -46.63331
    private var zoom = 16.0
    private var follow = true
    private var cursor: DoubleArray? = null
    var target: Waypoint? = null
    private val wpts = ArrayList<Waypoint>()
    private var wptSel = -1; private var wptScroll = 0
    private val track = ArrayList<DoubleArray>()
    private var recording = false
    private var tripDist = 0.0; private var tripMax = 0.0; private var tripMoving = 0L
    private var lastTrip: Location? = null
    private var imperial = false; private var routeMode = 0; private var autoDl = true; var keepOn = true
    private var findQuery = ""; private var findRes: List<FindResult> = emptyList()
    private var findSel = -1; private var findBusy = false; private var findErr: String? = null; private var findScroll = 0
    private var msg: String? = null; private var msgUntil = 0L
    private var confirmKey: String? = null; private var confirmUntil = 0L
    private var cache: Pair<Long, Int>? = null
    private var dragAcc = 0f
    private var mapTop = 0; private var mapBot = 0
    private var listTop = 0; private var listRows = 0

    // ----------------------------------------------------- persistência

    fun load() {
        prefs.getString("lat", null)?.toDoubleOrNull()?.let { mapLat = it }
        prefs.getString("lon", null)?.toDoubleOrNull()?.let { mapLon = it }
        zoom = prefs.getFloat("zoom", 16f).toDouble()
        follow = prefs.getBoolean("follow", true)
        imperial = prefs.getBoolean("imperial", false)
        routeMode = prefs.getInt("route", 0).coerceIn(0, MODES.size - 1)
        autoDl = prefs.getBoolean("autodl", true)
        keepOn = prefs.getBoolean("keepon", true)
        recording = prefs.getBoolean("rec", false)
        tripDist = prefs.getFloat("tripd", 0f).toDouble()
        tripMax = prefs.getFloat("tripm", 0f).toDouble()
        tripMoving = prefs.getLong("tript", 0L)
        view.fontSp = prefs.getFloat("font", 15f)
        view.scanlines = prefs.getBoolean("scan", true)
        view.glow = prefs.getBoolean("glow", true)
        view.night = prefs.getBoolean("night", false)
        view.applyFont()
        try {
            val a = JSONArray(prefs.getString("wpts", "[]"))
            for (i in 0 until a.length()) wpts.add(wptFrom(a.getJSONObject(i)))
            prefs.getString("target", null)?.let { target = wptFrom(JSONObject(it)) }
        } catch (_: Exception) {
        }
        val tf = File(act.filesDir, "track.csv")
        if (tf.exists()) try {
            tf.forEachLine { l ->
                val p = l.split(',')
                if (p.size >= 4) track.add(doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble()))
            }
        } catch (_: Exception) {
        }
    }

    private fun wptFrom(o: JSONObject) = Waypoint(o.getString("n"), o.getDouble("a"), o.getDouble("o"), o.optLong("t"))
    private fun wptJson(w: Waypoint) = JSONObject().put("n", w.name).put("a", w.lat).put("o", w.lon).put("t", w.time)

    fun save() {
        val a = JSONArray(); wpts.forEach { a.put(wptJson(it)) }
        prefs.edit()
            .putString("lat", mapLat.toString()).putString("lon", mapLon.toString())
            .putFloat("zoom", zoom.toFloat()).putBoolean("follow", follow)
            .putBoolean("imperial", imperial).putInt("route", routeMode).putBoolean("autodl", autoDl)
            .putBoolean("keepon", keepOn).putBoolean("rec", recording)
            .putFloat("tripd", tripDist.toFloat()).putFloat("tripm", tripMax.toFloat()).putLong("tript", tripMoving)
            .putFloat("font", view.fontSp).putBoolean("scan", view.scanlines)
            .putBoolean("glow", view.glow).putBoolean("night", view.night)
            .putString("wpts", a.toString())
            .putString("target", target?.let { wptJson(it).toString() })
            .apply()
        try {
            File(act.filesDir, "track.csv").bufferedWriter().use { w ->
                for (p in track) w.write(f("%.7f,%.7f,%.1f,%d\n", p[0], p[1], p[2], p[3].toLong()))
            }
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------- sensores

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    fun onLocation(l: Location) {
        val cur = loc
        if (l.provider == LocationManager.NETWORK_PROVIDER && cur != null &&
            cur.provider == LocationManager.GPS_PROVIDER && ageMs(cur) < 15_000) return
        loc = l
        if (follow) { mapLat = l.latitude; mapLon = l.longitude }
        if (ageMs(l) < 10_000 && l.hasAccuracy() && l.accuracy < 35f) {
            val p = lastTrip
            if (p == null) lastTrip = l else {
                val d = p.distanceTo(l)
                if (d > max(4f, l.accuracy * 0.5f)) {
                    tripDist += d
                    val dt = l.time - p.time
                    if (dt in 1..30_000 && d / (dt / 1000.0) > 0.8) tripMoving += dt
                    lastTrip = l
                }
            }
            if (l.hasSpeed()) tripMax = max(tripMax, l.speed.toDouble())
            if (recording) {
                val last = track.lastOrNull()
                if (last == null || Geo.dist(last[0], last[1], l.latitude, l.longitude) > 5.0)
                    track.add(doubleArrayOf(l.latitude, l.longitude, l.altitude, l.time.toDouble()))
            }
        }
        redraw()
    }

    fun onGnss(s: GnssStatus) {
        val list = ArrayList<Sat>(s.satelliteCount)
        for (i in 0 until s.satelliteCount) {
            val c = when (s.getConstellationType(i)) {
                GnssStatus.CONSTELLATION_GPS -> 'G'; GnssStatus.CONSTELLATION_GLONASS -> 'R'
                GnssStatus.CONSTELLATION_GALILEO -> 'E'; GnssStatus.CONSTELLATION_BEIDOU -> 'C'
                GnssStatus.CONSTELLATION_QZSS -> 'J'; GnssStatus.CONSTELLATION_SBAS -> 'S'
                GnssStatus.CONSTELLATION_IRNSS -> 'I'; else -> '?'
            }
            list.add(Sat(c, s.getSvid(i), s.getCn0DbHz(i), s.getAzimuthDegrees(i), s.getElevationDegrees(i), s.usedInFix(i)))
        }
        sats = list.sortedByDescending { it.cn0 }
        if (screen == SAT || screen == GPS) redraw()
    }

    fun onCompass(az: Float) {
        val old = compass
        compass = az
        val now = SystemClock.uptimeMillis()
        if ((old == null || abs(angDiff(old.toDouble(), az.toDouble())) > 2) && now - lastCompassDraw > 150 &&
            (screen == MAP || screen == GPS || screen == NAV)) {
            lastCompassDraw = now; redraw()
        }
    }

    private fun fresh(): Location? = loc?.takeIf { ageMs(it) < 15_000 }

    private fun heading(): Double? {
        val l = fresh()
        if (l != null && l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) return l.bearing.toDouble()
        return compass?.toDouble()?.let { (it + 360) % 360 }
    }

    private fun angDiff(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180

    /** Posição de referência: GPS, senão centro do mapa. */
    private fun refPos(): DoubleArray = loc?.let { doubleArrayOf(it.latitude, it.longitude) } ?: doubleArrayOf(mapLat, mapLon)

    fun toast(s: String, ms: Long = 3000) {
        msg = s; msgUntil = SystemClock.uptimeMillis() + ms; redraw()
    }

    private fun confirm(key: String, text: String, action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (confirmKey == key && now < confirmUntil) { confirmKey = null; action() }
        else { confirmKey = key; confirmUntil = now + 3000; toast(text) }
    }

    fun showScreen(s: Int) {
        screen = s; dragAcc = 0f
        if (s == FIND && findQuery.isEmpty() && findRes.isEmpty()) askFind()
        if (s == CFG) store.size { b, n -> cache = Pair(b, n); redraw() }
        redraw()
    }

    fun back(): Boolean {
        if (screen != MAP) { showScreen(MAP); return true }
        if (cursor != null) { cursor = null; redraw(); return true }
        return false
    }

    fun setTarget(w: Waypoint, go: Boolean = true) {
        target = w
        toast("ALVO: ${w.name.uppercase()}")
        if (go) showScreen(NAV)
    }

    fun openGeo(lat: Double, lon: Double, label: String?) {
        follow = false; mapLat = lat; mapLon = lon; zoom = max(zoom, 16.0)
        setTarget(Waypoint(label ?: f("%.5f,%.5f", lat, lon), lat, lon), go = false)
        showScreen(MAP)
    }

    // ------------------------------------------------------------ teclas

    fun zoomBy(d: Double) { zoom = (zoom + d).coerceIn(4.0, 19.0); redraw() }

    fun pan(dxCells: Int, dyCells: Int) {
        if (screen != MAP) return
        onDrag(dxCells * view.cellW * 4, dyCells * view.cellH * 2)
    }

    // ------------------------------------------------------------ toque

    override fun onGrid(cols: Int, rows: Int) = redraw()

    override fun onTap(cx: Int, cy: Int) {
        for (h in hits.asReversed()) if (cx >= h.x && cx < h.x + h.w && cy >= h.y && cy < h.y + h.h) { h.act(); redraw(); return }
        for (h in hits.asReversed()) if (h.loose && cx >= h.x && cx < h.x + h.w && cy >= h.y - 1 && cy <= h.y + h.h) { h.act(); redraw(); return }
        if (screen == MAP && cy in mapTop..mapBot) {
            val nx = mr.nxOfCol(cx + 0.5); val ny = mr.nyOfRow(cy + 0.5)
            val c = cursor
            val lat = Geo.lat(ny); val lon = Geo.lon(nx)
            cursor = if (c != null && floor(mr.colOf(Geo.nx(c[1]))).toInt() == cx && floor(mr.rowOf(Geo.ny(c[0]))).toInt() == cy) null
            else doubleArrayOf(lat, lon)
            redraw()
        }
    }

    override fun onLong(cx: Int, cy: Int) {
        if (screen == MAP && cy in mapTop..mapBot) {
            val lat = Geo.lat(mr.nyOfRow(cy + 0.5)); val lon = Geo.lon(mr.nxOfCol(cx + 0.5))
            cursor = doubleArrayOf(lat, lon)
            setTarget(Waypoint(f("PONTO %.4f,%.4f", lat, lon), lat, lon), go = false)
        }
    }

    override fun onDrag(dx: Float, dy: Float) {
        when (screen) {
            MAP -> {
                follow = false
                val scale = tilePx() * 2.0.pow(zoom)
                val nx = (Geo.nx(mapLon) + dx / scale).let { it - floor(it) }
                val ny = (Geo.ny(mapLat) + dy / scale).coerceIn(0.0001, 0.9999)
                mapLon = Geo.lon(nx); mapLat = Geo.lat(ny)
                redraw()
            }
            WPT, FIND -> {
                dragAcc += dy
                val step = view.cellH
                var d = 0
                while (dragAcc >= step) { dragAcc -= step; d++ }
                while (dragAcc <= -step) { dragAcc += step; d-- }
                if (d != 0) {
                    if (screen == WPT) wptScroll += d else findScroll += d
                    redraw()
                }
            }
        }
    }

    override fun onScale(f: Float) {
        if (screen != MAP) return
        zoom = (zoom + ln(f.toDouble()) / ln(2.0)).coerceIn(4.0, 19.0)
        redraw()
    }

    private fun tilePx() = 256.0 * act.resources.displayMetrics.density

    // ---------------------------------------------------------- desenho

    fun redraw() {
        if (g.cols == 0) return
        g.clear(); hits.clear(); keys.clear()
        val top = 2; val bot = g.rows - 3
        val h = bot - top + 1
        when (screen) {
            MAP -> drawMap(top, h)
            GPS -> drawGps(top, h)
            SAT -> drawSat(top, h)
            WPT -> drawWpt(top, h)
            NAV -> drawNav(top, h)
            FIND -> drawFind(top, h)
            CFG -> drawCfg(top, h)
        }
        drawHeader(); drawTabs(); drawStatus(); bar(g.rows - 1, keys, -1, P.HI, P.PANEL)
        view.invalidate()
    }

    private fun drawHeader() {
        g.fillRect(0, 0, g.cols, 1, ' ', P.BG, P.FG)
        g.text(1, 0, "▓ TANGERINA", P.BG)
        val right = "${fixLabel()} ${clock.format(Date())} "
        g.textR(g.cols - 1, 0, right, P.BG)
        if (recording && (SystemClock.uptimeMillis() / 1000) % 2 == 0L && g.cols > 14 + right.length + 5)
            g.text(13, 0, "●REC", P.ALERT, P.BG)
    }

    private fun fixLabel(): String {
        val l = loc ?: return "SEM FIX"
        val used = sats.count { it.used }
        val s = if (sats.isNotEmpty()) " $used/${sats.size}" else ""
        if (ageMs(l) > 10_000) return "FIX ANTIGO$s"
        if (l.provider == LocationManager.NETWORK_PROVIDER) return "REDE$s"
        return (if (l.hasAltitude()) "3D" else "2D") + s
    }

    private fun drawTabs() {
        val items = TABS.mapIndexed { i, t -> Pair(t, { showScreen(i) }) }
        bar(1, items, screen, P.DIM, P.PANEL)
    }

    /** Barra de botões dividindo a largura igualmente. */
    private fun bar(y: Int, items: List<Pair<String, () -> Unit>>, active: Int, fgc: Int, bgc: Int) {
        val n = items.size
        if (n == 0) { g.fillRect(0, y, g.cols, 1, ' ', P.DIM, P.BG); return }
        for ((i, it) in items.withIndex()) {
            val x0 = i * g.cols / n; val x1 = (i + 1) * g.cols / n
            val w = if (i == n - 1) x1 - x0 else x1 - x0 - 1
            val on = i == active
            val fc = if (on) P.BG else fgc; val bc = if (on) P.FG else bgc
            g.fillRect(x0, y, w, 1, ' ', fc, bc)
            val label = if (it.first.length > w) it.first.take(w) else it.first
            g.text(x0 + (w - label.length) / 2, y, label, fc, bc)
            hits.add(Hit(x0, y, x1 - x0, 1, true, it.second))
        }
    }

    private fun drawStatus() {
        val y = g.rows - 2
        val m = msg
        if (m != null && SystemClock.uptimeMillis() < msgUntil) {
            g.fillRect(0, y, g.cols, 1, ' ', P.BG, P.ALERT)
            g.text(1, y, m, P.BG, P.ALERT, g.cols - 2)
            return
        }
        msg = null
        val c = cursor
        val s = if (screen == MAP && c != null) {
            val r = refPos()
            "⌖" + f("%.5f %.5f", c[0], c[1]) + " " + Geo.fmtDist(Geo.dist(r[0], r[1], c[0], c[1]), imperial) +
                " " + Geo.cardinal(Geo.bearing(r[0], r[1], c[0], c[1]))
        } else {
            val l = loc
            if (l == null) "AGUARDANDO GPS… ATIVE A LOCALIZAÇÃO"
            else f("%.5f %.5f ", l.latitude, l.longitude) +
                (if (l.hasAccuracy()) "±${l.accuracy.roundToInt()}m " else "") +
                f("%.0f%s", Geo.speed(l.speed.toDouble(), imperial), Geo.speedUnit(imperial))
        }
        g.text(0, y, "▌", P.FG)
        g.text(1, y, s, P.FG, max = g.cols - 1)
    }

    private fun big(x: Int, y: Int, s: String, fc: Int): Int {
        var cx = x
        for (c in s) {
            val gl = BIG[c] ?: continue
            for (r in 0 until 5) for ((i, ch) in gl[r].withIndex()) if (ch != ' ') g.set(cx + i, y + r, '█', fc)
            cx += gl[0].length + 1
        }
        return cx
    }

    private fun bigWidth(s: String) = s.sumOf { (BIG[it]?.get(0)?.length ?: -1) + 1 } - 1

    private fun gline(x0: Double, y0: Double, x1: Double, y1: Double, c: Char, fc: Int) {
        val steps = (max(abs(x1 - x0), abs(y1 - y0)) * 2).toInt() + 1
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            g.set(floor(x0 + (x1 - x0) * t).toInt(), floor(y0 + (y1 - y0) * t).toInt(), c, fc)
        }
    }

    /** Rosa dos ventos. rot = rotação da escala (−proa = proa para cima). */
    private fun rose(cx: Int, cy: Int, ry: Double, rot: Double, needle: Double?, needleColor: Int, lubber: Boolean) {
        val rxs = ry * view.cellH / view.cellW
        val ccx = cx + 0.5; val ccy = cy + 0.5
        fun px(deg: Double, k: Double) = ccx + rxs * k * sin(Math.toRadians(deg + rot))
        fun py(deg: Double, k: Double) = ccy - ry * k * cos(Math.toRadians(deg + rot))
        var d = 0.0
        while (d < 360) { g.set(floor(px(d, 1.0)).toInt(), floor(py(d, 1.0)).toInt(), '·', P.FAINT); d += 3 }
        for (t in 0 until 360 step 30) g.set(floor(px(t.toDouble(), 1.0)).toInt(), floor(py(t.toDouble(), 1.0)).toInt(), '•', P.DIM)
        for ((deg, l) in listOf(0 to 'N', 90 to 'L', 180 to 'S', 270 to 'O')) {
            g.set(floor(px(deg.toDouble(), 1.0)).toInt(), floor(py(deg.toDouble(), 1.0)).toInt(), l, if (l == 'N') P.ALERT else P.HI, P.BG)
        }
        if (needle != null) {
            gline(ccx, ccy, px(needle + 180, 0.4), py(needle + 180, 0.4), '░', P.DIM)
            gline(ccx, ccy, px(needle, 0.78), py(needle, 0.78), '█', needleColor)
            g.set(floor(px(needle, 0.88)).toInt(), floor(py(needle, 0.88)).toInt(), '◆', needleColor)
        }
        g.set(cx, cy, '◉', P.HI)
        if (lubber) g.set(cx, floor(ccy - ry - 1).toInt(), '▼', P.HI)
    }

    // -------------------------------------------------------------- MAPA

    private fun drawMap(top: Int, h: Int) {
        val w = g.cols
        mapTop = top; mapBot = top + h - 1
        store.autoDownload = autoDl
        mr.setup(g, 0, top, w, h, mapLat, mapLon, zoom, view.cellW, view.cellH, tilePx())
        mr.drawData(store)

        // trilha gravada
        for (p in track) mr.plotOver(mr.colOf(Geo.nx(p[1])), mr.rowOf(Geo.ny(p[0])), '•', P.ALERT)
        // waypoints
        for (wp in wpts) {
            val c = mr.colOf(Geo.nx(wp.lon)); val r = mr.rowOf(Geo.ny(wp.lat))
            if (mr.plotOver(c, r, '◆', P.HI, P.BG) && zoom >= 12)
                mr.textOver(floor(c).toInt() + 1, floor(r).toInt(), wp.name.take(14), P.FG)
        }
        val l = loc
        val t = target
        if (t != null) {
            val tc = mr.colOf(Geo.nx(t.lon)); val tr = mr.rowOf(Geo.ny(t.lat))
            if (l != null) mr.lineOver(mr.colOf(Geo.nx(l.longitude)), mr.rowOf(Geo.ny(l.latitude)), tc, tr, '∙', P.ALERT)
            mr.plotOver(tc, tr, '⊕', P.ALERT, P.BG)
        }
        cursor?.let { mr.plotOver(mr.colOf(Geo.nx(it[1])), mr.rowOf(Geo.ny(it[0])), 'X', P.BG, P.ALERT) }
        if (l != null) {
            val c = mr.colOf(Geo.nx(l.longitude)); val r = mr.rowOf(Geo.ny(l.latitude))
            val mpp = metersPerPx(l.latitude)
            if (l.hasAccuracy()) {
                val rp = l.accuracy / mpp
                if (rp > view.cellW * 2) {
                    var a = 0.0
                    while (a < 360) {
                        val cc = c + rp * sin(Math.toRadians(a)) / view.cellW
                        val rr = r - rp * cos(Math.toRadians(a)) / view.cellH
                        val ci = floor(cc).toInt(); val ri = floor(rr).toInt()
                        if (ri in top..mapBot && g.get(ci, ri) == ' ') g.set(ci, ri, '·', P.FG)
                        a += 4
                    }
                }
            }
            val hd = heading()
            val ch = if (hd == null) '◉' else ARROWS[(((hd + 22.5) % 360) / 45).toInt() % 8]
            mr.plotOver(c, r, ch, P.BG, if (ageMs(l) < 15_000) P.HI else P.DIM)
        }
        if (!follow) {
            val cx = w / 2; val cy = top + h / 2
            if (g.get(cx, cy) == ' ' || cursor == null) g.set(cx, cy, '┼', P.HI)
        }

        // HUD
        g.text(0, top, f(" Z%.1f ", zoom), P.BG, P.DIM)
        g.text(w - 3, top, " N ", P.BG, P.ALERT)
        g.set(w - 2, top + 1, '↑', P.ALERT, P.BG)
        val pend = store.pending
        val hud = when {
            store.pinnedLeft > 0 -> "↓ BAIXANDO ${store.pinnedLeft}"
            pend > 0 -> "⟳ CARREGANDO $pend"
            mr.missing > 0 && !autoDl -> "OFFLINE: ${mr.missing} TILES S/ DADOS"
            mr.missing > 0 && store.lastError != null -> "⚠ REDE: ${store.lastError}"
            else -> null
        }
        if (hud != null) g.text(0, top + 1, " ${hud.take(w - 6)} ", P.HI, P.PANEL)
        if (mr.tooMany && zoom < 13.5) g.center(top + h / 2 + 2, " APROXIME (Z14+) PARA VER RUAS ", P.BG, P.FG)

        // escala
        val mpp = metersPerPx(mapLat)
        val target10 = mpp * view.cellW * 10
        val pow10 = 10.0.pow(floor(log10(target10)))
        val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * pow10 }.last { it <= target10 }
        val cells = max(2, (nice / (mpp * view.cellW)).roundToInt())
        val by = top + h - 1
        g.set(1, by, '├', P.HI, P.BG); g.hline(2, by, cells - 1, '─', P.HI); g.set(cells + 1, by, '┤', P.HI, P.BG)
        for (i in 2 until cells + 1) g.bg[by * g.cols + i] = P.BG
        g.text(cells + 2, by, " " + Geo.fmtDist(nice, imperial) + " ", P.HI, P.BG)
        g.textR(w - 1, by, "©OSM", P.DIM, P.BG)

        keys.add(Pair("+") { zoomBy(1.0) })
        keys.add(Pair("−") { zoomBy(-1.0) })
        keys.add(Pair(if (follow) "◉SEG" else "○SEG") {
            follow = !follow
            if (follow) loc?.let { mapLat = it.latitude; mapLon = it.longitude } ?: toast("SEM POSIÇÃO GPS")
        })
        keys.add(Pair("MARCAR") { markHere() })
        keys.add(Pair("BAIXAR") { downloadArea() })
        keys.add(Pair("OM") {
            val c = cursor
            val (la, lo, n) = if (c != null) Triple(c[0], c[1], "Ponto") else Triple(mapLat, mapLon, "Centro do mapa")
            om(OrganicMaps.showPoint(act, la, lo, n))
        })
    }

    private fun metersPerPx(lat: Double) =
        cos(Math.toRadians(lat)) * 2 * Math.PI * Geo.R / (tilePx() * 2.0.pow(zoom))

    private fun markHere() {
        val c = cursor
        val l = loc
        val (la, lo) = when {
            screen == MAP && c != null -> Pair(c[0], c[1])
            l != null -> Pair(l.latitude, l.longitude)
            else -> Pair(mapLat, mapLon)
        }
        act.ask("NOME DO PONTO:", "WPT ${wpts.size + 1}") { name ->
            val n = name.trim().ifEmpty { "WPT ${wpts.size + 1}" }
            wpts.add(Waypoint(n, la, lo))
            wptSel = wpts.size - 1
            toast("PONTO SALVO: ${n.uppercase()}")
        }
    }

    private fun downloadArea() {
        val n = 1 shl TileStore.Z
        val w = g.cols; val h = mapBot - mapTop + 1
        val nx0 = mr.nxOfCol(-w * 0.5); val nx1 = mr.nxOfCol(w * 1.5)
        val ny0 = mr.nyOfRow(mapTop - h * 0.5); val ny1 = mr.nyOfRow(mapTop + h * 1.5)
        val tx0 = floor(nx0 * n).toInt(); val tx1 = floor(nx1 * n).toInt()
        val ty0 = floor(ny0 * n).toInt(); val ty1 = floor(ny1 * n).toInt()
        val count = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
        if (count > 64) { toast("ÁREA GRANDE DEMAIS ($count TILES). APROXIME."); return }
        for (x in tx0..tx1) for (y in ty0..ty1) store.request(x, y, true)
        toast("BAIXANDO $count TILES P/ USO OFFLINE")
    }

    private fun om(r: Int) {
        when (r) {
            2 -> toast("ORGANIC MAPS NÃO ENCONTRADO — ABERTO EM OUTRO APP")
            0 -> toast("INSTALE O ORGANIC MAPS (CFG ▸ ORGANIC MAPS)", 4000)
        }
    }

    // --------------------------------------------------------------- GPS

    private fun drawGps(top: Int, h: Int) {
        val w = g.cols
        g.box(0, top, w, h, P.DIM, "GPS")
        var y = top + 1
        val l = loc
        fun kv(x: Int, k: String, v: String, vc: Int = P.HI) {
            g.text(x, y, k, P.DIM); g.text(x + 5, y, v, vc)
        }
        val half = max(20, w / 2)
        if (l == null) {
            kv(2, "FIX", "AGUARDANDO SINAL…", P.ALERT); y++
            g.text(2, y++, "Vá para céu aberto.", P.DIM)
        } else {
            kv(2, "LAT", f("%11.6f°", l.latitude)); if (w > 34) g.text(21, y, Geo.dms(l.latitude, 'N', 'S'), P.FG); y++
            kv(2, "LON", f("%11.6f°", l.longitude)); if (w > 34) g.text(21, y, Geo.dms(l.longitude, 'L', 'O'), P.FG); y++
            kv(2, "ALT", if (l.hasAltitude()) Geo.fmtAlt(l.altitude, imperial) else "—")
            if (w >= 36) kv(half, "PREC", if (l.hasAccuracy()) "±" + Geo.fmtAlt(l.accuracy.toDouble(), imperial) else "—")
            y++
            val hd = heading()
            kv(2, "PROA", if (hd != null) f("%03.0f° %s", hd, Geo.cardinal(hd)) else "—")
            if (w >= 36) kv(half, "FONTE", (l.provider ?: "?").uppercase())
            y++
            kv(2, "FIX", fixLabel())
            if (w >= 36) kv(half, "IDADE", "${ageMs(l) / 1000}s")
            y++
            kv(2, "HORA", utc.format(Date(l.time)) + " UTC"); y++
        }
        y++
        // velocidade em dígitos grandes
        if (y + 6 < top + h) {
            val spd = l?.let { Geo.speed(it.speed.toDouble(), imperial) } ?: 0.0
            val s = f("%.0f", spd)
            val bw = bigWidth(s)
            val x = max(2, (w - bw - 6) / 2)
            big(x, y, s, if (l != null && ageMs(l) < 15_000) P.FG else P.FAINT)
            g.text(x + bw + 2, y + 4, Geo.speedUnit(imperial).uppercase(), P.DIM)
            g.text(2, y, "VEL", P.DIM)
            y += 6
        }
        if (y + 3 < top + h) {
            g.rule(0, y, w, "VIAGEM"); y++
            kv(2, "DIST", Geo.fmtDist(tripDist, imperial))
            if (w >= 36) kv(half, "VMAX", f("%.0f %s", Geo.speed(tripMax, imperial), Geo.speedUnit(imperial)))
            y++
            kv(2, "MOV", Geo.hms(tripMoving))
            if (w >= 36) kv(half, "TRILHA", "${track.size} pts")
            y++
            g.text(2, y, if (recording) "● GRAVANDO TRILHA" else "○ TRILHA PAUSADA", if (recording) P.ALERT else P.DIM); y++
        }
        val remain = top + h - 1 - y
        if (remain >= 7) {
            val ry = ((remain - 2) / 2.0).coerceAtMost(12.0)
            rose(w / 2, y + 1 + ry.toInt(), ry, 0.0, heading(), P.FG, false)
        }
        keys.add(Pair(if (recording) "■ PARAR" else "● GRAVAR") {
            recording = !recording
            toast(if (recording) "GRAVANDO TRILHA" else "GRAVAÇÃO PAUSADA")
        })
        keys.add(Pair("ZERAR") {
            confirm("reset", "TOQUE ZERAR DE NOVO P/ APAGAR VIAGEM E TRILHA") {
                tripDist = 0.0; tripMax = 0.0; tripMoving = 0; lastTrip = null; track.clear(); toast("VIAGEM ZERADA")
            }
        })
        keys.add(Pair("GPX") { act.exportGpx() })
        keys.add(Pair("OM") { val p = refPos(); om(OrganicMaps.showPoint(act, p[0], p[1], "Minha posição")) })
    }

    // --------------------------------------------------------------- SAT

    private fun drawSat(top: Int, h: Int) {
        val w = g.cols
        val used = sats.count { it.used }
        g.box(0, top, w, h, P.DIM, "SATÉLITES $used/${sats.size}")
        val maxRy = ((w / 2 - 3) * view.cellW / view.cellH).toDouble()
        val ry = min(maxRy, (h - 4) * 0.28).coerceAtLeast(3.0)
        val cx = w / 2; val cy = top + 2 + ry.toInt()
        val rxs = ry * view.cellH / view.cellW
        for (e in intArrayOf(0, 30, 60)) {
            val k = (90 - e) / 90.0
            var a = 0.0
            while (a < 360) {
                g.set(floor(cx + 0.5 + rxs * k * sin(Math.toRadians(a))).toInt(), floor(cy + 0.5 - ry * k * cos(Math.toRadians(a))).toInt(), '·', P.FAINT)
                a += if (e == 0) 3.0 else 6.0
            }
        }
        g.set(cx, floor(cy + 0.5 - ry).toInt() - 1, 'N', P.ALERT)
        g.set(cx, floor(cy + 0.5 + ry).toInt() + 1, 'S', P.HI)
        g.set(floor(cx + 0.5 - rxs).toInt() - 1, cy, 'O', P.HI)
        g.set(floor(cx + 0.5 + rxs).toInt() + 1, cy, 'L', P.HI)
        g.set(cx, cy, '+', P.FAINT)
        for (s in sats.sortedBy { it.used }) {
            val k = (90 - s.el.coerceIn(0f, 90f)) / 90.0
            val c = floor(cx + 0.5 + rxs * k * sin(Math.toRadians(s.az.toDouble()))).toInt()
            val r = floor(cy + 0.5 - ry * k * cos(Math.toRadians(s.az.toDouble()))).toInt()
            if (s.used) g.set(c, r, s.cons, P.BG, P.HI) else g.set(c, r, s.cons, if (s.cn0 > 0) P.FG else P.FAINT, P.BG)
        }
        var y = floor(cy + ry).toInt() + 3
        if (y < top + h - 1) {
            g.rule(0, y, w, "SINAL (dB-Hz)"); y++
        }
        val barW = max(4, w - 17)
        val cols2 = w >= 70
        val per = if (cols2) 2 else 1
        val colW = w / per
        var i = 0
        while (y < top + h - 1 && i < sats.size) {
            for (k in 0 until per) {
                if (i >= sats.size) break
                val s = sats[i++]
                val x = 2 + k * colW
                val bw = if (cols2) colW - 17 else barW
                g.text(x, y, f("%c%3d", s.cons, s.svid), if (s.used) P.HI else P.DIM)
                val fill = ((s.cn0 / 50f).coerceIn(0f, 1f) * bw).roundToInt()
                for (b in 0 until bw) g.set(x + 5 + b, y, if (b < fill) '█' else '░', if (s.used) P.FG else P.DIM)
                g.text(x + 6 + bw, y, f("%2.0f", s.cn0), P.FG)
                if (s.used) g.text(x + 9 + bw, y, "✓", P.HI)
            }
            y++
        }
        if (sats.isEmpty()) g.center(top + h - 3, "SEM DADOS GNSS", P.DIM)
        keys.add(Pair("G=GPS R=GLO E=GAL C=BDS") { })
    }

    // --------------------------------------------------------------- WPT

    private fun drawWpt(top: Int, h: Int) {
        val w = g.cols
        g.box(0, top, w, h, P.DIM, "WAYPOINTS (${wpts.size})")
        listTop = top + 1; listRows = h - 2
        if (wpts.isEmpty()) {
            g.center(top + 2, "NENHUM PONTO SALVO", P.FG)
            g.center(top + 4, "[NOVO] = POSIÇÃO ATUAL", P.DIM)
            g.center(top + 5, "NO MAPA: TOQUE + [MARCAR]", P.DIM)
        }
        wptScroll = wptScroll.coerceIn(0, max(0, wpts.size - listRows))
        val ref = refPos()
        for (row in 0 until listRows) {
            val i = row + wptScroll
            if (i >= wpts.size) break
            val wp = wpts[i]
            val y = listTop + row
            val sel = i == wptSel
            val fc = if (sel) P.BG else P.FG
            val bc = if (sel) P.FG else P.BG
            g.fillRect(1, y, w - 2, 1, ' ', fc, bc)
            val d = Geo.dist(ref[0], ref[1], wp.lat, wp.lon)
            val b = Geo.bearing(ref[0], ref[1], wp.lat, wp.lon)
            val right = Geo.fmtDist(d, imperial) + " " + Geo.cardinal(b).padEnd(3)
            g.text(2, y, if (target === wp) "⊕" else "◆", if (sel) P.BG else P.HI, bc)
            g.text(4, y, wp.name.uppercase(), fc, bc, w - 6 - right.length - 2)
            g.textR(w - 2, y, right, if (sel) P.BG else P.DIM, bc)
            hits.add(Hit(1, y, w - 2, 1, false) { wptSel = if (wptSel == i) -1 else i })
        }
        if (wpts.size > listRows) {
            val pos = listTop + (wptScroll * (listRows - 1)) / max(1, wpts.size - listRows)
            g.set(w - 1, pos, '█', P.FG)
        }
        val sel = wpts.getOrNull(wptSel)
        keys.add(Pair("NOVO") { markHere() })
        if (sel != null) {
            keys.add(Pair("IR") { setTarget(sel) })
            keys.add(Pair("MAPA") { follow = false; mapLat = sel.lat; mapLon = sel.lon; cursor = doubleArrayOf(sel.lat, sel.lon); showScreen(MAP) })
            keys.add(Pair("OM") { om(OrganicMaps.showPoint(act, sel.lat, sel.lon, sel.name)) })
            keys.add(Pair("NOME") { act.ask("RENOMEAR:", sel.name) { n -> if (n.isNotBlank()) sel.name = n.trim(); redraw() } })
            keys.add(Pair("APAGAR") {
                confirm("del$wptSel", "TOQUE APAGAR DE NOVO P/ CONFIRMAR") {
                    wpts.remove(sel); if (target === sel) target = null; wptSel = -1; toast("PONTO APAGADO")
                }
            })
        }
    }

    // --------------------------------------------------------------- NAV

    private fun drawNav(top: Int, h: Int) {
        val w = g.cols
        val t = target
        if (t == null) {
            g.box(0, top, w, h, P.DIM, "NAVEGAÇÃO")
            g.center(top + 2, "SEM ALVO DEFINIDO", P.FG)
            g.center(top + 4, "TOQUE LONGO NO MAPA,", P.DIM)
            g.center(top + 5, "ESCOLHA UM WAYPOINT", P.DIM)
            g.center(top + 6, "OU FAÇA UMA BUSCA.", P.DIM)
            keys.add(Pair("MAPA") { showScreen(MAP) })
            keys.add(Pair("WPT") { showScreen(WPT) })
            keys.add(Pair("BUSCA") { showScreen(FIND) })
            return
        }
        g.box(0, top, w, h, P.DIM, "NAVEGAÇÃO")
        var y = top + 1
        g.text(2, y, "⊕ " + t.name.uppercase(), P.HI, max = w - 4); y++
        g.text(4, y, f("%.6f, %.6f", t.lat, t.lon), P.DIM); y++
        val l = loc
        if (l == null) {
            y++; g.text(2, y, "AGUARDANDO GPS…", P.ALERT)
        } else {
            val d = Geo.dist(l.latitude, l.longitude, t.lat, t.lon)
            val brg = Geo.bearing(l.latitude, l.longitude, t.lat, t.lon)
            val hd = heading()
            y++
            val (v, u) = Geo.distParts(d, imperial)
            val bw = bigWidth(v)
            val x = max(2, (w - bw - 4) / 2)
            big(x, y, v, if (d < 25) P.ALERT else P.FG)
            g.text(x + bw + 2, y + 4, u.uppercase(), P.DIM)
            y += 6
            fun kv(x: Int, k: String, s: String) { g.text(x, y, k, P.DIM); g.text(x + 6, y, s, P.HI) }
            val half = max(20, w / 2)
            kv(2, "RUMO", f("%03.0f° %s", brg, Geo.cardinal(brg)))
            if (hd != null && w >= 36) {
                val rel = angDiff(hd, brg)
                kv(half, "REL", f("%+.0f° %s", rel, if (abs(rel) < 10) "↑" else if (rel > 0) "→" else "←"))
            }
            y++
            val spd = if (l.hasSpeed()) l.speed.toDouble() else 0.0
            kv(2, "VEL", f("%.0f %s", Geo.speed(spd, imperial), Geo.speedUnit(imperial)))
            if (w >= 36) kv(half, "ETA", if (spd > 0.5) Geo.hms((d / spd * 1000).toLong()) else "--:--:--")
            y++
            if (d < 25) { g.center(y, " ★ VOCÊ CHEGOU ★ ", P.BG, P.ALERT) }
            y++
            val remain = top + h - 1 - y
            if (remain >= 7) {
                val ry = ((remain - 3) / 2.0).coerceAtMost(12.0)
                rose(w / 2, y + 2 + ry.toInt(), ry, -(hd ?: 0.0), brg, P.ALERT, hd != null)
            }
        }
        keys.add(Pair("ROTA OM") {
            val p = refPos()
            om(OrganicMaps.route(act, p[0], p[1], "Minha posição", t.lat, t.lon, t.name, MODES[routeMode]))
        })
        keys.add(Pair(MODE_NAMES[routeMode]) { routeMode = (routeMode + 1) % MODES.size })
        keys.add(Pair("SALVAR") {
            if (wpts.none { it === t }) { wpts.add(t); toast("ALVO SALVO EM WPT") } else toast("JÁ ESTÁ EM WPT")
        })
        keys.add(Pair("LIMPAR") { target = null; toast("ALVO REMOVIDO") })
    }

    // ------------------------------------------------------------- BUSCA

    private fun askFind() {
        act.ask("BUSCAR (OSM):", findQuery) { q -> if (q.isNotBlank()) search(q.trim()) }
    }

    private fun search(q: String) {
        findQuery = q; findBusy = true; findErr = null; findRes = emptyList(); findSel = -1; findScroll = 0
        redraw()
        val p = refPos()
        val lang = Locale.getDefault().toLanguageTag()
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=25&q=${Http.enc(q)}" +
                    f("&viewbox=%.4f,%.4f,%.4f,%.4f", p[1] - 0.7, p[0] + 0.7, p[1] + 0.7, p[0] - 0.7)
                val arr = JSONArray(Http.request(url, lang = lang))
                val out = ArrayList<FindResult>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val dn = o.optString("display_name")
                    val parts = dn.split(", ")
                    val name = o.optString("name").ifEmpty { parts.firstOrNull() ?: dn }
                    val detail = parts.drop(1).take(3).joinToString(", ")
                    out.add(FindResult(name, detail, o.getString("lat").toDouble(), o.getString("lon").toDouble()))
                }
                act.runOnUiThread { findRes = out; findBusy = false; if (out.isEmpty()) findErr = "NADA ENCONTRADO"; redraw() }
            } catch (e: Exception) {
                act.runOnUiThread { findBusy = false; findErr = "ERRO: ${e.message ?: "rede"}"; redraw() }
            }
        }.start()
    }

    private fun drawFind(top: Int, h: Int) {
        val w = g.cols
        g.box(0, top, w, h, P.DIM, "BUSCA OPENSTREETMAP")
        val qy = top + 1
        g.text(2, qy, "> ", P.HI)
        g.text(4, qy, findQuery.ifEmpty { "toque para digitar…" }, if (findQuery.isEmpty()) P.FAINT else P.HI, max = w - 7)
        if ((SystemClock.uptimeMillis() / 500) % 2 == 0L) g.set(min(w - 2, 4 + findQuery.length), qy, '█', P.FG)
        hits.add(Hit(1, qy, w - 2, 1, false) { askFind() })
        g.rule(0, qy + 1, w)
        listTop = qy + 2; listRows = top + h - 1 - listTop
        when {
            findBusy -> g.center(listTop + 1, "PROCURANDO…", P.FG)
            findErr != null -> g.center(listTop + 1, findErr!!, P.ALERT)
        }
        val per = 2
        val vis = listRows / per
        findScroll = findScroll.coerceIn(0, max(0, findRes.size - vis))
        val ref = refPos()
        for (k in 0 until vis) {
            val i = k + findScroll
            if (i >= findRes.size) break
            val r = findRes[i]
            val y = listTop + k * per
            val sel = i == findSel
            val fc = if (sel) P.BG else P.FG; val bc = if (sel) P.FG else P.BG
            g.fillRect(1, y, w - 2, 1, ' ', fc, bc)
            val d = Geo.fmtDist(Geo.dist(ref[0], ref[1], r.lat, r.lon), imperial)
            g.text(2, y, r.name, fc, bc, w - 5 - d.length)
            g.textR(w - 2, y, d, if (sel) P.BG else P.DIM, bc)
            g.text(4, y + 1, r.detail, P.DIM, max = w - 6)
            hits.add(Hit(1, y, w - 2, per, false) { findSel = if (findSel == i) -1 else i })
        }
        val sel = findRes.getOrNull(findSel)
        keys.add(Pair("NOVA") { askFind() })
        if (sel != null) {
            keys.add(Pair("IR") { setTarget(Waypoint(sel.name, sel.lat, sel.lon)) })
            keys.add(Pair("MAPA") { follow = false; mapLat = sel.lat; mapLon = sel.lon; cursor = doubleArrayOf(sel.lat, sel.lon); zoom = max(zoom, 16.0); showScreen(MAP) })
            keys.add(Pair("SALVAR") { wpts.add(Waypoint(sel.name, sel.lat, sel.lon)); toast("SALVO EM WPT") })
        }
        if (findQuery.isNotEmpty()) keys.add(Pair("NO OM") { val p = refPos(); om(OrganicMaps.search(act, findQuery, p[0], p[1])) })
    }

    // --------------------------------------------------------------- CFG

    private fun drawCfg(top: Int, h: Int) {
        val w = g.cols
        g.box(0, top, w, h, P.DIM, "CONFIGURAÇÕES")
        val onOff = { b: Boolean -> if (b) "LIGADO" else "DESLIG." }
        val omOk = OrganicMaps.installed(act)
        val c = cache
        val opts = listOf<Triple<String, String, () -> Unit>>(
            Triple("TAMANHO DA FONTE", f("%.0f sp", view.fontSp)) {
                val i = FONTS.indexOfFirst { it > view.fontSp + 0.1f }
                view.fontSp = if (i < 0) FONTS[0] else FONTS[i]; view.applyFont()
            },
            Triple("UNIDADES", if (imperial) "IMPERIAL" else "MÉTRICO") { imperial = !imperial },
            Triple("MODO NOITE", onOff(view.night)) { view.night = !view.night },
            Triple("SCANLINES CRT", onOff(view.scanlines)) { view.scanlines = !view.scanlines },
            Triple("BRILHO (GLOW)", onOff(view.glow)) { view.glow = !view.glow },
            Triple("TELA SEMPRE LIGADA", onOff(keepOn)) { keepOn = !keepOn; act.applyKeepOn() },
            Triple("ROTA NO ORGANIC MAPS", MODE_NAMES[routeMode]) { routeMode = (routeMode + 1) % MODES.size },
            Triple("DOWNLOAD AUTOMÁTICO", onOff(autoDl)) { autoDl = !autoDl; store.autoDownload = autoDl },
            Triple("CACHE DE MAPAS", if (c == null) "…" else f("%.1f MB", c.first / 1048576.0)) {
                confirm("cache", "TOQUE DE NOVO P/ APAGAR O CACHE") {
                    store.clear(); cache = Pair(0, 0); toast("CACHE APAGADO")
                }
            },
            Triple("ORGANIC MAPS", if (omOk) "INSTALADO ✓" else "INSTALAR ▶") { if (!omOk) OrganicMaps.openStore(act) else toast("ORGANIC MAPS OK") },
        )
        var y = top + 1
        for ((label, value, a) in opts) {
            if (y >= top + h - 1) break
            val v = "[$value]"
            g.text(2, y, label, P.FG, max = w - v.length - 5)
            g.textR(w - 3, y, v, P.HI)
            for (x in 2 + label.length + 1 until w - 3 - v.length) g.set(x, y, '.', P.FAINT)
            hits.add(Hit(1, y, w - 2, 1, false, a))
            y++
        }
        val about = listOf(
            "TANGERINA 1.0 · GPS TUI",
            "MAPA © COLABORADORES DO OPENSTREETMAP (ODbL)",
            "ROTAS OFFLINE: ORGANIC MAPS (om://)",
            "BUSCA: NOMINATIM · DADOS: OVERPASS API",
        )
        var ay = top + h - 1 - about.size
        if (ay > y) {
            g.rule(0, ay - 1, w)
            for (s in about) g.center(ay++, s, P.DIM, x0 = 1, w = w - 2)
        }
    }

    // --------------------------------------------------------------- GPX

    fun gpx(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Tangerina TUI\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (wp in wpts) append(f("  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><time>%s</time></wpt>\n",
                wp.lat, wp.lon, esc(wp.name), iso.format(Date(wp.time))))
            if (track.isNotEmpty()) {
                append("  <trk><name>Tangerina</name><trkseg>\n")
                for (p in track) append(f("    <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",
                    p[0], p[1], p[2], iso.format(Date(p[3].toLong()))))
                append("  </trkseg></trk>\n")
            }
            append("</gpx>\n")
        }
    }
}

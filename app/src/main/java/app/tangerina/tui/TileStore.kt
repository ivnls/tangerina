package app.tangerina.tui

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** Classes de feição do mapa (também a ordem/prioridade de desenho). */
object K {
    const val GREEN = 0
    const val WATER = 1
    const val BUILDING = 2
    const val PATH = 3
    const val SERVICE = 4
    const val STREAM = 5
    const val MINOR = 6
    const val RAIL = 7
    const val RIVER = 8
    const val MAJOR = 9
    const val MOTORWAY = 10
    const val POI = 11
    const val PLACE = 12
    const val COUNT = 13
}

class Feature(
    val id: Long, val kind: Int,
    val xs: DoubleArray, val ys: DoubleArray,
    val name: String?, val sym: Char, val rank: Int,
) {
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0
}

class Tile(val byKind: Array<ArrayList<Feature>>)

/**
 * Baixa dados vetoriais do OpenStreetMap (Overpass API) em tiles z15,
 * guarda em disco (uso offline) e mantém um cache LRU em memória.
 */
class TileStore(private val dir: File, private val onChange: () -> Unit) {
    companion object {
        const val Z = 15
        private const val TAG = "TileStore"
        private val ENDPOINTS = arrayOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        fun kx(k: Long) = (k shr 32).toInt()
        fun ky(k: Long) = k.toInt()
    }

    private val mem = object : LinkedHashMap<Long, Tile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tile>?) = size > 60
    }
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val pinned: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val noFile: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val failed = ConcurrentHashMap<Long, Long>()
    private val disk = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ep = 0

    @Volatile var wanted: Set<Long> = emptySet()
    @Volatile var autoDownload = true
        set(v) { if (v != field) noFile.clear(); field = v }
    @Volatile var lastError: String? = null

    val pending get() = inFlight.size
    val pinnedLeft get() = pinned.size

    init { dir.mkdirs() }

    private fun file(x: Int, y: Int) = File(dir, "${Z}_${x}_$y.json")

    fun get(x: Int, y: Int): Tile? {
        val k = key(x, y)
        synchronized(mem) { mem[k] }?.let { return it }
        request(x, y, false)
        return null
    }

    fun snapshot(): List<Pair<Long, Tile>> = synchronized(mem) { mem.entries.map { Pair(it.key, it.value) } }

    fun request(x: Int, y: Int, pin: Boolean) {
        val n = 1 shl Z
        if (x !in 0 until n || y !in 0 until n) return
        val k = key(x, y)
        if (synchronized(mem) { mem.containsKey(k) } && !pin) return
        if (pin) { pinned.add(k); noFile.remove(k); failed.remove(k) }
        if (!pin) {
            val retry = failed[k]
            if (retry != null && System.currentTimeMillis() < retry) return
            if (!autoDownload && noFile.contains(k)) return
        }
        if (!inFlight.add(k)) return
        disk.execute {
            val f = file(x, y)
            if (f.exists()) {
                try {
                    val t = parse(f.readText())
                    synchronized(mem) { mem[k] = t }
                } catch (e: Exception) {
                    Log.w(TAG, "tile corrompido $x/$y", e); f.delete()
                }
                inFlight.remove(k); pinned.remove(k)
                main.post(onChange)
                return@execute
            }
            if (!autoDownload && !pinned.contains(k)) {
                noFile.add(k); inFlight.remove(k); return@execute
            }
            net.execute { fetch(x, y, k) }
        }
    }

    private fun fetch(x: Int, y: Int, k: Long) {
        if (!wanted.contains(k) && !pinned.contains(k)) { inFlight.remove(k); return }
        try {
            val txt = overpass(query(x, y))
            val t = parse(txt)
            val f = file(x, y)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(txt)
            tmp.renameTo(f)
            synchronized(mem) { mem[k] = t }
            failed.remove(k)
            lastError = null
        } catch (e: Exception) {
            Log.w(TAG, "falha tile $x/$y: ${e.message}")
            failed[k] = System.currentTimeMillis() + 30_000
            lastError = e.message ?: e.javaClass.simpleName
        } finally {
            inFlight.remove(k); pinned.remove(k)
            main.post(onChange)
        }
    }

    private fun overpass(q: String): String {
        var err: Exception? = null
        for (i in ENDPOINTS.indices) {
            val url = ENDPOINTS[(ep + i) % ENDPOINTS.size]
            try {
                val r = Http.request(url, "data=" + Http.enc(q))
                val head = r.take(4000)
                if (!head.contains("\"elements\"")) throw IllegalStateException("resposta inválida")
                val remark = Regex("\"remark\"\\s*:\\s*\"([^\"]*)\"").find(r.takeLast(2000))?.groupValues?.get(1)
                if (remark != null && remark.contains("error", true)) throw IllegalStateException(remark)
                ep = (ep + i) % ENDPOINTS.size
                return r
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: IllegalStateException("sem servidor")
    }

    private fun tileLon(x: Int) = x.toDouble() / (1 shl Z) * 360.0 - 180.0
    private fun tileLat(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl Z)))))

    private fun query(x: Int, y: Int): String {
        val s = tileLat(y + 1); val n = tileLat(y); val w = tileLon(x); val e = tileLon(x + 1)
        val bb = f("%.7f,%.7f,%.7f,%.7f", s, w, n, e)
        return """
            [out:json][timeout:90][bbox:$bb];
            (
              way[highway];
              way[railway];
              way[waterway];
              way[natural~"^(water|wood|scrub|wetland)$"];
              way[landuse~"^(forest|grass|meadow|reservoir|basin|recreation_ground|village_green)$"];
              way[leisure~"^(park|garden|pitch|nature_reserve|golf_course)$"];
              way[building];
              node[place][name];
              node[amenity][name];
              node[shop][name];
              node[tourism][name];
            );
            out geom qt;
        """.trimIndent()
    }

    fun clear() {
        synchronized(mem) { mem.clear() }
        noFile.clear(); failed.clear()
        disk.execute {
            dir.listFiles()?.forEach { it.delete() }
            main.post(onChange)
        }
    }

    fun size(cb: (Long, Int) -> Unit) {
        disk.execute {
            val fs = dir.listFiles() ?: emptyArray()
            val sum = fs.sumOf { it.length() }
            main.post { cb(sum, fs.size) }
        }
    }

    // ------------------------------------------------------------ parsing

    private fun parse(json: String): Tile {
        val byKind = Array(K.COUNT) { ArrayList<Feature>() }
        val arr = JSONObject(json).optJSONArray("elements") ?: return Tile(byKind)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotEmpty() }
            when (e.optString("type")) {
                "node" -> {
                    val c = classifyNode(tags) ?: continue
                    val lat = e.optDouble("lat", Double.NaN); val lon = e.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    byKind[c.first].add(Feature(e.optLong("id") * 4 + 1, c.first,
                        doubleArrayOf(Geo.nx(lon)), doubleArrayOf(Geo.ny(lat)), name, c.second, c.third))
                }
                "way" -> {
                    val kind = classifyWay(tags)
                    if (kind < 0) continue
                    val geom = e.optJSONArray("geometry") ?: continue
                    val n = geom.length()
                    val xs = DoubleArray(n); val ys = DoubleArray(n)
                    var m = 0
                    for (j in 0 until n) {
                        val p = geom.optJSONObject(j) ?: continue
                        xs[m] = Geo.nx(p.optDouble("lon")); ys[m] = Geo.ny(p.optDouble("lat")); m++
                    }
                    if (m < 2) continue
                    byKind[kind].add(Feature(e.optLong("id") * 4 + 2, kind,
                        if (m == n) xs else xs.copyOf(m), if (m == n) ys else ys.copyOf(m), name, ' ', 0))
                }
            }
        }
        return Tile(byKind)
    }

    private fun classifyWay(t: JSONObject): Int {
        val hw = t.optString("highway")
        if (hw.isNotEmpty()) return when (hw) {
            "motorway", "trunk", "motorway_link", "trunk_link" -> K.MOTORWAY
            "primary", "secondary", "primary_link", "secondary_link" -> K.MAJOR
            "tertiary", "tertiary_link", "residential", "unclassified", "living_street", "road" -> K.MINOR
            "service", "track", "pedestrian", "busway" -> K.SERVICE
            "footway", "path", "cycleway", "steps", "bridleway" -> K.PATH
            else -> -1
        }
        when (t.optString("railway")) {
            "rail", "light_rail", "tram", "narrow_gauge", "subway", "monorail" ->
                return if (t.optString("tunnel") == "yes") -1 else K.RAIL
        }
        when (t.optString("waterway")) {
            "river", "canal" -> return K.RIVER
            "stream", "ditch", "drain" -> return K.STREAM
            "riverbank" -> return K.WATER
        }
        when (t.optString("natural")) {
            "water" -> return K.WATER
            "wood", "scrub", "wetland" -> return K.GREEN
        }
        when (t.optString("landuse")) {
            "reservoir", "basin" -> return K.WATER
            "forest", "grass", "meadow", "recreation_ground", "village_green" -> return K.GREEN
        }
        when (t.optString("leisure")) {
            "park", "garden", "pitch", "nature_reserve", "golf_course" -> return K.GREEN
        }
        if (t.has("building")) return K.BUILDING
        return -1
    }

    private fun classifyNode(t: JSONObject): Triple<Int, Char, Int>? {
        if (!t.has("name")) return null
        val place = t.optString("place")
        if (place.isNotEmpty()) {
            val rank = when (place) {
                "city" -> 0; "town" -> 1; "village", "suburb" -> 2
                "neighbourhood", "quarter", "hamlet" -> 3
                else -> return null
            }
            return Triple(K.PLACE, ' ', rank)
        }
        val sym: Char? = when (t.optString("amenity")) {
            "" -> null
            "hospital", "clinic", "doctors" -> 'H'
            "pharmacy" -> '+'
            "fuel" -> 'F'
            "parking" -> 'P'
            "restaurant", "fast_food", "food_court" -> 'R'
            "cafe", "bar", "pub", "ice_cream" -> 'C'
            "bank", "atm", "bureau_de_change" -> '$'
            "school", "university", "college", "kindergarten" -> 'E'
            "place_of_worship" -> '†'
            "police", "fire_station" -> '!'
            "toilets" -> 'W'
            "bus_station" -> 'B'
            "post_office" -> '✉'
            else -> '•'
        }
        if (sym != null) return Triple(K.POI, sym, 0)
        val shop = t.optString("shop")
        if (shop.isNotEmpty()) return Triple(K.POI, if (shop == "supermarket") 'M' else 's', 0)
        val tour = t.optString("tourism")
        if (tour.isNotEmpty()) return Triple(K.POI, when (tour) {
            "hotel", "hostel", "guest_house", "motel" -> 'h'
            "museum", "gallery" -> 'm'
            "viewpoint", "attraction" -> '*'
            else -> 'i'
        }, 0)
        return null
    }
}

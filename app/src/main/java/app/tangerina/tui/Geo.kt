package app.tangerina.tui

import java.util.Locale
import kotlin.math.*

fun f(fmt: String, vararg a: Any?): String = String.format(Locale.US, fmt, *a)

object Geo {
    const val R = 6371008.8

    /** Web Mercator normalizado (0..1). */
    fun nx(lon: Double) = (lon + 180.0) / 360.0
    fun ny(lat: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0
    }
    fun lon(nx: Double) = nx * 360.0 - 180.0
    fun lat(ny: Double) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ny))))

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val dp = p2 - p1; val dl = Math.toRadians(lo2 - lo1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val CARD = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    fun cardinal(deg: Double): String = CARD[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

    fun dms(v: Double, pos: Char, neg: Char): String {
        val a = abs(v)
        val d = floor(a).toInt()
        val mf = (a - d) * 60
        val m = floor(mf).toInt()
        val s = (mf - m) * 60
        return f("%d°%02d'%04.1f\"%c", d, m, s, if (v >= 0) pos else neg)
    }

    /** Retorna (valor, unidade). */
    fun distParts(m: Double, imp: Boolean): Pair<String, String> = if (!imp) {
        when {
            m < 1000 -> Pair(f("%d", m.roundToInt()), "m")
            m < 10000 -> Pair(f("%.2f", m / 1000), "km")
            m < 100000 -> Pair(f("%.1f", m / 1000), "km")
            else -> Pair(f("%d", (m / 1000).roundToInt()), "km")
        }
    } else {
        val mi = m / 1609.344
        when {
            mi < 0.1 -> Pair(f("%d", (m * 3.28084).roundToInt()), "ft")
            mi < 10 -> Pair(f("%.2f", mi), "mi")
            mi < 100 -> Pair(f("%.1f", mi), "mi")
            else -> Pair(f("%d", mi.roundToInt()), "mi")
        }
    }

    fun fmtDist(m: Double, imp: Boolean): String = distParts(m, imp).let { "${it.first} ${it.second}" }

    fun speed(mps: Double, imp: Boolean): Double = if (imp) mps * 2.236936 else mps * 3.6
    fun speedUnit(imp: Boolean) = if (imp) "mph" else "km/h"
    fun fmtAlt(m: Double, imp: Boolean): String = if (imp) f("%d ft", (m * 3.28084).roundToInt()) else f("%d m", m.roundToInt())

    fun hms(ms: Long): String {
        val s = ms / 1000
        return f("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}

package app.tangerina.tui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometria dos traços girados (compartilhada entre a view e os testes). */
object Stroke {
    /** Caracteres desenhados como linha vetorial; os demais são glifos girados. */
    fun isLine(c: Char) = c == '─' || c == '━' || c == '═' || c == '╪'

    /** Ângulo para texto não ficar de cabeça para baixo. */
    fun upright(a: Float): Float {
        var d = a
        while (d > 90f) d -= 180f
        while (d <= -90f) d += 180f
        return d
    }

    /**
     * Recorta a reta que passa por (px,py) na direção (dx,dy) ao retângulo
     * [x0,x1]×[y0,y1]. Escreve ax,ay,bx,by em [out]; false se não cruza.
     */
    fun clip(px: Float, py: Float, dx: Float, dy: Float,
             x0: Float, y0: Float, x1: Float, y1: Float, out: FloatArray): Boolean {
        var tmin = -1e9f; var tmax = 1e9f
        if (abs(dx) > 1e-6f) {
            val t1 = (x0 - px) / dx; val t2 = (x1 - px) / dx
            tmin = max(tmin, min(t1, t2)); tmax = min(tmax, max(t1, t2))
        } else if (px < x0 || px > x1) return false
        if (abs(dy) > 1e-6f) {
            val t1 = (y0 - py) / dy; val t2 = (y1 - py) / dy
            tmin = max(tmin, min(t1, t2)); tmax = min(tmax, max(t1, t2))
        } else if (py < y0 || py > y1) return false
        if (tmin >= tmax) return false
        out[0] = px + dx * tmin; out[1] = py + dy * tmin
        out[2] = px + dx * tmax; out[3] = py + dy * tmax
        return true
    }
}

package app.tangerina.tui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Integração com o Organic Maps via sua API de URL (esquema om://).
 * O Organic Maps faz o roteamento/navegação offline; o Tangerina é o "painel" TUI.
 */
object OrganicMaps {
    private val PKGS = arrayOf("app.organicmaps", "app.organicmaps.web", "app.organicmaps.beta", "app.organicmaps.debug")
    private const val APP = "Tangerina"

    private fun e(s: String) = Http.enc(s).replace("+", "%20")
    private fun ll(lat: Double, lon: Double) = f("%.6f,%.6f", lat, lon)

    fun installed(ctx: Context): Boolean = PKGS.any {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    /** 1 = abriu no Organic Maps, 2 = abriu em outro app de mapas, 0 = nada instalado. */
    fun showPoint(ctx: Context, lat: Double, lon: Double, name: String): Int =
        launch(ctx, "om://map?v=1&ll=${ll(lat, lon)}&n=${e(name)}&appname=$APP",
            "geo:${ll(lat, lon)}?q=${ll(lat, lon)}(${e(name)})")

    fun route(ctx: Context, sLat: Double, sLon: Double, sName: String,
              dLat: Double, dLon: Double, dName: String, type: String): Int =
        launch(ctx, "om://route?sll=${ll(sLat, sLon)}&saddr=${e(sName)}&dll=${ll(dLat, dLon)}&daddr=${e(dName)}&type=$type&appname=$APP",
            "geo:${ll(dLat, dLon)}?q=${ll(dLat, dLon)}(${e(dName)})")

    fun search(ctx: Context, q: String, lat: Double, lon: Double): Int =
        launch(ctx, "om://search?query=${e(q)}&cll=${ll(lat, lon)}&appname=$APP", "geo:0,0?q=${e(q)}")

    private fun launch(ctx: Context, uri: String, fallback: String): Int {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return 1
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return 2
        } catch (_: Exception) {
        }
        return 0
    }

    fun openStore(ctx: Context) {
        for (u in arrayOf("market://details?id=app.organicmaps", "https://f-droid.org/packages/app.organicmaps/")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) {
            }
        }
    }
}

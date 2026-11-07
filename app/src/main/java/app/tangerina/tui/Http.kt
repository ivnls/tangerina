package app.tangerina.tui

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    const val UA = "Tangerina-TUI/1.0 (Android GPS; dados OpenStreetMap)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun request(url: String, form: String? = null, lang: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("User-Agent", UA)
            if (lang != null) c.setRequestProperty("Accept-Language", lang)
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

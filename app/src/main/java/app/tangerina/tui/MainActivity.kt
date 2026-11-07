package app.tangerina.tui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {

    private lateinit var view: TuiView
    private lateinit var app: TuiApp
    private lateinit var inputBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var prompt: TextView
    private var inputCb: ((String) -> Unit)? = null

    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val sm by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val handler = Handler(Looper.getMainLooper())
    private var gnssCb: GnssStatus.Callback? = null
    private var listening = false
    private var declination = 0f
    private val rm = FloatArray(9)
    private val rr = FloatArray(9)
    private val ori = FloatArray(3)

    private val tick = object : Runnable {
        override fun run() { app.redraw(); handler.postDelayed(this, 1000) }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
            app.onLocation(l)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(P.BG)
        view = TuiView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val dp = resources.displayMetrics.density
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(P.PANEL)
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        prompt = TextView(this).apply {
            typeface = mono; setTextColor(P.HI); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        input = EditText(this).apply {
            typeface = mono; setTextColor(P.FG); setHintTextColor(P.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(P.BG)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    submitInput(); true
                } else false
            }
        }
        inputBar.addView(prompt, LinearLayout.LayoutParams(-2, -2))
        inputBar.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * dp).toInt() })
        root.addView(inputBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }
        setContentView(root)

        app = TuiApp(this, view)
        view.listener = app
        app.load()
        applyKeepOn()
        handleIntent(intent)

        if (!hasLocationPerm()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Aceita links geo:lat,lon?q=lat,lon(nome) de outros apps. */
    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "geo") return
        try {
            val ssp = d.schemeSpecificPart ?: return
            var coords = ssp.substringBefore('?')
            var label: String? = null
            val q = d.getQueryParameter("q") ?: Uri.decode(ssp.substringAfter("q=", ""))
            val m = Regex("^\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*(?:\\((.*)\\))?").find(q)
            if (m != null) {
                coords = "${m.groupValues[1]},${m.groupValues[2]}"
                label = m.groupValues[3].ifEmpty { null }
            }
            val p = coords.split(',')
            val lat = p[0].toDouble(); val lon = p[1].toDouble()
            if (lat == 0.0 && lon == 0.0) return
            app.openGeo(lat, lon, label)
        } catch (_: Exception) {
        }
    }

    private fun hasLocationPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPerm()) startLocation() else app.toast("SEM PERMISSÃO DE LOCALIZAÇÃO", 5000)
    }

    override fun onResume() {
        super.onResume()
        startLocation()
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        stopLocation()
        sm.unregisterListener(this)
        handler.removeCallbacks(tick)
        app.save()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm() || listening) return
        listening = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(p)) lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        try {
            val cb = gnssCb ?: object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) = app.onGnss(status)
            }.also { gnssCb = it }
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(cb, handler)
        } catch (_: Exception) {
        }
        if (app.loc == null) {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull {
                try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            if (last != null) app.onLocation(last)
        }
    }

    private fun stopLocation() {
        if (!listening) return
        listening = false
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        try { gnssCb?.let { lm.unregisterGnssStatusCallback(it) } } catch (_: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val (ax, ay) = when (rot) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rm, ax, ay, rr)
        SensorManager.getOrientation(rr, ori)
        val az = (Math.toDegrees(ori[0].toDouble()).toFloat() + declination + 360f) % 360f
        app.onCompass(az)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun applyKeepOn() {
        if (app.keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------ entrada

    fun ask(label: String, initial: String, cb: (String) -> Unit) {
        prompt.text = label
        input.setText(initial)
        input.setSelection(input.text.length)
        input.hint = label
        inputCb = cb
        inputBar.visibility = View.VISIBLE
        input.requestFocus()
        input.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitInput() {
        val t = input.text.toString()
        val cb = inputCb
        hideInput()
        cb?.invoke(t)
    }

    private fun hideInput() {
        inputCb = null
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        inputBar.visibility = View.GONE
        input.clearFocus()
        view.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inputBar.visibility == View.VISIBLE) { hideInput(); return }
        if (app.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Atalhos para teclado físico: 1–7 abas, +/− zoom, setas movem o mapa. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (inputBar.visibility != View.VISIBLE) {
            when (keyCode) {
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_7 -> { app.showScreen(keyCode - KeyEvent.KEYCODE_1); return true }
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { app.zoomBy(1.0); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { app.zoomBy(-1.0); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { app.pan(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { app.pan(1, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { app.pan(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { app.pan(0, 1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ----------------------------------------------------------------- GPX

    fun exportGpx() {
        val name = "tangerina_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".gpx"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/gpx+xml").putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 42)
        } catch (_: Exception) {
            app.toast("SEM GERENCIADOR DE ARQUIVOS")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(app.gpx().toByteArray()) }
            app.toast("GPX EXPORTADO ✓")
        } catch (e: Exception) {
            app.toast("ERRO AO SALVAR GPX")
        }
    }
}

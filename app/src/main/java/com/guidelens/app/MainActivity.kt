package com.guidelens.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * GuideLens — voice-first assistive app for blind & elderly users.
 *
 * UX wiring highlights:
 *  - detection auto-starts on launch (a blind user can't tap a button to begin)
 *  - double-tap anywhere repeats the last spoken message
 *  - periodic OCR reads signs in detection mode ("Sign says: …") and the
 *    "read this" button uses the same camera frames — not PreviewView.getBitmap()
 *  - pocket pause (proximity) and a covered-lens/dark watchdog keep alerts honest
 *  - first run: safety disclaimer, then a spoken tutorial (holding the phone,
 *    overhang limitation, one-earbud guidance, voice commands)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var ocr: OcrReader
    private lateinit var voice: VoiceController
    private lateinit var geo: Geo
    private lateinit var vibrator: Vibrator
    private lateinit var alerts: AlertCenter

    private lateinit var homeView: View
    private lateinit var cameraView: View
    private lateinit var navView: View
    private lateinit var navStatus: TextView
    private lateinit var readView: View
    private lateinit var sosView: View
    private lateinit var settingsView: View
    private lateinit var aboutView: View
    private lateinit var camStatus: TextView
    private lateinit var navEta: TextView
    private lateinit var scroller: ScrollView

    private lateinit var btnHome: MaterialButton
    private lateinit var btnNav: MaterialButton
    private lateinit var btnSos: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnVoice: MaterialButton

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlay: OverlayView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var camera: CameraController

    private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
    private val REQ_PERMS = 101
    private val REQ_CAM = 102
    private val REQ_MIC = 103

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var activeNav: Geo.Route? = null

    // ---- new UX state ----
    private lateinit var sonar: Sonar
    private lateinit var tapDetector: GestureDetector
    private lateinit var pocketWatcher: PocketWatcher
    private lateinit var fallDetector: FallDetector
    private var cameraStartedOnce = false
    private var ocrBusy = false
    private var lastOcrNorm = ""
    private var lastOcrAt = 0L
    private var fallCancel: Runnable? = null

    private val ocrRunnable = object : Runnable {
        override fun run() {
            if (camera.running && !ocrBusy) {
                val bmp = camera.lastFrame
                if (bmp != null) {
                    ocrBusy = true
                    val snap = Bitmap.createScaledBitmap(bmp, bmp.width, bmp.height, false)
                    lifecycleScope.launch {
                        val text = withContext(Dispatchers.IO) { ocr.read(snap) }
                        ocrBusy = false
                        if (text.isNotBlank()) maybeAnnounceSign(text)
                    }
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        applyTheme()
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        alerts = AlertCenter(
            speaker = speakerInit(),
            prefs = prefs,
            vibrator = vibrator
        )
        ocr = OcrReader()
        voice = VoiceController(this)
        geo = Geo()
        sonar = Sonar()

        bindViews()
        twoLine(btnNav, "Navigate somewhere")
        twoLine(btnSos, "SOS - alert my contact")
        twoLine(btnSettings, "Settings")
        styleButtons()
        applySimpleMode()

        show("home")
        syncThemeIcon()
        wireButtons()
        startGeo()
        loadSettingsUI()
        updateBatteryChip()

        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                speaker.repeat()
                return true
            }
        })

        pocketWatcher = PocketWatcher(this) { covered ->
            runOnUiThread {
                alerts.setMuted(covered)
                sonar.muted = covered
                if (covered) speaker.speak("Phone covered. Detection paused.")
                else speaker.speak("Detection resumed.")
            }
        }

        fallDetector = FallDetector(this) { runOnUiThread { onFallDetected() } }

        if (!allGranted(PERMS)) {
            ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)
        }

        // detection is the point of the app: start it without waiting for a tap
        handler.postDelayed({ startCamera() }, 700)
        handler.postDelayed(ocrRunnable, 6000)
        showDisclaimer()
    }

    private fun speakerInit(): Speaker {
        speaker = Speaker(this, prefs)
        return speaker
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelFallCountdownIfAny()
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        homeView = findViewById(R.id.homeView)
        cameraView = findViewById(R.id.cameraView)
        navView = findViewById(R.id.navView)
        navStatus = findViewById(R.id.navStatus)
        readView = findViewById(R.id.readView)
        sosView = findViewById(R.id.sosView)
        settingsView = findViewById(R.id.settingsView)
        aboutView = findViewById(R.id.aboutView)
        camStatus = findViewById(R.id.camStatus)
        navEta = findViewById(R.id.navEta)
        scroller = findViewById(R.id.scroller)

        btnHome = findViewById(R.id.btnHome)
        btnNav = findViewById(R.id.btnNav)
        btnSos = findViewById(R.id.btnSos)
        btnSettings = findViewById(R.id.btnSettings)
        btnVoice = findViewById(R.id.btnVoice)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
    }

    private fun twoLine(btn: MaterialButton, text: String) {
        val parts = text.split(" - ", limit = 2)
        if (parts.size == 2) {
            val title = parts[0].trim()
            val subtitle = parts[1].trim()
            val sp = android.text.SpannableString("$title\n$subtitle")
            sp.setSpan(android.text.style.AbsoluteSizeSpan(13, true), title.length + 1, sp.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#B9BDC7")), title.length + 1, sp.length, 0)
            btn.text = sp
        } else {
            btn.text = text
        }
    }

    private fun styleButtons() {
        val ids = intArrayOf(R.id.btnStartCamera, R.id.btnWhereAmI, R.id.btnNavigate,
            R.id.btnReadSign, R.id.btnVoice, R.id.btnStopNav, R.id.btnDescribe, R.id.btnStopCam,
            R.id.btnCallContact, R.id.btnSmsContact, R.id.btnTestSOS, R.id.btnSendSOS,
            R.id.btnBackSOS, R.id.btnSaveSettings, R.id.btnBackSettings, R.id.btnOpenTtsSettings,
            R.id.btnBackAbout, R.id.btnHome, R.id.btnNav, R.id.btnSos, R.id.btnSettings)
        for (id in ids) {
            val b = findViewById<View>(id) as? MaterialButton ?: continue
            b.cornerRadius = 28
            b.setPadding(24, 18, 24, 18)
        }
    }

    private fun applySimpleMode() {
        val simple = prefs.simple
        listOf(R.id.btnReadSign, R.id.btnSettings, R.id.btnOpenTtsSettings,
            R.id.rowTheme, R.id.cardModes, R.id.cardVoice, R.id.cardNav,
            R.id.cardRead, R.id.cardTts, R.id.cardAbout).forEach { id ->
            findViewById<View>(id)?.visibility = if (simple) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btnTestSOS)?.visibility = if (simple) View.GONE else View.VISIBLE
    }

    private fun show(name: String) {
        homeView.visibility = if (name == "home") View.VISIBLE else View.GONE
        cameraView.visibility = if (name == "camera") View.VISIBLE else View.GONE
        navView.visibility = if (name == "nav") View.VISIBLE else View.GONE
        readView.visibility = if (name == "read") View.VISIBLE else View.GONE
        sosView.visibility = if (name == "sos") View.VISIBLE else View.GONE
        settingsView.visibility = if (name == "settings") View.VISIBLE else View.GONE
        aboutView.visibility = if (name == "about") View.VISIBLE else View.GONE

        val title = when (name) {
            "home" -> "GuideLens"
            "camera" -> "Obstacle detection"
            "nav" -> "Navigate"
            "read" -> "Read for me"
            "sos" -> "SOS"
            "settings" -> "Settings"
            "about" -> "About"
            else -> "GuideLens"
        }
        supportActionBar?.title = title
        scroller.post { scroller.scrollTo(0, 0) }
    }

    private fun syncThemeIcon() {
        val icon = if (prefs.themeDark) R.drawable.ic_moon else R.drawable.ic_sun
        try {
            val d: Drawable? = androidx.core.content.ContextCompat.getDrawable(this, icon)
            findViewById<MaterialButton>(R.id.btnToggleTheme)?.icon = d
        } catch (e: Exception) {
        }
    }

    private fun wireButtons() {
        findViewById<View>(R.id.btnWhereAmI).setOnClickListener { whereAmI() }
        findViewById<View>(R.id.btnStartCamera).setOnClickListener { show("camera"); startCamera() }
        findViewById<View>(R.id.btnDescribe).setOnClickListener { describeAround() }
        findViewById<View>(R.id.btnStopCam).setOnClickListener { stopCameraAndHome() }
        findViewById<View>(R.id.btnNavigate).setOnClickListener { show("nav") }
        findViewById<View>(R.id.btnStopNav).setOnClickListener { stopNavigation() }
        findViewById<View>(R.id.btnReadSign).setOnClickListener { readSign() }
        findViewById<View>(R.id.btnCallContact).setOnClickListener { callContact() }
        findViewById<View>(R.id.btnSmsContact).setOnClickListener { smsContact() }
        findViewById<View>(R.id.btnTestSOS).setOnClickListener {
            vibrateSosPattern()
            speaker.speak("SOS test. Both sound and vibration are working.")
        }
        findViewById<View>(R.id.btnSendSOS).setOnClickListener { sendSOS() }
        findViewById<View>(R.id.btnBackSOS).setOnClickListener { show("home") }
        findViewById<View>(R.id.btnSaveSettings).setOnClickListener { saveSettings(); show("home") }
        findViewById<View>(R.id.btnBackSettings).setOnClickListener { show("home") }
        findViewById<View>(R.id.btnOpenTtsSettings).setOnClickListener {
            try {
                val i = Intent("com.android.settings.TTS_SETTINGS")
                startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(this, "TTS settings not available", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnBackAbout).setOnClickListener { show("home") }
        btnNav.setOnClickListener { show("nav") }
        btnSos.setOnClickListener { show("sos") }
        btnSettings.setOnClickListener { show("settings") }
        btnHome.setOnClickListener { show("home") }
        findViewById<View>(R.id.btnToggleTheme)?.setOnClickListener {
            prefs.themeDark = !prefs.themeDark
            applyTheme()
            syncThemeIcon()
        }
        btnVoice.setOnClickListener {
            if (listening) {
                try { recognizer?.stopListening() } catch (e: Exception) { }
                return@setOnClickListener
            }
            micPermissionThen { startListening() }
        }

        val et = findViewById<EditText>(R.id.etDestination)
        findViewById<View>(R.id.btnGo).setOnClickListener {
            val q = et.text.toString().trim()
            if (q.isEmpty()) {
                speaker.speak("Please type or say where you want to go.")
            } else {
                navigateTo(q)
            }
        }
    }

    private fun applyTheme() {
        if (prefs.themeDark) setTheme(R.style.Theme_GuideLens) else setTheme(R.style.Theme_GuideLens_Light)
    }

    private fun allGranted(perms: Array<String>): Boolean {
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun startCamera() {
        cameraStartedOnce = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        if (!::camera.isInitialized) {
            camera = CameraController(this, previewView, overlay, prefs, speaker, alerts, sonar) { msg ->
                camStatus.text = msg
            }
        }
        camera.start()
        sonar.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGeo()
            }
            REQ_CAM -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) camera.start()
                else speaker.speak("Camera permission was denied. Obstacle detection will not work.")
            }
            REQ_MIC -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
                else speaker.speak("Microphone permission was denied. Voice commands will not work.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGeo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            geo.start(this)
        }
    }

    private fun whereAmI() {
        lifecycleScope.launch {
            val d = geo.whereAmI()
            val text = when {
                d.isOnline == false && d.name.isEmpty() -> {
                    "No internet access. I can't tell you where you are right now."
                }
                d.name.isNotEmpty() -> {
                    val lat = String.format("%.4f", d.lat)
                    val lon = String.format("%.4f", d.lon)
                    "${d.name}. Approximate coordinates: latitude $lat, longitude $lon."
                }
                else -> "I can't determine your exact address, but your approximate coordinates are " +
                        "${String.format("%.4f", d.lat)}, ${String.format("%.4f", d.lon)}."
            }
            speaker.speak(text)
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateTo(query: String) {
        lifecycleScope.launch {
            speaker.speak("Searching for $query.")
            val dest = geo.lookupDestination(query)
            if (dest == null) {
                if (!Geo.isOnline(this@MainActivity)) {
                    speaker.speak("I need an internet connection to search for places.")
                } else {
                    speaker.speak("I couldn't find $query.")
                }
                return@launch
            }
            speaker.speak("Found ${dest.name}. Downloading walking route.")
            val route = geo.fetchRoute(dest)
            if (route == null) {
                if (!Geo.isOnline(this@MainActivity)) {
                    speaker.speak("Navigation needs an internet connection to download the walking route. Everything else works offline.")
                } else {
                    speaker.speak("I couldn't get a walking route to ${dest.name}.")
                }
                return@launch
            }
            activeNav = route
            show("nav")
            speaker.speak("Starting navigation to ${dest.name}. Distance ${route.distanceMeters} meters. Walk straight to begin.")
            navStatus.text = "Navigating to ${dest.name} — ${route.distanceMeters} m"
            handler.removeCallbacks(navTick)
            handler.post(navTick)
        }
    }

    private val navTick = object : Runnable {
        override fun run() {
            val r = activeNav ?: return
            val loc = geo.lastLocation
            if (loc == null) {
                handler.postDelayed(this, 3000)
                return
            }
            val near = geo.isNearTurn(loc, r.steps)
            if (near != null) {
                val text = Geo.maneuverText(near)
                speaker.speak(text)
                navStatus.text = "Next: $text"
                handler.postDelayed(this, 15000)
            } else {
                handler.postDelayed(this, 3000)
            }
        }
    }

    private fun finishNav() {
        activeNav = null
        handler.removeCallbacks(navTick)
        speaker.speak("You have arrived. Navigation finished.")
        navStatus.text = "You have arrived."
    }

    private fun stopNavigation() {
        activeNav = null
        handler.removeCallbacks(navTick)
        speaker.speak("Navigation stopped.")
        show("home")
    }

    private fun stopCameraAndHome() {
        if (::camera.isInitialized) camera.stop()
        sonar.stop()
        show("home")
    }

    private fun describeAround() {
        val dets = camera.lastSeen
        val zone = camera.lastZone
        val sb = StringBuilder()
        if (dets.isEmpty() && zone == null) {
            speaker.speak("I don't see any obstacles right now.")
            return
        }
        dets.take(5).forEach { d ->
            val distTxt = d.dist?.let { " %.1f meters".format(it) } ?: ""
            sb.append("${d.label}$distTxt ${d.side}. ")
        }
        zone?.let { sb.append("Obstacle ahead ${"%.1f".format(it.dist)} meters. ") }
        val text = sb.toString().trim()
        speaker.speak(text)
        camStatus.text = text
    }

    /** "Read this" — uses the live camera frame cache, NOT PreviewView.getBitmap(). */
    private fun readSign() {
        val bmp = if (::camera.isInitialized) camera.lastFrame else null
        if (!::camera.isInitialized || !camera.running || bmp == null) {
            show("camera")
            startCamera()
            handler.postDelayed({ readSign() }, 1500)
            return
        }
        speaker.speak("Reading… hold it steady.")
        val snap = Bitmap.createScaledBitmap(bmp, bmp.width, bmp.height, false)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { ocr.read(snap) }
            if (text.isNotBlank()) {
                lastOcrNorm = normalizeOcr(text)
                lastOcrAt = System.currentTimeMillis()
                speaker.speak("It says: " + text.take(200))
                camStatus.text = "Read: $text"
            } else {
                speaker.speak("I couldn't read any text here. Try holding the camera steady and closer.")
            }
        }
    }

    /** Periodic sign reading while detection runs: announces NEW text only. */
    private fun maybeAnnounceSign(text: String) {
        val norm = normalizeOcr(text)
        val now = System.currentTimeMillis()
        if (norm == lastOcrNorm && now - lastOcrAt < 30_000) return
        lastOcrNorm = norm
        lastOcrAt = now
        speaker.speak("Sign says: " + text.take(140))
    }

    private fun normalizeOcr(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun sosFlow() {
        show("sos")
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set. Please add one in settings.")
            show("settings")
        }
    }

    private fun callContact() {
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set.")
            return
        }
        try {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${prefs.contact}"))
            startActivity(i)
        } catch (e: Exception) {
            speaker.speak("I couldn't open the phone app.")
        }
    }

    private fun smsContact() {
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set.")
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${prefs.contact}"))
            i.putExtra("sms_body", buildSosText())
            startActivity(i)
        } catch (e: Exception) {
            speaker.speak("I couldn't open the messages app.")
        }
    }

    private fun sendSOS() {
        val text = buildSosText()
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set. Please add a contact number in settings.")
            show("settings")
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${prefs.contact}"))
            i.putExtra("sms_body", text)
            startActivity(i)
            speaker.speak("Opening messages with your emergency text. Press send.")
        } catch (e: Exception) {
            speaker.speak("I couldn't open the messages app.")
        }
    }

    private fun buildSosText(): String {
        val loc = geo.lastLocation
        return if (loc != null) {
            "I need help! My approximate location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        } else {
            "I need help! (location unavailable)"
        }
    }

    private fun startListening() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    listening = false
                }

                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val said = list?.getOrNull(0)
                    if (said.isNullOrBlank()) {
                        speaker.speak("Sorry, I didn't catch that.")
                    } else {
                        handleCommand(said)
                    }
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            listening = true
            recognizer?.startListening(i)
        } catch (e: Exception) {
            listening = false
        }
    }

    private fun micPermissionThen(fn: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            fn()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    sosFlow()
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    whereAmI()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleCommand(raw: String) {
        val text = raw.lowercase()
        when {
            text.contains("where am i") || text.contains("my location") -> whereAmI()
            text.contains("navigate") || text.contains("take me") || text.contains("directions to") -> {
                val dest = text.removePrefix("navigate to").removePrefix("take me to").removePrefix("directions to").trim()
                if (dest.isEmpty()) {
                    speaker.speak("Where would you like to go?")
                } else {
                    navigateTo(dest)
                }
            }
            text.contains("read") -> readSign()
            text.contains("stop navigation") -> stopNavigation()
            text.contains("help") || text.contains("emergency") || text.contains("sos") -> {
                vibrateSosPattern()
                sendSOS()
            }
            text.contains("describe") -> describeAround()
            text.contains("camera") || text.contains("obstacle") -> {
                show("camera")
                startCamera()
            }
            text.contains("battery") -> updateBatteryChip(true)
            text.contains("repeat") -> speaker.repeat()
            else -> speaker.speak("You said $text. Try: where am I, navigate to the market, read this, or help.")
        }
    }

    private fun loadSettingsUI() {
        findViewById<MaterialSwitch>(R.id.swAudio)?.isChecked = prefs.audio
        findViewById<MaterialSwitch>(R.id.swHaptics)?.isChecked = prefs.haptics
        findViewById<MaterialSwitch>(R.id.swStepFree)?.isChecked = prefs.stepFree
        findViewById<MaterialSwitch>(R.id.swSimple)?.isChecked = prefs.simple
        findViewById<EditText>(R.id.etContact)?.setText(prefs.contact)
        checkVerb()
        checkSens()
        val rate = findViewById<EditText>(R.id.etRate)
        rate?.setText(prefs.rate.toString())
        rate?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull()
                if (v != null && v in 0.5f..2.0f) {
                    prefs.rate = v
                    speaker.setRate(v)
                }
            }
        })
    }

    private fun checkVerb() {
        val r = findViewById<LinearLayout>(R.id.rgVerbosity)
        val id = when (prefs.verbosity) {
            "full" -> R.id.rbFull
            "minimal" -> R.id.rbMinimal
            else -> R.id.rbStandard
        }
        (r?.getChildAt(0) as? android.widget.RadioButton)?.isChecked = false
        findViewById<View>(id)?.let { (it as? android.widget.RadioButton)?.isChecked = true }
        r?.setOnCheckedChangeListener { _, checkedId ->
            prefs.verbosity = when (checkedId) {
                R.id.rbFull -> "full"
                R.id.rbMinimal -> "minimal"
                else -> "standard"
            }
        }
    }

    private fun checkSens() {
        val r = findViewById<LinearLayout>(R.id.rgSensitivity)
        val id = when (prefs.sensitivity) {
            "near" -> R.id.rbNear
            "far" -> R.id.rbFar
            else -> R.id.rbNormal
        }
        findViewById<View>(id)?.let { (it as? android.widget.RadioButton)?.isChecked = true }
        r?.setOnCheckedChangeListener { _, checkedId ->
            prefs.sensitivity = when (checkedId) {
                R.id.rbNear -> "near"
                R.id.rbFar -> "far"
                else -> "standard"
            }
        }
    }

    private fun saveSettings() {
        prefs.audio = findViewById<MaterialSwitch>(R.id.swAudio)?.isChecked ?: true
        prefs.haptics = findViewById<MaterialSwitch>(R.id.swHaptics)?.isChecked ?: true
        prefs.stepFree = findViewById<MaterialSwitch>(R.id.swStepFree)?.isChecked ?: false
        prefs.simple = findViewById<MaterialSwitch>(R.id.swSimple)?.isChecked ?: false
        prefs.contact = findViewById<EditText>(R.id.etContact)?.text?.toString()?.trim() ?: ""
        applySimpleMode()
        speaker.speak("Settings saved.")
    }

    private fun updateBatteryChip(forceSpeak: Boolean = false) {
        val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        findViewById<TextView>(R.id.batteryChip)?.text = "Battery $pct%"
        if (pct in 0..20 || forceSpeak) {
            speaker.speak("Battery at $pct percent.")
        }
    }

    private fun showDisclaimer() {
        if (prefs.agreed) {
            if (!prefs.tutorialDone) playTutorial()
            return
        }
        val msg = (
            "Please read carefully:\n\n" +
            "1) GuideLens is an assistive aid — it cannot replace your white cane, guide dog, or your own judgment.\n\n" +
            "2) Do not cross roads based solely on this app.\n\n" +
            "3) Distances and obstacles detection are approximate.\n\n" +
            "4) Your camera and microphone stay on-device; only location requests go online for maps."
        )
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Safety & Privacy")
            .setMessage(msg)
            .setPositiveButton("I understand") { d, _ ->
                prefs.agreed = true
                d.dismiss()
                playTutorial()
            }
            .setCancelable(false)
        dlg.show()
        speaker.speak("Welcome to GuideLens. Please listen to the safety information on screen, then press I understand.")
    }

    /** Spoken onboarding for blind & elderly users — plays once, out loud. */
    private fun playTutorial() {
        prefs.tutorialDone = true
        val steps = listOf(
            900L to "Let's learn the basics.",
            4200L to "Hold the phone at chest height, with the camera on the back facing forward, tilted slightly toward the ground. Detection is already running, you don't need to press anything.",
            12000L to "I will beep faster as obstacles get closer, and tell you what they are. Double-tap anywhere on the screen to repeat my last message.",
            18500L to "I can't see things above your head, like open cupboards, awnings or tree branches. Always check overhead obstacles with your hand or cane.",
            25500L to "For your safety, use only one earphone, or a bone conduction headset, so you can still hear traffic and people around you.",
            32000L to "Hold the big green button and speak to ask for things, like: navigate to the market, where am I, read this, or help.",
            39000L to "That's it. The volume up button opens S O S, and volume down tells you where you are. GuideLens is now watching for obstacles."
        )
        for ((t, msg) in steps) handler.postDelayed({ speaker.speak(msg) }, t)
    }

    private fun onFallDetected() {
        vibrateSosPattern()
        speaker.speak(
            "I think you may have fallen. Are you okay? Tap anywhere on the screen if you are fine, " +
            "or I will send an emergency message in 15 seconds.", priority = true
        )
        val r = Runnable { sendSOS() }
        fallCancel = r
        handler.postDelayed(r, 15_000)
    }

    private fun cancelFallCountdownIfAny() {
        val r = fallCancel ?: return
        handler.removeCallbacks(r)
        fallCancel = null
        speaker.speak("Okay. Emergency message cancelled.")
    }

    fun vibrateSosPattern() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) {
        }
    }

    private fun vibrateLong() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        pocketWatcher.start()
        fallDetector.start()
        // auto-restart detection after phone calls / app switching
        if (cameraStartedOnce && (!::camera.isInitialized || !camera.running)) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        pocketWatcher.stop()
        fallDetector.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::camera.isInitialized) camera.shutdown()
        sonar.stop()
        pocketWatcher.stop()
        fallDetector.stop()
        try { recognizer?.destroy() } catch (e: Exception) { }
        speaker.shutdown()
        geo.stop()
    }
}

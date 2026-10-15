package com.guidelens.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GuideLens — voice-first assistive app for blind & elderly users.
 *
 * UX wiring highlights:
 *  - detection auto-starts on launch (a blind user can't tap a button to begin)
 *  - double-tap anywhere repeats the last spoken message
 *  - periodic OCR reads signs in detection mode ("Sign says: …") and "Read a
 *    sign" uses the same camera frames — not PreviewView.getBitmap()
 *  - pocket pause (proximity) and a covered-lens/dark watchdog keep alerts honest
 *  - first run: safety disclaimer, then a spoken tutorial (holding the phone,
 *    overhang limitation, one-earbud guidance, voice commands)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var ocr: OcrReader
    private lateinit var voice: VoiceController
    private lateinit var vibrator: Vibrator
    private lateinit var alerts: AlertCenter

    private lateinit var scroller: android.widget.ScrollView
    private lateinit var scrHome: View
    private lateinit var scrNav: View
    private lateinit var scrCamera: View
    private lateinit var scrSOS: View
    private lateinit var scrSettings: View
    private lateinit var navStatus: TextView
    private lateinit var camStatus: TextView
    private lateinit var navDestInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var setContact: com.google.android.material.textfield.TextInputEditText

    private lateinit var gpsChip: Chip
    private lateinit var battChip: Chip

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlay: OverlayView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var camera: CameraController
    private lateinit var fused: com.google.android.gms.location.FusedLocationProviderClient
    private var lastLocation: Location? = null

    private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
    private val REQ_PERMS = 101
    private val REQ_CAM = 102
    private val REQ_MIC = 103

    private var activeNav: Geo.Route? = null

    // ---- UX additions ----
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
            if (::camera.isInitialized && camera.running && !ocrBusy) {
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

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                lastLocation = it
                updateGpsChip()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        speaker = Speaker(this, prefs)
        alerts = AlertCenter(speaker, prefs, vibrator)
        ocr = OcrReader()
        voice = VoiceController(
            this,
            onCommand = { runOnUiThread { handleCommand(it) } },
            onError = { runOnUiThread { speaker.speak("Sorry, I didn't catch that.") } }
        )
        sonar = Sonar()
        fused = LocationServices.getFusedLocationProviderClient(this)

        bindViews()
        wireButtons()
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

        if (!allGranted(PERMS)) ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)

        // detection is the point of the app: start it without waiting for a tap
        handler.postDelayed({ startCamera() }, 700)
        handler.postDelayed({ startLocation() }, 900)
        handler.postDelayed(ocrRunnable, 6000)
        showDisclaimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelFallCountdownIfAny()
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        scroller = findViewById(R.id.scroller)
        scrHome = findViewById(R.id.scrHome)
        scrNav = findViewById(R.id.scrNav)
        scrCamera = findViewById(R.id.scrCamera)
        scrSOS = findViewById(R.id.scrSOS)
        scrSettings = findViewById(R.id.scrSettings)
        navStatus = findViewById(R.id.navStatus)
        camStatus = findViewById(R.id.camStatus)
        navDestInput = findViewById(R.id.navDestInput)
        setContact = findViewById(R.id.setContact)
        gpsChip = findViewById(R.id.gpsChip)
        battChip = findViewById(R.id.battChip)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
    }

    private fun show(name: String) {
        scrHome.visibility = if (name == "home") View.VISIBLE else View.GONE
        scrNav.visibility = if (name == "nav") View.VISIBLE else View.GONE
        scrCamera.visibility = if (name == "camera") View.VISIBLE else View.GONE
        scrSOS.visibility = if (name == "sos") View.VISIBLE else View.GONE
        scrSettings.visibility = if (name == "settings") View.VISIBLE else View.GONE
        scroller.post { scroller.scrollTo(0, 0) }
    }

    private fun wireButtons() {
        findViewById<View>(R.id.btnWhereAmI).setOnClickListener { whereAmI() }
        findViewById<View>(R.id.btnNavigate).setOnClickListener { show("nav") }
        findViewById<View>(R.id.btnDetect).setOnClickListener { show("camera"); startCamera() }
        findViewById<View>(R.id.btnAround).setOnClickListener { describeAround() }
        findViewById<View>(R.id.btnRead).setOnClickListener { readSign() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { show("settings") }
        findViewById<View>(R.id.btnSOS).setOnClickListener { show("sos") }

        // hold-to-talk
        findViewById<View>(R.id.btnTalk).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    micPermissionThen { voice.start() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    voice.stop()
                    true
                }
                else -> false
            }
        }

        findViewById<View>(R.id.navBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.navGo).setOnClickListener {
            val q = navDestInput.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) speaker.speak("Please type or say where you want to go.")
            else navigateTo(q)
        }
        findViewById<View>(R.id.navStop).setOnClickListener { stopNavigation() }

        findViewById<View>(R.id.camStopTop).setOnClickListener { stopCameraAndHome() }
        findViewById<View>(R.id.camStop).setOnClickListener { stopCameraAndHome() }

        findViewById<View>(R.id.sosBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.sosSend).setOnClickListener { sendSOS() }

        findViewById<View>(R.id.setBack).setOnClickListener { show("home") }
        findViewById<View>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<View>(R.id.btnTestVoice).setOnClickListener {
            vibrateSosPattern()
            speaker.speak("Voice test. Both sound and vibration are working.")
        }

        findViewById<View>(R.id.themeBtn).setOnClickListener {
            prefs.themeDark = !prefs.themeDark
            applyNightMode()
            speaker.speak(if (prefs.themeDark) "Dark theme on." else "Light theme on.")
        }
        findViewById<View>(R.id.simpleBtn).setOnClickListener {
            prefs.simple = !prefs.simple
            applySimpleMode()
            speaker.speak(if (prefs.simple) "Simple view on." else "Simple view off.")
        }
    }

    private fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applySimpleMode() {
        val simple = prefs.simple
        listOf(R.id.verbGroup, R.id.sensGroup, R.id.rateLabel, R.id.setRate, R.id.chkStepFree,
            R.id.themeGroup).forEach { id ->
            findViewById<View>(id)?.visibility = if (simple) View.GONE else View.VISIBLE
        }
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
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startLocation()
                    startCamera()
                }
            }
            REQ_CAM -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) camera.start()
                else speaker.speak("Camera permission was denied. Obstacle detection will not work.")
            }
            REQ_MIC -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) voice.start()
                else speaker.speak("Microphone permission was denied. Voice commands will not work.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLocation = loc
                    updateGpsChip()
                }
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L).build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    private fun updateGpsChip() {
        val loc = lastLocation
        gpsChip.text = if (loc != null)
            " GPS %.4f, %.4f".format(loc.latitude, loc.longitude)
        else " GPS --"
    }

    private fun whereAmI() {
        val loc = lastLocation
        if (loc == null) {
            speaker.speak("I don't have your location yet. Please wait a moment and try again.")
            return
        }
        val result = Geo.whereAmIText(this, loc)
        val text = when (result) {
            "NO_INTERNET" -> "No internet access. I can't tell you where you are right now."
            "COORDS" -> "I can't determine your exact address, but your approximate coordinates are " +
                    "%.4f, %.4f.".format(loc.latitude, loc.longitude)
            else -> "$result. Approximate coordinates: %.4f, %.4f.".format(loc.latitude, loc.longitude)
        }
        speaker.speak(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun navigateTo(query: String) {
        val loc = lastLocation
        if (loc == null) {
            speaker.speak("I don't have your location yet. Please wait a moment and try again.")
            return
        }
        lifecycleScope.launch {
            speaker.speak("Searching for $query.")
            val dest = Geo.geocode(query)
            if (dest == null) {
                speaker.speak(if (!isOnline(this@MainActivity)) "I need an internet connection to search for places." else "I couldn't find $query.")
                return@launch
            }
            speaker.speak("Found ${dest.label}. Downloading walking route.")
            val route = Geo.fetchRoute(LatLng(loc.latitude, loc.longitude), dest)
            if (route == null) {
                speaker.speak(if (!isOnline(this@MainActivity))
                    "Navigation needs an internet connection to download the walking route. Everything else works offline."
                else "I couldn't get a walking route to ${dest.label}.")
                return@launch
            }
            activeNav = route
            show("nav")
            val total = Geo.routeLen(route.coords).toInt()
            speaker.speak("Starting navigation to ${dest.label}. Distance about $total meters. Walk straight to begin.")
            navStatus.text = "Navigating to ${dest.label} — ~$total m"
            handler.removeCallbacks(navTick)
            handler.post(navTick)
        }
    }

    private val navTick = object : Runnable {
        override fun run() {
            val r = activeNav ?: return
            val loc = lastLocation ?: run { handler.postDelayed(this, 3000); return }
            val pt = LatLng(loc.latitude, loc.longitude)
            val cur = r.steps.getOrNull(r.stepIdx)
            if (cur == null) { finishNav(); return }
            val d = Geo.hav(pt, LatLng(cur.lat, cur.lng)).toInt()
            val txt = Geo.maneuverText(cur)
            if (!r.announcedPre && d in 6..30) {
                speaker.speak("In about $d meters, $txt")
                r.announcedPre = true
            }
            if (d < 6 && !r.announcedNow) {
                speaker.speak(txt)
                r.announcedNow = true
            }
            if (d < 4) {
                r.stepIdx++
                r.announcedPre = false
                r.announcedNow = false
                if (r.stepIdx >= r.steps.size) { finishNav(); return }
            }
            navStatus.text = "Next: ${Geo.maneuverText(r.steps[r.stepIdx])} ($d m)"
            handler.postDelayed(this, 2500)
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
        navStatus.text = "Idle."
        show("home")
    }

    private fun stopCameraAndHome() {
        if (::camera.isInitialized) camera.stop()
        sonar.stop()
        show("home")
    }

    private fun describeAround() {
        if (!::camera.isInitialized) {
            speaker.speak("Detection is not running yet. Starting it now.")
            startCamera()
            return
        }
        val dets = camera.lastSeen
        val zone = camera.lastZone
        if (dets.isEmpty() && zone == null) {
            speaker.speak("I don't see any obstacles right now.")
            return
        }
        val sb = StringBuilder()
        dets.take(5).forEach { d ->
            val distTxt = d.dist?.let { " %.1f meters".format(it) } ?: ""
            sb.append("${d.label}$distTxt ${d.side}. ")
        }
        zone?.let { sb.append("Obstacle ahead ${"%.1f".format(it.dist)} meters. ") }
        val text = sb.toString().trim()
        speaker.speak(text)
        camStatus.text = text
    }

    /** "Read a sign" — uses the live camera frame cache, NOT PreviewView.getBitmap(). */
    private fun readSign() {
        val bmp = if (::camera.isInitialized) camera.lastFrame else null
        if (!::camera.isInitialized || !camera.running || bmp == null) {
            speaker.speak("Starting the camera so I can read.")
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

    private fun sendSOS() {
        if (prefs.contact.isEmpty()) {
            speaker.speak("No emergency contact set. Please add a contact number in settings.")
            show("settings")
            return
        }
        val loc = lastLocation
        val text = if (loc != null)
            "I need help! My approximate location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        else "I need help! (location unavailable)"
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${prefs.contact}"))
            i.putExtra("sms_body", text)
            startActivity(i)
            vibrateSosPattern()
            speaker.speak("Opening messages with your emergency text. Press send.")
        } catch (e: Exception) {
            speaker.speak("I couldn't open the messages app.")
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
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { show("sos"); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { whereAmI(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleCommand(raw: String) {
        val text = raw.lowercase()
        when {
            text.contains("where am i") || text.contains("my location") -> whereAmI()
            text.contains("navigate") || text.contains("take me") || text.contains("directions to") -> {
                val dest = text.removePrefix("navigate to").removePrefix("take me to")
                    .removePrefix("directions to").trim()
                if (dest.isEmpty()) speaker.speak("Where would you like to go?")
                else navigateTo(dest)
            }
            text.contains("read") -> readSign()
            text.contains("stop navigation") -> stopNavigation()
            text.contains("help") || text.contains("emergency") || text.contains("sos") -> sendSOS()
            text.contains("describe") || text.contains("around") -> describeAround()
            text.contains("camera") || text.contains("obstacle") -> { show("camera"); startCamera() }
            text.contains("battery") -> updateBatteryChip(true)
            text.contains("repeat") -> speaker.repeat()
            else -> speaker.speak("You said $text. Try: where am I, navigate to the market, read this, or help.")
        }
    }

    private fun loadSettingsUI() {
        findViewById<MaterialSwitch>(R.id.chkAudio)?.isChecked = prefs.audio
        findViewById<MaterialSwitch>(R.id.chkHaptics)?.isChecked = prefs.haptics
        findViewById<MaterialSwitch>(R.id.chkStepFree)?.isChecked = prefs.stepFree
        findViewById<MaterialSwitch>(R.id.chkSimple)?.isChecked = prefs.simple
        setContact.setText(prefs.contact)

        findViewById<RadioGroup>(R.id.themeGroup)?.check(if (prefs.themeDark) R.id.themeDark else R.id.themeLight)
        findViewById<RadioGroup>(R.id.verbGroup)?.check(
            when (prefs.verbosity) {
                "minimal" -> R.id.verbMinimal
                "full" -> R.id.verbVerbose
                else -> R.id.verbStandard
            }
        )
        findViewById<RadioGroup>(R.id.sensGroup)?.check(
            when (prefs.sensitivity) {
                "near" -> R.id.sensNear
                "far" -> R.id.sensFar
                else -> R.id.sensStandard
            }
        )
        val slider = findViewById<Slider>(R.id.setRate)
        slider?.value = prefs.rate.coerceIn(0.6f, 1.6f)
        updateRateLabel()
        slider?.addOnChangeListener { _, v, _ ->
            prefs.rate = v
            speaker.setRate(v)
            updateRateLabel()
        }

        findViewById<RadioGroup>(R.id.themeGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.themeDark = checkedId == R.id.themeDark
            applyNightMode()
        }
        findViewById<RadioGroup>(R.id.verbGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.verbosity = when (checkedId) {
                R.id.verbMinimal -> "minimal"
                R.id.verbVerbose -> "full"
                else -> "standard"
            }
        }
        findViewById<RadioGroup>(R.id.sensGroup)?.setOnCheckedChangeListener { _, checkedId ->
            prefs.sensitivity = when (checkedId) {
                R.id.sensNear -> "near"
                R.id.sensFar -> "far"
                else -> "standard"
            }
        }

        applySimpleMode()
    }

    private fun updateRateLabel() {
        findViewById<TextView>(R.id.rateLabel)?.text = "Speech speed  %.1f×".format(prefs.rate)
    }

    private fun saveSettings() {
        prefs.audio = findViewById<MaterialSwitch>(R.id.chkAudio)?.isChecked ?: true
        prefs.haptics = findViewById<MaterialSwitch>(R.id.chkHaptics)?.isChecked ?: true
        prefs.stepFree = findViewById<MaterialSwitch>(R.id.chkStepFree)?.isChecked ?: false
        prefs.simple = findViewById<MaterialSwitch>(R.id.chkSimple)?.isChecked ?: false
        prefs.contact = setContact.text?.toString()?.trim() ?: ""
        applySimpleMode()
        speaker.speak("Settings saved.")
    }

    private fun updateBatteryChip(forceSpeak: Boolean = false) {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        battChip.text = " $pct%"
        if (pct in 0..20 || forceSpeak) speaker.speak("Battery at $pct percent.")
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
            "3) Distances and obstacle detection are approximate.\n\n" +
            "4) Your camera and microphone stay on-device; only location requests go online for maps."
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Safety & Privacy")
            .setMessage(msg)
            .setPositiveButton("I understand") { d, _ ->
                prefs.agreed = true
                d.dismiss()
                playTutorial()
            }
            .setCancelable(false)
            .show()
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

    private fun vibrateSosPattern() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300, 200, 300), -1)
            }
        } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        pocketWatcher.start()
        fallDetector.start()
        if (cameraStartedOnce && (!::camera.isInitialized || !camera.running)) startCamera()
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
        voice.destroy()
        try { fused.removeLocationUpdates(locationCallback) } catch (e: Exception) { }
        speaker.shutdown()
    }
}

package com.DynnKyru.driveAware

import android.Manifest
import android.app.Dialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.DynnKyru.driveAware.Constants.LABELS_PATH
import com.DynnKyru.driveAware.Constants.MODEL_PATH
import com.DynnKyru.driveAware.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ToggleButton
import org.osmdroid.views.MapView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.location.Location
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.animation.OvershootInterpolator
import com.DynnKyru.driveAware.ui.SoundSettingsManager
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isNotificationEnabled = true // Default is ON (this should be changed)
    private var detector: Detector? = null
    private var reportImageView: ImageView? = null
    private var profileImageView: ImageView? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LAT_KEY = "Latitude"
    private val LON_KEY = "Longitude"
    private var selectedReportBitmap: Bitmap? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFERENCES_NAME = "AppPreferences"
    private val ALERT_KEY = "AlertState"
    private val NOTIFICATION_KEY = "NotificationState"
    private val NIGHT_MODE_KEY = "NightModeState"
    private val detectionRecords = mutableListOf<DetectionRecord>()
    var selectedDetectionRecord: DetectionRecord? = null
    private var lastMarkerTime = 0L  // For throttling red marker creation only
    private var detectionResultTextSheetView: TextView? = null
    private var isSignedOut = false


    // CardView and TextView for heads-up notification
    private lateinit var notificationBanner: CardView
    private lateinit var notificationText: TextView
    private lateinit var notificationBackground: CardView
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Location permission granted!", Toast.LENGTH_SHORT).show()

            } else {
                Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show()
            }
        }
    private fun getHazardIcon(detection: String): Int {
        return when (detection) {
            "Uneven-terrain", "Road-cracks"  -> R.drawable.ic_dia
            "Speed-Bumps" -> R.drawable.ic_bump
            "Manholes", "Puddle" -> R.drawable.ic_octa
            "Potholes" -> R.drawable.ic_pothole
            else -> R.drawable.ic_detecting
        }
    }
    // Enum class for severity levels
    enum class Severity(val color: Int) {
        LOW(R.color.light_green),
        MEDIUM(R.color.light_yellow),
        HIGH(R.color.light_orange),
        CRITICAL(R.color.light_red)
    }
    //soundmenyu
    private lateinit var soundButton: MaterialCardView
    private lateinit var soundIcon: ImageView
    private lateinit var voiceButton: MaterialCardView
    private lateinit var beepButton: MaterialCardView
    private lateinit var silentButton: MaterialCardView


    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityMainBinding.inflate(layoutInflater)
        sharedPreferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // detecting... bg
        setContentView(R.layout.activity_main)
        notificationBackground = findViewById(R.id.notificationBackground)
        notificationBackground.alpha = 1f
        notificationBackground.visibility = View.VISIBLE

        setupMapButton(binding.mapButton)
        setupSettingsButton(binding.settingsButton)


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    // Save exact location
                    sharedPreferences.edit()
                        .putFloat(LAT_KEY, it.latitude.toFloat())
                        .putFloat(LON_KEY, it.longitude.toFloat())
                        .apply()
                }
            }
        }
        enableEdgeToEdge()
        setContentView(binding.root)

        val isGpuToggle: ToggleButton = findViewById(R.id.isGpu)
        // Set up the listener for the GPU toggle button
        isGpuToggle.setOnCheckedChangeListener { buttonView: CompoundButton, isChecked: Boolean ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }
            // Change the background color of the ToggleButton based on the checked state
            val backgroundColor = if (isChecked) {
                ContextCompat.getColor(baseContext, R.color.light_blue) // On color
            } else {
                ContextCompat.getColor(baseContext, R.color.Trans_white) // Off color
            }

            // Update the backgroundTint using the appropriate color
            buttonView.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        }
        // SOoooooooooooooooooooooooooooooooooUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUND
        soundButton = findViewById(R.id.soundButton)
        soundIcon = findViewById(R.id.soundIcon)
        voiceButton = findViewById(R.id.voiceButton)
        beepButton = findViewById(R.id.beepButton)
        silentButton = findViewById(R.id.silentButton)

        // Load saved sound setting
        val savedMode = SoundSettingsManager.getCurrentSoundSetting(this)
        updateSoundIcon(savedMode)

        soundButton.setOnClickListener {
            toggleSoundMenu()
        }
        beepButton.setOnClickListener {
            selectSoundOption(SoundSettingsManager.SoundMode.BEEP)
        }
        voiceButton.setOnClickListener {
            selectSoundOption(SoundSettingsManager.SoundMode.VOICE)
        }

        silentButton.setOnClickListener {
            selectSoundOption(SoundSettingsManager.SoundMode.SILENT)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Initialize notification components
        notificationBanner = findViewById(R.id.detectionNotificationCard)
        notificationText = findViewById(R.id.detectionNotificationText)

        // Dismiss notification on tap
        findViewById<CardView>(R.id.detectionNotificationCard)?.setOnClickListener {
            hideNotification()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            reportImageView?.setImageURI(it)

            try {
                val inputStream = contentResolver.openInputStream(it)
                selectedReportBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                // ✅ Add this log line
                Log.d("ImagePicker", "Bitmap set? ${selectedReportBitmap != null}")
            } catch (e: Exception) {
                Log.e("ImagePicker", "Failed to decode bitmap: ${e.message}", e)
            }
        }
    }


    private val profileImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { profileImageView?.setImageURI(it) }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }
    private fun saveBitmapToCache(bitmap: Bitmap, filename: String): String {
        val file = File(cacheDir, filename)
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.flush()
        outputStream.close()
        return file.absolutePath
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            onDetectWithBitmap(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onDetectWithBitmap(rotatedBitmap: Bitmap) {
        if (isSignedOut) return  // Prevent TFLite from running after sign-out
        detector?.detect(rotatedBitmap)

        val now = System.currentTimeMillis()
        if (now - lastMarkerTime < 5000) {
            Log.d("RedMarkerThrottle", "⏳ Skipping red marker — still in cooldown")
            return
        }
        lastMarkerTime = now

        val timestamp = now
        val imagePath = saveBitmapToCache(rotatedBitmap, "detection_$timestamp.png")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val latitude = location?.latitude ?: 0.0
                val longitude = location?.longitude ?: 0.0

                val jitter = (0..10).random() * 0.000002
                val jitteredLat = latitude + jitter
                val jitteredLon = longitude + jitter

                val record = DetectionRecord(
                    anomalyType = "Unknown",
                    imagePath = imagePath,
                    latitude = jitteredLat,
                    longitude = jitteredLon,
                    timestamp = timestamp
                )

                detectionRecords.add(record)
                Log.d("DetectionLog", "🆕 Red marker added at: $jitteredLat, $jitteredLon")
            }

        } else {
            val record = DetectionRecord(
                anomalyType = "Unknown",
                imagePath = imagePath,
                latitude = 0.0,
                longitude = 0.0,
                timestamp = timestamp
            )
            detectionRecords.add(record)
            Log.w("DetectionLog", "⚠️ Detection saved without GPS")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null

        // Your existing cleanup
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }

    // Mapping detected objects to severity levels
    private fun getSeverity(detection: String): Severity {
        return when (detection) {
            "Uneven-terrain", "Road-cracks" -> Severity.LOW
            "Speed-Bumps" -> Severity.MEDIUM
            "Manholes", "Puddle" -> Severity.HIGH
            "Potholes" -> Severity.CRITICAL
            else -> Severity.LOW
        }
    }

    private var lastDetectionTime = 0L
    private val detectionInterval = 2000 // Adjust time in milliseconds (e.g., 4000ms = 4 seconds)

    fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastDetectionTime < detectionInterval) return
        lastDetectionTime = currentTime

        runOnUiThread {
            if (boundingBoxes.isNotEmpty()) {
                val detectedBox = boundingBoxes[0]
                val detectedClass = detectedBox.clsName
                val confidence = String.format("%.2f", detectedBox.cnf * 100)
                val severity = getSeverity(detectedClass)

                val detectionText = "$detectedClass: $confidence%"

                showNotification("$detectedClass detected! Severity: ${severity.name}")

                // Check if alert switch is ON
                val isAlertEnabled = sharedPreferences.getBoolean(ALERT_KEY, true)
                mediaPlayer?.release()
                mediaPlayer = null
                val selectedMode = SoundSettingsManager.getCurrentSoundSetting(this)
                // Play sound only if alerts are enabled AND severity is MEDIUM or HIGH
                if (isAlertEnabled && (severity == Severity.CRITICAL || severity == Severity.HIGH)) {
                    when (selectedMode) {
                        SoundSettingsManager.SoundMode.BEEP -> {
                            mediaPlayer = MediaPlayer.create(this, R.raw.notif_sound)
                            mediaPlayer?.start()
                        }
                        SoundSettingsManager.SoundMode.VOICE -> {
                            mediaPlayer = MediaPlayer.create(this, R.raw.notif_voice)
                            mediaPlayer?.start()
                        }
                        SoundSettingsManager.SoundMode.SILENT -> {
                            // Do nothing
                        }
                    }
                }

                if (severity == Severity.CRITICAL) vibratePhone()

                // Update UI (medyo oke na
                binding.overlay.apply {
                    setResults(listOf(detectedBox))
                    invalidate()
                }
            } else {
                hideNotification()
                detectionResultTextSheetView?.text = "Detecting"
            }

        }
    }

    private var isSoundMenuExpanded = false
    private fun toggleSoundMenu() {
        val options = listOf(voiceButton, beepButton, silentButton)
        if (::voiceButton.isInitialized && ::beepButton.isInitialized && ::silentButton.isInitialized) {
            if (isSoundMenuExpanded) {
                options.forEach { button ->
                    button.animate()
                        .translationX(100f)
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { button.visibility = View.GONE }
                        .start()
                }
            } else {
                val baseOffset = 70f
                options.forEachIndexed { index, button ->
                    button.visibility = View.VISIBLE
                    button.alpha = 0f
                    button.translationX = baseOffset * (index + 1)
                    button.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }
            }
            isSoundMenuExpanded = !isSoundMenuExpanded
        } else {
            Log.e("SoundMenu", "Buttons not initialized!")
        }
    }

    private fun selectSoundOption(mode: SoundSettingsManager.SoundMode) {
        SoundSettingsManager.saveSoundSetting(this, mode)
        updateSoundIcon(mode)
        toggleSoundMenu()
        Toast.makeText(this, "${mode.name} Mode Selected", Toast.LENGTH_SHORT).show()
    }

    private fun updateSoundIcon(mode: SoundSettingsManager.SoundMode) {
        val iconRes = when (mode) {
            SoundSettingsManager.SoundMode.BEEP -> R.drawable.ic_beep
            SoundSettingsManager.SoundMode.VOICE -> R.drawable.ic_voice
            SoundSettingsManager.SoundMode.SILENT -> R.drawable.ic_silent
        }
        soundIcon.setImageResource(iconRes)
    }


    private fun vibratePhone() {
        if (!isNotificationEnabled) return // 🚨 Stop vibration if notifications are off

        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            val vibrationEffect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(vibrationEffect)
        }
    }

    fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.apply {
                setResults(emptyList()) // Clear bounding boxes
                invalidate() // Redraw overlay
            }
        }
    }

    private lateinit var detectionIcon: ImageView


    private fun showNotification(detection: String) {
        Log.d("Notification", "isNotificationEnabled = $isNotificationEnabled")

        if (!isNotificationEnabled) {
            Log.d("Notification", "Notification blocked because isNotificationEnabled = false")
            return
        }
        val detectedClass = detection.split(" ")[0]
        val severity = getSeverity(detectedClass)

        detectionIcon = findViewById(R.id.detectionIcon)
        val iconRes = getHazardIcon(detectedClass)
        detectionIcon.setImageResource(iconRes)

        notificationBanner.setCardBackgroundColor(ContextCompat.getColor(this, severity.color))
        notificationText.text = detection
        notificationBanner.alpha = 0f
        notificationBanner.visibility = View.VISIBLE

        notificationBanner.apply {
            scaleX = 0.7f
            scaleY = 0.7f
            alpha = 0f
            visibility = View.VISIBLE
        }

        notificationBanner.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setInterpolator(OvershootInterpolator()) // This gives the bounce
            .setDuration(300)
            .withEndAction {
                notificationBanner.postDelayed({
                    hideNotification()
                }, 4000)
            }
            .start()
    }

    private fun hideNotification() {
        //bannersssgrgrgr
        notificationBanner.animate()
            .alpha(1f)
            .setDuration(6000)
            .withEndAction {
                notificationBanner.visibility = View.GONE
            }
            .start()
    }
    //to help show the map
    private fun showMapContainerSheet() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.mapcontainer)
            setCancelable(true)
        }

        // Fix window settings here
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes.windowAnimations = R.style.DialogAnimation
        }

        val mapView = dialog.findViewById<MapView>(R.id.map)
        val backBtn = dialog.findViewById<ImageView>(R.id.backBtn)

        val lat = sharedPreferences.getFloat(LAT_KEY, 14.5995f).toDouble()
        val lon = sharedPreferences.getFloat(LON_KEY, 120.9842f).toDouble()

        MapManager.clearSearchState()
        loadReportedDetectionsFromFirebase {
            MapManager.setupMap(this, mapView, lat, lon, detectionRecords)
        }
        backBtn.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { mapView.onResume() }
        dialog.setOnDismissListener { mapView.onPause() }
        dialog.show()
    }
    // Pass in the view if you want, but you can also just look it up by ID.
    private fun setupMapButton(btn: View? = null) {
        val mapBtn = (btn ?: findViewById<MaterialCardView>(R.id.mapButton))
        mapBtn.setOnClickListener { showMapContainerSheet() }
    }
    // SETTINGS BUTTON
    private fun setupSettingsButton(btn: MaterialCardView) {
        btn.setOnClickListener {
            showSettingsMenuDialog()
        }
    }



    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }
    private fun loadReportedDetectionsFromFirebase(onComplete: () -> Unit) {
        val db = FirebaseFirestore.getInstance()

        db.collection("hazardReports")
            .get()
            .addOnSuccessListener { documents ->
                for (doc in documents) {
                    val type = doc.getString("type") ?: "Unknown"
                    val address = doc.getString("location") ?: ""
                    val lat = doc.getDouble("latitude") ?: 0.0
                    val lon = doc.getDouble("longitude") ?: 0.0
                    val date = doc.getString("date") ?: ""
                    val time = doc.getString("time") ?: ""
                    val imageUrl = doc.getString("imageUrl") ?: ""
                    val timestamp = System.currentTimeMillis()

                    // Remove duplicate red markers at this location
                    //detectionRecords.removeAll { local ->
                    //    !local.isReported &&
                    //           Math.abs(local.latitude - lat) < 0.000001 &&
                    //           Math.abs(local.longitude - lon) < 0.000001
                    //}

                    val record = DetectionRecord(
                        anomalyType = type,
                        imagePath = imageUrl,
                        latitude = lat,
                        longitude = lon,
                        timestamp = timestamp,
                        isReported = true,
                        address = address
                    )

                    detectionRecords.add(record)
                }

                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseLoad", "Failed to load reports: ${e.message}", e)
                onComplete()
            }

    }

    //removing this but we might need the code for mappings later

    /*
    codes for map searching in the future
    private fun showBottomDialog() {
        val lat = sharedPreferences.getFloat(LAT_KEY, 14.5995f) // Default to Manila
        val lon = sharedPreferences.getFloat(LON_KEY, 120.9842f)
        // Set initial user location
        MapManager.clearSearchState()  // 👈 Add this right before setupMap
        loadReportedDetectionsFromFirebase {
            MapManager.setupMap(this, mapView, lat.toDouble(), lon.toDouble(), detectionRecords)
        }
        // Handle search input
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    MapManager.searchLocation(this@MainActivity, mapView, query, detectionRecords)
                }
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty() && MapManager.isSearchActive()) {
                    MapManager.resetToUserLocation(this@MainActivity, mapView, detectionRecords)
                    MapManager.clearSearchState()
                }
                return true
            }
        })
    }*/

    private fun loadSwitchStates(
        alertSwitch: Switch,
        notificationSwitch: Switch,

        ) {
        val alertEnabled = sharedPreferences.getBoolean(ALERT_KEY, false)
        val notificationsEnabled = sharedPreferences.getBoolean(NOTIFICATION_KEY, true)


        alertSwitch.isChecked = alertEnabled
        notificationSwitch.isChecked = notificationsEnabled


        isNotificationEnabled = notificationsEnabled // Make sure the flag is set correctly

    }


    private fun showSettingsMenuDialog() {
        val dialog = createDialog(R.layout.settings)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val alertModeLayout: LinearLayout? = dialog.findViewById(R.id.layoutAlertMode)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        val helpLayout: LinearLayout? = dialog.findViewById(R.id.layoutHelp)
        val profileLayout: LinearLayout? = dialog.findViewById(R.id.layoutProfile)
        val reportLayout: LinearLayout? = dialog.findViewById(R.id.layoutReport)

        val alertSwitch: Switch = dialog.findViewById(R.id.alertSwitch)
        val notificationSwitch: Switch = dialog.findViewById(R.id.notificationSwitch)

        // Call load states function
        loadSwitchStates(alertSwitch, notificationSwitch)

        // Set listeners as before, using the shared_preferences logic
        alertSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(ALERT_KEY, isChecked).apply()
        }
        notificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            isNotificationEnabled = isChecked
            sharedPreferences.edit().putBoolean(NOTIFICATION_KEY, isChecked).apply()
        }
        profileLayout?.setOnClickListener {
            dialog.dismiss()
            showProfileMenuDialog()
        }
        reportLayout?.setOnClickListener {
            dialog.dismiss()
            showReportMenuDialog()
        }
        backButton?.setOnClickListener {
            dialog.dismiss()
        }
        alertModeLayout?.setOnClickListener {
            dialog.dismiss()
            showAlertModeDialog()
        }
        helpLayout?.setOnClickListener {
            dialog.dismiss()
            showHelpdialog()
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    private fun showHelpdialog() {
        val dialog = createDialog(R.layout.help)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        val helpCamera: LinearLayout? = dialog.findViewById(R.id.HelpCameraLayout)
        val helpreport: LinearLayout? = dialog.findViewById(R.id.HelpReportLayout)
        val helpprofile: LinearLayout? = dialog.findViewById(R.id.HelpProfileLayout)
        val helpsettings: LinearLayout? = dialog.findViewById(R.id.HelpSettingsLayout)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog() // Go back to main menu
        }

        helpCamera?.setOnClickListener {
            dialog.dismiss()
            showHelpCameraActivity()
        }

        helpreport?.setOnClickListener {
            dialog.dismiss()
            showHelpReporthazard()
        }

        helpprofile?.setOnClickListener {
            dialog.dismiss()
            showHelpprofile()
        }

        helpsettings?.setOnClickListener {
            dialog.dismiss()
            showHelpsettings()
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showHelpCameraActivity() {
        val dialog = createDialog(R.layout.help_cameractivity)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showHelpdialog() // Go back to main menu
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showHelpsettings() {
        val dialog = createDialog(R.layout.help_settings)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showHelpdialog() // Go back to main menu
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showHelpprofile() {
        val dialog = createDialog(R.layout.help_profile)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showHelpdialog() // Go back to main menu
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showHelpReporthazard() {
        val dialog = createDialog(R.layout.help_reporthazard)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showHelpdialog() // Go back to main menu
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showAlertModeDialog() {
        val dialog = createDialog(R.layout.settings_alertmode)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        val roadCrackCheckbox = dialog.findViewById<CheckBox>(R.id.RoadcrackCheckbox)
        val roadPotholeCheckbox = dialog.findViewById<CheckBox>(R.id.RoadpotholeCheckbox)
        val speedBumpCheckbox = dialog.findViewById<CheckBox>(R.id.SpeedbumpCheckbox)
        val roadManholeCheckbox = dialog.findViewById<CheckBox>(R.id.RoadmanholeCheckbox)
        val unfinishedPavementCheckbox = dialog.findViewById<CheckBox>(R.id.UnfinishedpavementCheckbox)
        val puddlesCheckbox = dialog.findViewById<CheckBox>(R.id.puddlesCheckbox)
        val detectButton = dialog.findViewById<Button>(R.id.detectButton)

        // Load saved states from SharedPreferences
        roadCrackCheckbox.isChecked = sharedPreferences.getBoolean("road_crack", false)
        roadPotholeCheckbox.isChecked = sharedPreferences.getBoolean("road_pothole", false)
        speedBumpCheckbox.isChecked = sharedPreferences.getBoolean("speed_bump", false)
        roadManholeCheckbox.isChecked = sharedPreferences.getBoolean("road_manhole", false)
        unfinishedPavementCheckbox.isChecked = sharedPreferences.getBoolean("unfinished_pavement", false)
        puddlesCheckbox.isChecked = sharedPreferences.getBoolean("puddles", false)

        detectButton.setOnClickListener {
            val selectedClasses = mutableListOf<String>()
            val editor = sharedPreferences.edit()

            editor.putBoolean("road_crack", roadCrackCheckbox.isChecked)
            editor.putBoolean("road_pothole", roadPotholeCheckbox.isChecked)
            editor.putBoolean("speed_bump", speedBumpCheckbox.isChecked)
            editor.putBoolean("road_manhole", roadManholeCheckbox.isChecked)
            editor.putBoolean("unfinished_pavement", unfinishedPavementCheckbox.isChecked)
            editor.putBoolean("puddles", puddlesCheckbox.isChecked)

            if (roadCrackCheckbox.isChecked) selectedClasses.add("Road-cracks")
            if (roadPotholeCheckbox.isChecked) selectedClasses.add("Potholes")
            if (speedBumpCheckbox.isChecked) selectedClasses.add("Speed-bumps")
            if (roadManholeCheckbox.isChecked) selectedClasses.add("Manholes")
            if (unfinishedPavementCheckbox.isChecked) selectedClasses.add("Unfinished pavements")
            if (puddlesCheckbox.isChecked) selectedClasses.add("Puddle")

            editor.apply()

            detector?.setTargetClasses(selectedClasses)
            dialog.dismiss()
        }

        backButton?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog() // Go back to main menu
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showProfileMenuDialog() {
        val dialog = createDialog(R.layout.profile)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val userDetailsLayout: LinearLayout? = dialog.findViewById(R.id.layoutuserdetails)
        val signOutLayout: LinearLayout? = dialog.findViewById(R.id.layoutsignout)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        userDetailsLayout?.setOnClickListener {
            dialog.dismiss()
            showUserDetailsDialog()
        }

        signOutLayout?.setOnClickListener {
            isSignedOut = true // prevent detection during shutdown
            cameraExecutor.shutdownNow() // immediately stop analyzing frames

            FirebaseAuth.getInstance().signOut()
            dialog.dismiss()

            val intent = Intent(this, LoginNSignup::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra("showSignup", false)
            startActivity(intent)
            finish()
        }


        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()

        backButton?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog()
        }
    }

    private fun showUserDetailsDialog() {
        val dialog = createDialog(R.layout.profile_userdetails)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        val userPicture: ImageView? = dialog.findViewById(R.id.UserPicture)
        // Click to change image
        userPicture?.setOnClickListener {
            profileImageView = it as ImageView
            profileImagePickerLauncher.launch("image/*")
        }

        backButton?.setOnClickListener {
            dialog.dismiss()
            showProfileMenuDialog()
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    fun showReportMenuDialog() {
        val dialog = createDialog(R.layout.report_hazard)
        dialog.window?.apply{attributes.windowAnimations = R.style.DialogAnimation}
        // Top buttons
        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)

        // Info TextViews
        val hazardTypeText: TextView = dialog.findViewById(R.id.typeofhazard)
        val locationText: TextView = dialog.findViewById(R.id.location)
        val timeText: TextView = dialog.findViewById(R.id.time)
        val dateText: TextView = dialog.findViewById(R.id.date)

        // Auto-fill location
        val lat = sharedPreferences.getFloat(LAT_KEY, 14.5995f)
        val lon = sharedPreferences.getFloat(LON_KEY, 120.9842f)
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            val addresses = geocoder.getFromLocation(lat.toDouble(), lon.toDouble(), 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0].getAddressLine(0)
                locationText.text = address
            } else {
                locationText.text = "Unknown location (Lat: $lat, Lon: $lon)"
            }
        } catch (e: Exception) {
            locationText.text = "Location unavailable"
            Log.e("Geocoder", "Failed to get address: ${e.message}", e)
        }

        // Auto-fill date and time
        val currentDate = java.text.SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(Date())
        val currentTime = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        dateText.text = currentDate
        timeText.text = currentTime

        // Image picker
        val reportImage: ImageView = dialog.findViewById(R.id.reportImage)
        reportImageView = reportImage
        selectedDetectionRecord?.imagePath?.let { path ->
            val imageFile = File(path)
            if (imageFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                reportImage.setImageBitmap(bitmap)
                selectedReportBitmap = bitmap
                Log.d("AutoImageLoad", " Loaded image from $path")
            } else {
                Log.w("AutoImageLoad", " Image file does not exist at $path")
            }
        }

        reportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // CheckBoxes
        val roadCrackCheckBox: CheckBox = dialog.findViewById(R.id.RoadcrackcheckBox)
        val roadPotholeCheckBox: CheckBox = dialog.findViewById(R.id.RoadpotholeCheckbox)
        val speedBumpCheckBox: CheckBox = dialog.findViewById(R.id.SpeedbumpCheckbox)
        val roadManholeCheckBox: CheckBox = dialog.findViewById(R.id.RoadmanholeCheckbox)
        val unfinishedPavementCheckBox: CheckBox = dialog.findViewById(R.id.UnfinishedpavementCheckbox)

        // Buttons
        val reportButton: Button = dialog.findViewById(R.id.Reportbutton)
        val viewHistoryButton: Button = dialog.findViewById(R.id.ViewhistoryButton) // New line

        reportButton.setOnClickListener {
            // Dynamically set hazard type from checkboxes
            val selectedTypes = mutableListOf<String>()
            if (roadCrackCheckBox.isChecked) selectedTypes.add("Road Crack")
            if (roadPotholeCheckBox.isChecked) selectedTypes.add("Pothole")
            if (speedBumpCheckBox.isChecked) selectedTypes.add("Speed Bump")
            if (roadManholeCheckBox.isChecked) selectedTypes.add("Manhole")
            if (unfinishedPavementCheckBox.isChecked) selectedTypes.add("Unfinished Pavement")

            val hazardType = if (selectedTypes.isNotEmpty()) {
                selectedTypes.joinToString(", ")
            } else {
                "Unknown"
            }

            hazardTypeText.text = hazardType

            val location = locationText.text.toString()
            val time = timeText.text.toString()
            val date = dateText.text.toString()
            val bitmap = selectedReportBitmap

            if (bitmap == null) {
                Toast.makeText(this, "Please select an image before reporting", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            showReportVerificationDialog(hazardType, location, time, date, bitmap)
        }
        // View History Button Action
        viewHistoryButton.setOnClickListener {
            dialog.dismiss()
            showReportHistoryDialog()
        }
        // Back/cancel
        backButton.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog()
        }
        cancelMenuButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    fun showReportedInfoDialog(record: DetectionRecord) {
        val message = """
        🛠 Type: ${record.anomalyType}
        📍 Location: ${record.address.ifEmpty { "Unknown" }}
        📅 Date: ${SimpleDateFormat("MMMM dd yyyy", Locale.getDefault()).format(Date(record.timestamp))}
        🕒 Time: ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(record.timestamp))}
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Reported Anomaly")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showReportVerificationDialog(
        hazardType: String,
        location: String,
        time: String,
        date: String,
        imageBitmap: Bitmap?
    ) {
        val dialog = createDialog(R.layout.report_verification)

        // Top Buttons
        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)

        // Info Fields
        val roadTypeText: TextView = dialog.findViewById(R.id.Roadtype)
        val locationText: TextView = dialog.findViewById(R.id.location)
        val timeText: TextView = dialog.findViewById(R.id.time)
        val dateText: TextView = dialog.findViewById(R.id.date)
        val reportImage: ImageView = dialog.findViewById(R.id.reportImage)

        // Fill in the data
        roadTypeText.text = hazardType
        locationText.text = location
        timeText.text = time
        dateText.text = date

        imageBitmap?.let {
            reportImage.setImageBitmap(it)
        }

        // Action Buttons
        val checkButton: Button = dialog.findViewById(R.id.checkButton)
        val denyButton: Button = dialog.findViewById(R.id.denyButton)

        // Set button actions
        backButton.setOnClickListener {
            dialog.dismiss()
            showReportMenuDialog()
        }

        cancelMenuButton.setOnClickListener {
            dialog.dismiss()
        }

        topCenterButton.setOnClickListener {
            dialog.dismiss()
        }

        checkButton.setOnClickListener {
            saveReportToFirebase(hazardType, location, date, time, imageBitmap)
            Toast.makeText(dialog.context, "Report Approved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showReportHistoryDialog()
        }

        denyButton.setOnClickListener {
            Toast.makeText(dialog.context, "Report Denied", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showReportMenuDialog()
        }

        dialog.show()
    }

    private fun saveReportToFirebase(
        type: String,
        location: String,
        date: String,
        time: String,
        imageBitmap: Bitmap?
    ) {
        val db = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance().reference
        val reportId = db.collection("hazardReports").document().id

        // Get lat/lon from selected detection
        val lat = selectedDetectionRecord?.latitude ?: 0.0
        val lon = selectedDetectionRecord?.longitude ?: 0.0
        val geoPoint = com.google.firebase.firestore.GeoPoint(lat, lon)

        Log.d("FirebaseUpload", "Bitmap is null? ${imageBitmap == null}")

        val saveToFirestore: (String) -> Unit = { imageUrl ->
            val reportData = mapOf(
                "type" to type,
                "location" to location,
                "latitude" to lat,
                "longitude" to lon,
                "locationPoint" to geoPoint,
                "date" to date,
                "time" to time,
                "imageUrl" to imageUrl
            )

            db.collection("hazardReports").document(reportId)
                .set(reportData)
                .addOnSuccessListener {
                    Toast.makeText(this, "Report saved!", Toast.LENGTH_SHORT).show()
                    Log.d("FirebaseReport", " Report saved with ID: $reportId")

                    // Mark detection as reported
                    selectedDetectionRecord?.isReported = true
                    Log.d("ReportStatus", " Detection marked as reported: ${selectedDetectionRecord?.anomalyType}")
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save report", Toast.LENGTH_SHORT).show()
                    Log.e("FirebaseReport", "Firestore write failed: ${it.message}", it)
                }
        }

        if (imageBitmap != null) {
            val baos = ByteArrayOutputStream()
            imageBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val imageData = baos.toByteArray()

            Log.d("FirebaseUpload", "Image byte size: ${imageData.size}")

            val imageRef = storage.child("report_images/$reportId.jpg")
            Log.d("FirebaseUpload", "Starting upload to report_images/$reportId.jpg")

            val uploadTask = imageRef.putBytes(imageData)
            uploadTask
                .addOnSuccessListener {
                    Log.d("FirebaseUpload", "Image upload succeeded")
                    imageRef.downloadUrl
                        .addOnSuccessListener { uri ->
                            Log.d("FirebaseUpload", "Download URL obtained: $uri")
                            saveToFirestore(uri.toString())
                        }
                        .addOnFailureListener {
                            Log.e("FirebaseUpload", "Failed to get image URL: ${it.message}", it)
                            saveToFirestore("")
                        }
                }
                .addOnFailureListener {
                    Log.e("FirebaseUpload", "Image upload failed: ${it.message}", it)
                    Toast.makeText(this, "Image upload failed", Toast.LENGTH_SHORT).show()
                    saveToFirestore("")
                }
        } else {
            Toast.makeText(this, "No image selected to upload", Toast.LENGTH_SHORT).show()
            saveToFirestore("")
        }
    }



    private fun showReportHistoryDialog() {
        val dialog = createDialog(R.layout.report_history)

        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)
        val viewSuggestedButton: Button = dialog.findViewById(R.id.viewSuggestedButton)
        val historyContainer = dialog.findViewById<LinearLayout>(R.id.historyContainer)

        val db = FirebaseFirestore.getInstance()
        db.collection("hazardReports")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Toast.makeText(this, "Error loading history", Toast.LENGTH_SHORT).show()
                    Log.e("FirebaseHistory", "Listen failed.", e)
                    return@addSnapshotListener
                }

                historyContainer.removeAllViews()

                if (snapshots != null && !snapshots.isEmpty) {
                    for (document in snapshots) {
                        val view = layoutInflater.inflate(R.layout.report_history_item, historyContainer, false)

                        view.findViewById<TextView>(R.id.roadType).text = document.getString("type") ?: "Unknown"
                        view.findViewById<TextView>(R.id.date).text = document.getString("date") ?: "-"
                        view.findViewById<TextView>(R.id.timeText).text = document.getString("time") ?: "-" // Replace "severity" with time
                        view.findViewById<TextView>(R.id.location).text = document.getString("location") ?: "-"


                        val imageUrl = document.getString("imageUrl")
                        val imageView = view.findViewById<ImageView>(R.id.reportImage)
                        if (!imageUrl.isNullOrEmpty()) {
                            Glide.with(this).load(imageUrl).into(imageView)
                        }

                        historyContainer.addView(view)
                    }
                } else {
                    val emptyText = TextView(this).apply {
                        text = "No reports available"
                        setPadding(32, 32, 32, 32)
                        textSize = 16f
                    }
                    historyContainer.addView(emptyText)
                }
            }

        backButton.setOnClickListener { dialog.dismiss() }
        cancelMenuButton.setOnClickListener { dialog.dismiss() }
        topCenterButton.setOnClickListener { dialog.dismiss() }
        viewSuggestedButton.setOnClickListener {
            Toast.makeText(dialog.context, "Viewing Suggested Reports", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }



    fun Alertswitch(view: View) {
        val switch = view as Switch
        val isChecked = switch.isChecked
        Toast.makeText(this, if (isChecked) "Alerts Enabled" else "Alerts Disabled", Toast.LENGTH_SHORT).show()
        // Save state to SharedPreferences
        sharedPreferences.edit().putBoolean(ALERT_KEY, isChecked).apply()
    }

    fun Notificationsswitch(view: View) {
        val switch = view as Switch
        isNotificationEnabled = switch.isChecked // Update the flag

        if (!isNotificationEnabled) {
            hideNotification() // Hide any active notifications
        }

        Toast.makeText(
            this,
            if (isNotificationEnabled) "Notifications Enabled" else "Notifications Disabled",
            Toast.LENGTH_SHORT
        ).show()
        // Save state to SharedPreferences
        sharedPreferences.edit().putBoolean(NOTIFICATION_KEY, isNotificationEnabled).apply()
        Log.d("NotificationSwitch", "isNotificationEnabled = $isNotificationEnabled") // Debugging log
    }


    fun Nightmodeswitch(view: View) {
        val switch = view as Switch
        val isChecked = switch.isChecked
        Toast.makeText(this, if (isChecked) "Night Mode Enabled" else "Night Mode Disabled", Toast.LENGTH_SHORT).show()
    }
    fun Context.createDialog(layoutId: Int): Dialog {
        val dialog = Dialog(this)
        dialog.setContentView(layoutId)

        // Optional: Make background transparent
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Optional: Set layout size
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Optional: Disable outside touch to dismiss
        dialog.setCancelable(true)

        return dialog
    }

    private fun setupDialogWindow(dialog: Dialog) {
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes.windowAnimations = R.style.DialogAnimation
            setGravity(Gravity.BOTTOM)
        }
    }
}
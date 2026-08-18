package com.example.arhud

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.arhud.ui.theme.ARHUDTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedAlertSeverity
import com.google.android.libraries.navigation.SpeedometerUiOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NavigationActivity : ComponentActivity() {

    private lateinit var navigationView: NavigationView
    private var navigator: Navigator? = null
    private lateinit var bleManager: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager.getInstance(this)

        // Initialize Navigation View
        navigationView = layoutInflater.inflate(R.layout.navigation_view_layout, null) as NavigationView
        navigationView.onCreate(savedInstanceState)
        
        // Enable speedometer and speed limit display to ensure the SDK tracks this data
        navigationView.setSpeedometerEnabled(true)
        navigationView.setSpeedLimitIconEnabled(true)

        // Capture 60 FPS vehicle facing from the map camera in real time
        navigationView.getMapAsync { googleMap ->
            googleMap.setOnCameraMoveListener {
                val cameraBearing = googleMap.cameraPosition.bearing
                var ccwFacing = (360f - cameraBearing) % 360f
                if (ccwFacing < 0) ccwFacing += 360f
                NavUpdateService.lastKnownCarFacingCcw = ccwFacing
            }
        }

        checkAndRequestPermissions()
        runDirectApiDiagnosis()

        enableEdgeToEdge()
        setContent {
            val bleStatus by bleManager.status.collectAsState()
            val debugData by bleManager.hudDebugData.collectAsState()
            val prefs = remember { getSharedPreferences("ar_hud_config", Context.MODE_PRIVATE) }
            var displayHudSimulator by remember {
                mutableStateOf(prefs.getBoolean("key_display_hud_simulator", true))
            }
            var devMode by remember {
                mutableStateOf(prefs.getInt("key_dev_mode_selected", 3))
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        displayHudSimulator = prefs.getBoolean("key_display_hud_simulator", true)
                        devMode = prefs.getInt("key_dev_mode_selected", 3)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            ARHUDTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        NavigationContent(
                            navigationView = navigationView,
                            bleStatus = bleStatus,
                            debugData = debugData,
                            displayHudSimulator = displayHudSimulator,
                            devMode = devMode,
                            onStartNavigation = { destination ->
                                startNavigation(destination)
                            },
                            onExitNavigation = {
                                navigator?.let { nav ->
                                    if (nav.isGuidanceRunning) {
                                        nav.stopGuidance()
                                    }
                                    nav.clearDestinations()
                                    nav.simulator.pause()
                                }
                                navigationView.getMapAsync { googleMap ->
                                    googleMap.clear()
                                }
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            initializeNavigationApi()
        }
    }

    private fun initializeNavigationApi() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)
        if (resultCode != ConnectionResult.SUCCESS) {
            availability.getErrorDialog(this, resultCode, 0)?.show()
            return
        }

        val prefs = getSharedPreferences("ar_hud_config", Context.MODE_PRIVATE)
        val devMode = prefs.getInt("key_dev_mode_selected", 3)

        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            @SuppressLint("MissingPermission")
            override fun onNavigatorReady(newNavigator: Navigator) {
                navigator = newNavigator
                newNavigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)

                // Register for the official Turn-by-Turn data feed
                val options = NavigationUpdatesOptions.Builder()
                    .setNumNextStepsToPreview(1)
                    .build()

                newNavigator.registerServiceForNavUpdates(
                    packageName,
                    NavUpdateService::class.java.name,
                    options
                )

                navigationView.getMapAsync { googleMap ->
                    if (ContextCompat.checkSelfPermission(this@NavigationActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        googleMap.isMyLocationEnabled = true
                    }
                }

                if (devMode == 1 || devMode == 2) {
                    // Mode 1 & 2: Set simulated origin
                    try {
                        val simulatedOrigin = LatLng(-33.9399, 151.1753)
                        newNavigator.simulator.setUserLocation(simulatedOrigin)
                        navigationView.getMapAsync { googleMap ->
                            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(simulatedOrigin, 15f))
                        }
                    } catch (e: Exception) {
                        Log.e("NavDebug", "Failed to inject simulated origin location", e)
                    }
                } else {
                    // Mode 3: Normal mode - Use real GPS location
                    try {
                        newNavigator.simulator.unsetUserLocation()
                    } catch (ignored: Exception) {}
                }
                Toast.makeText(this@NavigationActivity, "Navigator Ready", Toast.LENGTH_SHORT).show()
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                Log.e("NavDebug", "getNavigator onError - errorCode=$errorCode")
            }
        })
    }

    private fun startNavigation(destinationQuery: String) {
        val currentNavigator = navigator ?: run {
            Toast.makeText(this, "Navigator not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("ar_hud_config", Context.MODE_PRIVATE)
        val devMode = prefs.getInt("key_dev_mode_selected", 3)

        if (devMode == 1) {
            // Mode 1: Simulated Navigation (Fixed Origin & Fixed Destination)
            val fixedOrigin = LatLng(-33.9399, 151.1753)
            val fixedDestination = LatLng(-33.8731, 151.2063)

            try {
                currentNavigator.simulator.setUserLocation(fixedOrigin)
            } catch (e: Exception) {
                Log.e("NavDebug", "Failed to set fixed simulated origin", e)
            }

            val waypoint = Waypoint.builder()
                .setLatLng(fixedDestination.latitude, fixedDestination.longitude)
                .setTitle("Sydney (Fixed Simulation)")
                .build()

            try {
                val pendingRoute = currentNavigator.setDestination(waypoint)
                pendingRoute.setOnResultListener { result ->
                    if (result == Navigator.RouteStatus.OK) {
                        currentNavigator.startGuidance()
                        currentNavigator.simulator.simulateLocationsAlongExistingRoute(
                            SimulationOptions().speedMultiplier(0.8f)
                        )
                        Toast.makeText(this, "Simulated Navigation Started", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to start simulation route: $result", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("NavDebug", "Simulated setDestination threw exception", e)
                Toast.makeText(this, "Simulation error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Mode 2 & Mode 3: Destination allocated by user
        if (destinationQuery.isBlank()) {
            Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            return
        }

        resolveDestination(destinationQuery) { targetLatLng, title ->
            if (targetLatLng == null) {
                Toast.makeText(this, "Could not find location: '$destinationQuery'", Toast.LENGTH_SHORT).show()
                return@resolveDestination
            }

            val waypoint = Waypoint.builder()
                .setLatLng(targetLatLng.latitude, targetLatLng.longitude)
                .setTitle(title.ifBlank { destinationQuery })
                .build()

            try {
                val pendingRoute = currentNavigator.setDestination(waypoint)
                pendingRoute.setOnResultListener { result ->
                    if (result == Navigator.RouteStatus.OK) {
                        currentNavigator.startGuidance()
                        try {
                            currentNavigator.simulator.pause()
                            if (devMode == 3) {
                                currentNavigator.simulator.unsetUserLocation()
                            }
                        } catch (ignored: Exception) {}
                        Toast.makeText(this, "Navigating to: $title", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to calculate route: $result", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("NavDebug", "setDestination threw exception", e)
                Toast.makeText(this, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveDestination(query: String, onResolved: (LatLng?, String) -> Unit) {
        val trimmed = query.trim()

        // 1. Direct Lat/Lng coordinate parsing: "-33.8688, 151.2093"
        val latLngMatch = Regex("""^(-?\d+(\.\d+)?),\s*(-?\d+(\.\d+)?)$""").find(trimmed)
        if (latLngMatch != null) {
            val lat = latLngMatch.groupValues[1].toDoubleOrNull()
            val lng = latLngMatch.groupValues[3].toDoubleOrNull()
            if (lat != null && lng != null) {
                onResolved(LatLng(lat, lng), "Destination ($trimmed)")
                return
            }
        }

        // 2. Android Geocoder lookup for text query
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@NavigationActivity, Locale.getDefault())
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val deferred = CompletableDeferred<List<Address>?>()
                    geocoder.getFromLocationName(trimmed, 1) { list -> deferred.complete(list) }
                    deferred.await()
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(trimmed, 1)
                }

                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val displayName = addr.getAddressLine(0) ?: addr.featureName ?: trimmed
                        onResolved(LatLng(addr.latitude, addr.longitude), displayName)
                    } else if (trimmed.equals("sydney", ignoreCase = true)) {
                        onResolved(LatLng(-33.8688, 151.2093), "Sydney")
                    } else {
                        onResolved(null, trimmed)
                    }
                }
            } catch (e: Exception) {
                Log.e("NavDebug", "Geocoding error for '$trimmed'", e)
                withContext(Dispatchers.Main) {
                    if (trimmed.equals("sydney", ignoreCase = true)) {
                        onResolved(LatLng(-33.8688, 151.2093), "Sydney")
                    } else {
                        onResolved(null, trimmed)
                    }
                }
            }
        }
    }

    private fun runDirectApiDiagnosis() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val apiKey = appInfo.metaData?.getString("com.google.android.geo.API_KEY") ?: ""

                val urlString = "https://routes.googleapis.com/directions/v2:computeRoutes"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Goog-Api-Key", apiKey)
                connection.setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")

                val jsonPayload = """
                {
                  "origin":{"location":{"latLng":{"latitude": -33.8688, "longitude": 151.2093}}},
                  "destination":{"location":{"latLng":{"latitude": -33.8731, "longitude": 151.2063}}},
                  "travelMode": "DRIVE"
                }
                """.trimIndent()

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(jsonPayload)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                withContext(Dispatchers.Main) {
                    Log.d("NavDebug", "Diagnosis Response Code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("NavDebug", "Routes API v2 Connection Failed", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::navigationView.isInitialized) navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::navigationView.isInitialized) navigationView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::navigationView.isInitialized) navigationView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::navigationView.isInitialized) navigationView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::navigationView.isInitialized) navigationView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::navigationView.isInitialized) navigationView.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeNavigationApi()
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
    }
}

/**
 * Service to receive turn-by-turn navigation updates from the Google Maps Navigation SDK.
 */
/**
 * Service to receive turn-by-turn navigation updates from the Google Maps Navigation SDK.
 */
class NavUpdateService : Service() {
    private var navigator: Navigator? = null
    private lateinit var bleManager: BleManager

    // Raw, unrounded values — source of truth for math
    private var lastKnownSpeedMps: Float = 0f
    private var currentSpeedLimitKmH: Int = 0

    // Rounded, display/BLE-facing value derived from lastKnownSpeedMps
    private val currentSpeedKmH: Int
        get() = (lastKnownSpeedMps * 3.6f).toInt()

    private var latestNavInfo: com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo? = null

    private val sendHandler = Handler(Looper.getMainLooper())
    private val sendRunnable = object : Runnable {
        override fun run() {
            sendCurrentNavData()
            sendHandler.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    private val mMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
            val bundle = msg.data
            bundle.classLoader = TurnByTurnManager::class.java.classLoader

            // DIAGNOSTIC: Log all keys in Turn-by-Turn bundle
            Log.d("HUD_DEBUG", "NavInfo Bundle Keys: ${bundle.keySet().joinToString(", ")}")

            val navInfo = TurnByTurnManager.createInstance().readNavInfoFromBundle(bundle)
            if (navInfo != null) {
                latestNavInfo = navInfo
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        val app = this.application as android.app.Application

        // Start 5Hz periodic BLE transmission
        sendHandler.post(sendRunnable)

        // Use RoadSnappedLocationProvider for accurate vehicle speed and initial/fallback car facing
        NavigationApi.getRoadSnappedLocationProvider(app)?.addLocationListener(object : RoadSnappedLocationProvider.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                // Keep the raw float — do NOT truncate here, truncation happens only
                // at the point of display/BLE transmission via currentSpeedKmH getter.
                lastKnownSpeedMps = location.speed

                // Initial/fallback simulated car bearing converted to CCW
                if (location.hasBearing() && lastKnownCarFacingCcw == 0f) {
                    var rawBearing = location.bearing
                    if (rawBearing < 0) rawBearing += 360f
                    var ccwFacing = (360f - rawBearing) % 360f
                    if (ccwFacing < 0) ccwFacing += 360f
                    lastKnownCarFacingCcw = ccwFacing
                }
            }
        })

        NavigationApi.getNavigator(app, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav

                // Configure speed alerts to trigger as much as possible
                val speedAlertOptions = SpeedAlertOptions.Builder()
                    .setSpeedAlertThresholdPercentage(SpeedAlertSeverity.MINOR, 0f)
                    .setSpeedAlertThresholdPercentage(SpeedAlertSeverity.MAJOR, 5f)
                    .setSeverityUpgradeDurationSeconds(5.0)
                    .build()
                nav.setSpeedAlertOptions(speedAlertOptions)

                // Derive the speed limit from (current speed, percentage above limit).
                nav.setSpeedingListener { speedingPercentage, _ ->
                    val speedKmH = lastKnownSpeedMps * 3.6f

                    if (speedingPercentage > 0 && speedKmH > 0) {
                        val rawLimit = speedKmH / (1f + speedingPercentage / 100f)
                        val roundedLimit = (Math.round(rawLimit / 5f) * 5)

                        Log.d(
                            "SPEEDLIMIT_DEBUG",
                            "pct=$speedingPercentage speedKmH=$speedKmH rawLimit=$rawLimit roundedLimit=$roundedLimit (prevLimit=$currentSpeedLimitKmH)"
                        )

                        if (roundedLimit > 0) {
                            currentSpeedLimitKmH = roundedLimit
                        }
                    }
                }
            }
            override fun onError(p0: Int) {}
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        sendHandler.removeCallbacks(sendRunnable)
    }

    override fun onBind(intent: Intent): IBinder? {
        return mMessenger.binder
    }

    private fun sendCurrentNavData() {
        val navInfo = latestNavInfo ?: return

        val NAV_distanceMeters = navInfo.distanceToCurrentStepMeters ?: 0
        var nextTurnAngle = 65535f
        var NAV_heading = 65535f
        val currentSegment = navigator?.currentRouteSegment

        if (currentSegment != null) {
            val latLngs = currentSegment.latLngs

            if (latLngs != null && latLngs.size >= 2) {
                var accumulatedDistance = 0f
                var turnIndex = -1

                for (i in 0 until latLngs.size - 1) {
                    val p1 = latLngs[i]
                    val p2 = latLngs[i + 1]
                    accumulatedDistance += calculateDistance(p1, p2)

                    if (accumulatedDistance >= (NAV_distanceMeters - 15f)) {
                        turnIndex = i + 1
                        break
                    }
                }

                if (turnIndex != -1 && turnIndex < latLngs.size - 1) {
                    val startP = latLngs[turnIndex]
                    var endP = latLngs[turnIndex + 1]
                    var lookAheadDist = 0f

                    for (j in turnIndex until latLngs.size - 1) {
                        lookAheadDist += calculateDistance(latLngs[j], latLngs[j + 1])
                        endP = latLngs[j + 1]
                        if (lookAheadDist >= 25f) break
                    }
                    nextTurnAngle = calculateBearing(startP, endP)
                } else {
                    val startP = latLngs[0]
                    var endP = latLngs[1]
                    var lookAheadDist = 0f
                    for (j in 0 until latLngs.size - 1) {
                        lookAheadDist += calculateDistance(latLngs[j], latLngs[j + 1])
                        endP = latLngs[j + 1]
                        if (lookAheadDist >= 25f) break
                    }
                    nextTurnAngle = calculateBearing(startP, endP)
                }
            }
        }

        val prefs = getSharedPreferences("ar_hud_config", Context.MODE_PRIVATE)
        val devMode = prefs.getInt("key_dev_mode_selected", 3)

        // 1. Heading source:
        // In Normal Mode (Mode 3), prioritize real device IMU yaw (from ESP32 BLE).
        // In Simulated Modes (Mode 1 & 2), strictly follow simulated map camera facing without IMU influence.
        val effectiveCarFacingCcw = if (devMode == 3) {
            val imuYaw = bleManager.imuData.value?.yaw
            if (imuYaw != null) {
                var ccw = (360f - imuYaw) % 360f
                if (ccw < 0) ccw += 360f
                ccw
            } else {
                lastKnownCarFacingCcw
            }
        } else {
            lastKnownCarFacingCcw
        }

        if (NAV_distanceMeters <= 25 && nextTurnAngle < 65535f) {
            // Combine next turn angle with simulated/real car facing (both in CCW frame)
            // Relative arrow angle = (nextTurnAngle - carFacingCcw) mod 360
            var relativeArrowBearing = (nextTurnAngle - effectiveCarFacingCcw) % 360f
            if (relativeArrowBearing < 0) relativeArrowBearing += 360f
            NAV_heading = relativeArrowBearing
        } else {
            // Next turn is > 25m away: return fixed out-of-bounds angle 65535
            NAV_heading = 65535f
        }

        // 2. Vehicle speed from SDK RoadSnappedLocationProvider (km/h)
        val navSpeedKmH = currentSpeedKmH

        // 3. Road speed limit derived from speeding listener (km/h)
        val navSpeedLimitKmH = currentSpeedLimitKmH

        val rawManeuver = navInfo.currentStep?.maneuver ?: 0
        val deviceSignIndex = ManeuverMapper.mapManeuverToDeviceIcon(rawManeuver)
        val navLaneData = ByteArray(3) // Lane data placeholder

        val laneByte0 = "0x%02X".format(navLaneData[0])
        val laneByte1 = "0x%02X".format(navLaneData[1])
        val laneByte2 = "0x%02X".format(navLaneData[2])

        bleManager.sendNavigationData(NAV_heading, NAV_distanceMeters, navSpeedKmH, deviceSignIndex, navSpeedLimitKmH, navLaneData)
        bleManager.updateHudDebugData(
            arrowBearing = NAV_heading,
            nextTurnAngle = nextTurnAngle,
            carFacing = effectiveCarFacingCcw,
            distanceMeters = NAV_distanceMeters,
            speedKmH = navSpeedKmH,
            speedLimitKmH = navSpeedLimitKmH,
            signIndex = deviceSignIndex
        )

        val arrowHeadingLog = if (NAV_heading >= 65535f) "65535 (Out of bounds)" else "%.1f°".format(NAV_heading)
        val nextTurnLog = if (nextTurnAngle >= 65535f) "N/A" else "%.1f°".format(nextTurnAngle)
        val carFacingLog = "%.1f°".format(effectiveCarFacingCcw)
        val signNameLog = ManeuverMapper.getDeviceIconName(deviceSignIndex)

        Log.d("HUD_DEBUG", """
            [HUD Data Sent @ 5Hz] 
            ├── Arrow Bearing (Sent to HUD) : $arrowHeadingLog
            ├── Next Turn Angle (World CCW) : $nextTurnLog
            ├── Car Facing (Active CCW)     : $carFacingLog
            ├── Distance to Turn            : ${NAV_distanceMeters} m
            ├── Speed                       : ${navSpeedKmH} km/h (Limit: ${navSpeedLimitKmH} km/h)
            ├── Sign Index (Device Mapped)  : $deviceSignIndex ($signNameLog) [Raw Google: $rawManeuver]
            └── Lane Data                   : [$laneByte0, $laneByte1, $laneByte2]
            """.trimIndent())
    }

    companion object {
        @Volatile
        var lastKnownCarFacingCcw: Float = 0f

        private const val SEND_FREQ_HZ = 5 // 5Hz (every 200ms)
        private const val SEND_INTERVAL_MS = 1000L / SEND_FREQ_HZ // 200ms
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
        return results[0]
    }

    private fun calculateBearing(start: LatLng, end: LatLng): Float {
        val startLoc = Location("").apply {
            latitude = start.latitude
            longitude = start.longitude
        }
        val endLoc = Location("").apply {
            latitude = end.latitude
            longitude = end.longitude
        }
        var bearing = startLoc.bearingTo(endLoc)
        if (bearing < 0) bearing += 360f

        // Convert clockwise compass bearing to counterclockwise heading
        // (0° = North, 90° = West, 180° = South, 270° = East)
        var ccwBearing = (360f - bearing) % 360f
        if (ccwBearing < 0) ccwBearing += 360f
        return ccwBearing
    }
}

/**
 * Maps Google Navigation SDK Maneuver integers (0..65) to the AR HUD device icon index (0..14).
 *
 * Device icon indices:
 * 0  = NULL (Unknown)
 * 1  = &ic_turn_u_turn_counterclockwise
 * 2  = &ic_turn_sharp_left
 * 3  = &ic_turn_left
 * 4  = &ic_turn_slight_left
 * 5  = &ic_straight
 * 6  = &ic_turn_slight_right
 * 7  = &ic_turn_right
 * 8  = &ic_turn_sharp_right
 * 9  = &ic_turn_u_turn_clockwise
 * 10 = &ic_destination_left
 * 11 = &ic_destination_right
 * 12 = &ic_destination
 * 13 = &ic_depart
 * 14 = &ic_merge
 */
object ManeuverMapper {
    fun mapManeuverToDeviceIcon(maneuver: Int): Int {
        return when (maneuver) {
            0 -> 0 // UNKNOWN -> NULL
            1 -> 13 // DEPART -> ic_depart
            2 -> 12 // DESTINATION -> ic_destination
            3 -> 10 // DESTINATION_LEFT -> ic_destination_left
            4 -> 11 // DESTINATION_RIGHT -> ic_destination_right

            // Straight / Continue / Name change / Ferries
            5, 63, 64, 65 -> 5 // STRAIGHT, FERRY_BOAT, FERRY_TRAIN, NAME_CHANGE -> ic_straight

            // Basic Turns
            6 -> 3 // TURN_LEFT -> ic_turn_left
            7 -> 7 // TURN_RIGHT -> ic_turn_right
            8 -> 3 // TURN_KEEP_LEFT -> ic_turn_left
            9 -> 7 // TURN_KEEP_RIGHT -> ic_turn_right
            10 -> 4 // TURN_SLIGHT_LEFT -> ic_turn_slight_left
            11 -> 6 // TURN_SLIGHT_RIGHT -> ic_turn_slight_right
            12 -> 2 // TURN_SHARP_LEFT -> ic_turn_sharp_left
            13 -> 8 // TURN_SHARP_RIGHT -> ic_turn_sharp_right
            14 -> 9 // TURN_U_TURN_CLOCKWISE -> ic_turn_u_turn_clockwise
            15 -> 1 // TURN_U_TURN_COUNTERCLOCKWISE -> ic_turn_u_turn_counterclockwise

            // Merges
            16, 17, 18 -> 14 // MERGE_UNSPECIFIED, MERGE_LEFT, MERGE_RIGHT -> ic_merge

            // Forks
            19 -> 4 // FORK_LEFT -> ic_turn_slight_left
            20 -> 6 // FORK_RIGHT -> ic_turn_slight_right

            // On-ramps
            21 -> 14 // ON_RAMP_UNSPECIFIED -> ic_merge
            22 -> 3  // ON_RAMP_LEFT -> ic_turn_left
            23 -> 7  // ON_RAMP_RIGHT -> ic_turn_right
            24, 26 -> 4 // ON_RAMP_KEEP_LEFT, ON_RAMP_SLIGHT_LEFT -> ic_turn_slight_left
            25, 27 -> 6 // ON_RAMP_KEEP_RIGHT, ON_RAMP_SLIGHT_RIGHT -> ic_turn_slight_right
            28 -> 2 // ON_RAMP_SHARP_LEFT -> ic_turn_sharp_left
            29 -> 8 // ON_RAMP_SHARP_RIGHT -> ic_turn_sharp_right
            30 -> 9 // ON_RAMP_U_TURN_CLOCKWISE -> ic_turn_u_turn_clockwise
            31 -> 1 // ON_RAMP_U_TURN_COUNTERCLOCKWISE -> ic_turn_u_turn_counterclockwise

            // Off-ramps
            32 -> 5 // OFF_RAMP_UNSPECIFIED -> ic_straight
            33 -> 3 // OFF_RAMP_LEFT -> ic_turn_left
            34 -> 7 // OFF_RAMP_RIGHT -> ic_turn_right
            35, 37 -> 4 // OFF_RAMP_KEEP_LEFT, OFF_RAMP_SLIGHT_LEFT -> ic_turn_slight_left
            36, 38 -> 6 // OFF_RAMP_KEEP_RIGHT, OFF_RAMP_SLIGHT_RIGHT -> ic_turn_slight_right
            39 -> 2 // OFF_RAMP_SHARP_LEFT -> ic_turn_sharp_left
            40 -> 8 // OFF_RAMP_SHARP_RIGHT -> ic_turn_sharp_right
            41 -> 9 // OFF_RAMP_U_TURN_CLOCKWISE -> ic_turn_u_turn_clockwise
            42 -> 1 // OFF_RAMP_U_TURN_COUNTERCLOCKWISE -> ic_turn_u_turn_counterclockwise

            // Roundabouts - Clockwise
            43 -> 7 // ROUNDABOUT_CLOCKWISE -> ic_turn_right
            45 -> 5 // ROUNDABOUT_STRAIGHT_CLOCKWISE -> ic_straight
            47 -> 3 // ROUNDABOUT_LEFT_CLOCKWISE -> ic_turn_left
            49 -> 7 // ROUNDABOUT_RIGHT_CLOCKWISE -> ic_turn_right
            51 -> 4 // ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE -> ic_turn_slight_left
            53 -> 6 // ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE -> ic_turn_slight_right
            55 -> 2 // ROUNDABOUT_SHARP_LEFT_CLOCKWISE -> ic_turn_sharp_left
            57 -> 8 // ROUNDABOUT_SHARP_RIGHT_CLOCKWISE -> ic_turn_sharp_right
            59 -> 9 // ROUNDABOUT_U_TURN_CLOCKWISE -> ic_turn_u_turn_clockwise
            61 -> 6 // ROUNDABOUT_EXIT_CLOCKWISE -> ic_turn_slight_right

            // Roundabouts - Counter-Clockwise
            44 -> 3 // ROUNDABOUT_COUNTERCLOCKWISE -> ic_turn_left
            46 -> 5 // ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE -> ic_straight
            48 -> 3 // ROUNDABOUT_LEFT_COUNTERCLOCKWISE -> ic_turn_left
            50 -> 7 // ROUNDABOUT_RIGHT_COUNTERCLOCKWISE -> ic_turn_right
            52 -> 4 // ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE -> ic_turn_slight_left
            54 -> 6 // ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE -> ic_turn_slight_right
            56 -> 2 // ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE -> ic_turn_sharp_left
            58 -> 8 // ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE -> ic_turn_sharp_right
            60 -> 1 // ROUNDABOUT_U_TURN_COUNTERCLOCKWISE -> ic_turn_u_turn_counterclockwise
            62 -> 4 // ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> ic_turn_slight_left

            else -> 0
        }
    }

    fun getDeviceIconName(deviceIconIdx: Int): String {
        return when (deviceIconIdx) {
            0 -> "None (0)"
            1 -> "U-Turn CCW (1)"
            2 -> "Sharp Left (2)"
            3 -> "Turn Left (3)"
            4 -> "Slight Left (4)"
            5 -> "Straight (5)"
            6 -> "Slight Right (6)"
            7 -> "Turn Right (7)"
            8 -> "Sharp Right (8)"
            9 -> "U-Turn CW (9)"
            10 -> "Dest Left (10)"
            11 -> "Dest Right (11)"
            12 -> "Destination (12)"
            13 -> "Depart (13)"
            14 -> "Merge (14)"
            else -> "Icon $deviceIconIdx"
        }
    }
}

@Composable
fun NavigationContent(
    navigationView: NavigationView,
    bleStatus: String,
    debugData: HudDebugData,
    displayHudSimulator: Boolean = true,
    devMode: Int = 3,
    onStartNavigation: (String) -> Unit,
    onExitNavigation: () -> Unit
) {
    var destinationText by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BLE: $bleStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bleStatus == "Connected") Color(0xFF4CAF50) else Color.Red
                    )
                }
                Button(
                    onClick = onExitNavigation,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Exit Nav")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = destinationText,
                onValueChange = { destinationText = it },
                label = { Text("Enter Destination") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onStartNavigation(destinationText) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Navigation")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            AndroidView(
                factory = { navigationView },
                modifier = Modifier.fillMaxSize()
            )

            // Floating Real-Time HUD Debug Overlay / Simulator
            if (displayHudSimulator) {
                HudArrowDebugWidget(
                    debugData = debugData,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun HudArrowDebugWidget(
    debugData: HudDebugData,
    modifier: Modifier = Modifier
) {
    val isOutOfBounds = debugData.arrowBearing >= 65535f || debugData.distanceMeters > 25
    val targetRotationAngle = if (!isOutOfBounds) -debugData.arrowBearing else 0f
    val animatedRotationAngle by animateFloatAsState(
        targetValue = targetRotationAngle,
        animationSpec = tween(durationMillis = 90),
        label = "arrowRotation"
    )

    Card(
        modifier = modifier.width(180.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.82f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "HUD ARROW PREVIEW",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Circular HUD Dial ───
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Color(0xFF1E1E1E), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 6.dp.toPx()

                    // Outer dial ring
                    drawCircle(
                        color = Color(0xFF444444),
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Reference tick marks: 0° (Top), 90° (Left/CCW), 180° (Bottom), 270° (Right)
                    val tickLen = 6.dp.toPx()
                    drawLine(Color.Gray, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tickLen), 2.dp.toPx()) // 0°
                    drawLine(Color.Gray, Offset(center.x - radius, center.y), Offset(center.x - radius + tickLen, center.y), 2.dp.toPx()) // 90°
                    drawLine(Color.Gray, Offset(center.x, center.y + radius), Offset(center.x, center.y + radius - tickLen), 2.dp.toPx()) // 180°
                    drawLine(Color.Gray, Offset(center.x + radius, center.y), Offset(center.x + radius - tickLen, center.y), 2.dp.toPx()) // 270°

                    if (isOutOfBounds) {
                        // Straight dashed / line indicator when out of bounds (> 25m)
                        drawLine(
                            color = Color(0xFF757575),
                            start = Offset(center.x, center.y + 16.dp.toPx()),
                            end = Offset(center.x, center.y - 24.dp.toPx()),
                            strokeWidth = 3.dp.toPx()
                        )
                    } else {
                        // Smoothly animated rotation (CCW = negative rotation in Compose)
                        rotate(degrees = animatedRotationAngle, pivot = center) {
                            val arrowPath = Path().apply {
                                moveTo(center.x, center.y - radius + 8.dp.toPx()) // Tip
                                lineTo(center.x - 12.dp.toPx(), center.y + 14.dp.toPx()) // Left wing
                                lineTo(center.x, center.y + 4.dp.toPx())                 // Inner base
                                lineTo(center.x + 12.dp.toPx(), center.y + 14.dp.toPx()) // Right wing
                                close()
                            }
                            drawPath(
                                path = arrowPath,
                                color = Color(0xFF00E676) // Bright Green Arrow
                            )
                        }
                    }
                }

                if (isOutOfBounds) {
                    Text(
                        text = "STRAIGHT",
                        color = Color.LightGray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Telemetry Data ───
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TelemetryRow("Arrow", if (isOutOfBounds) "65535" else "%.1f°".format(debugData.arrowBearing), Color(0xFF00E676))
                TelemetryRow("Turn", if (debugData.nextTurnAngle >= 65535f) "N/A" else "%.1f°".format(debugData.nextTurnAngle), Color.White)
                TelemetryRow("Car", "%.1f°".format(debugData.carFacing), Color.White)
                TelemetryRow("Dist", "${debugData.distanceMeters} m", Color(0xFF00E5FF))
                TelemetryRow("Sign", ManeuverMapper.getDeviceIconName(debugData.signIndex), Color(0xFFFFD54F))
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
        Text(text = value, color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
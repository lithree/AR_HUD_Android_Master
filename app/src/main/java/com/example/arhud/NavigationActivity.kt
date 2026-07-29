package com.example.arhud

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.google.android.libraries.navigation.SimulationOptions
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

        checkAndRequestPermissions()
        runDirectApiDiagnosis()

        enableEdgeToEdge()
        setContent {
            val bleStatus by bleManager.status.collectAsState()

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

        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
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

                try {
                    val simulatedOrigin = LatLng(-33.9399, 151.1753)
                    newNavigator.simulator.setUserLocation(simulatedOrigin)
                    navigationView.getMapAsync { googleMap ->
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(simulatedOrigin, 15f))
                    }
                } catch (e: Exception) {
                    Log.e("NavDebug", "Failed to inject simulated origin location", e)
                }
                Toast.makeText(this@NavigationActivity, "Navigator Ready", Toast.LENGTH_SHORT).show()
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                Log.e("NavDebug", "getNavigator onError - errorCode=$errorCode")
            }
        })
    }

    private fun startNavigation(destinationQuery: String) {
        val currentNavigator = navigator ?: return

        val waypoint = if (destinationQuery.lowercase() == "sydney") {
            Waypoint.builder().setLatLng(-33.8688, 151.2093).setTitle("Sydney").build()
        } else {
            Waypoint.builder().setLatLng(-33.8731, 151.2063).setTitle(destinationQuery).build()
        }

        try {
            val pendingRoute = currentNavigator.setDestination(waypoint)
            pendingRoute.setOnResultListener { result ->
                if (result == Navigator.RouteStatus.OK) {
                    currentNavigator.startGuidance()
                    currentNavigator.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(0.5f))
                } else {
                    Toast.makeText(this, "Failed to set destination: $result", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("NavDebug", "setDestination threw exception", e)
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
class NavUpdateService : Service() {
    private var navigator: Navigator? = null
    private lateinit var bleManager: BleManager

    private val mMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
            val bundle = msg.data
            bundle.classLoader = TurnByTurnManager::class.java.classLoader
            val navInfo = TurnByTurnManager.createInstance().readNavInfoFromBundle(bundle)
            if (navInfo != null) {
                handleNavInfo(navInfo)
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)

        NavigationApi.getNavigator(this.application as android.app.Application, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav
            }
            override fun onError(p0: Int) {}
        })
    }

    override fun onBind(intent: Intent): IBinder? {
        return mMessenger.binder
    }

    private fun handleNavInfo(navInfo: com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo) {
        val distanceMeters = navInfo.distanceToCurrentStepMeters ?: 0
        var targetHeading = 0f

        val currentSegment = navigator?.currentRouteSegment

        if (currentSegment != null) {
            // latLngs from routeSegment are valid geometry coordinates
            val latLngs = currentSegment.latLngs

            if (latLngs != null && latLngs.size >= 2) {
                if (distanceMeters > 100) {
                    // Logic 1: Distance > 100m. Output the CURRENT road's heading.
                    // Calculate heading from current car position (latLngs[0]) looking ahead 25 meters.
                    val startP = latLngs[0]
                    var endP = latLngs[1]
                    var lookAheadDist = 0f
                    for (j in 0 until latLngs.size - 1) {
                        lookAheadDist += calculateDistance(latLngs[j], latLngs[j + 1])
                        endP = latLngs[j + 1]
                        if (lookAheadDist >= 25f) break
                    }
                    targetHeading = calculateBearing(startP, endP)

                } else {
                    // Logic 2: Distance <= 100m. Output the NEXT road's heading.
                    var accumulatedDistance = 0f
                    var turnIndex = -1

                    // Find the physical turning point by walking along the route geometry
                    for (i in 0 until latLngs.size - 1) {
                        val p1 = latLngs[i]
                        val p2 = latLngs[i + 1]
                        accumulatedDistance += calculateDistance(p1, p2)

                        // 15m tolerance for GPS drift / intersection width
                        if (accumulatedDistance >= (distanceMeters - 15f)) {
                            turnIndex = i + 1
                            break
                        }
                    }

                    if (turnIndex != -1 && turnIndex < latLngs.size - 1) {
                        // Successfully located the turn intersection.
                        // Look ahead 25m into the NEW road to establish the next heading.
                        val startP = latLngs[turnIndex]
                        var endP = latLngs[turnIndex + 1]
                        var lookAheadDist = 0f

                        for (j in turnIndex until latLngs.size - 1) {
                            lookAheadDist += calculateDistance(latLngs[j], latLngs[j + 1])
                            endP = latLngs[j + 1]
                            if (lookAheadDist >= 25f) break
                        }
                        targetHeading = calculateBearing(startP, endP)
                    } else {
                        // Fallback: If we couldn't find the turn (e.g. nearing final destination)
                        // just maintain current heading.
                        val startP = latLngs[0]
                        var endP = latLngs[1]
                        var lookAheadDist = 0f
                        for (j in 0 until latLngs.size - 1) {
                            lookAheadDist += calculateDistance(latLngs[j], latLngs[j + 1])
                            endP = latLngs[j + 1]
                            if (lookAheadDist >= 25f) break
                        }
                        targetHeading = calculateBearing(startP, endP)
                    }
                }
            }
        }

        bleManager.sendNavigationData(targetHeading, distanceMeters)
        Log.d("HUD_DEBUG", "Sent to HUD: Heading $targetHeading, Distance $distanceMeters")
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
        return bearing
    }
}

@Composable
fun NavigationContent(
    navigationView: NavigationView,
    bleStatus: String,
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
        AndroidView(
            factory = { navigationView },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        )
    }
}
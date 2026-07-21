package com.example.arhud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.arhud.ui.theme.ARHUDTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private var bleStatus by mutableStateOf("Disconnected")
    private var sendJob: Job? = null
    private var bleCounter = 0
    private var isConnected = false
    private lateinit var navigationView: NavigationView
    private var navigator: Navigator? = null

    private fun startPeriodicSending() {
        sendJob?.cancel()
        sendJob = lifecycleScope.launch {
            while (isConnected) {
                bleManager.sendTurnAndLaneData(bleCounter++, 0, 2, 100)
                delay(1000)
            }
        }
    }

    private fun stopPeriodicSending() {
        sendJob?.cancel()
        sendJob = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Execute raw HTTP POST diagnostic request to Google Routes API v2 (computeRoutes).
        // This targets the exact routing engine used by Navigation SDK to capture the backend refusal reason.
        runDirectApiDiagnosis()

        bleManager = BleManager(this)
        bleManager.callback = object : BleManager.BleCallback {
            override fun onScanFound(device: android.bluetooth.BluetoothDevice) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bleStatus = "Device found: ${device.name ?: "Unknown"}. Connecting..."
                } else {
                    bleStatus = "Device found. Connecting..."
                }
            }

            override fun onConnected() {
                bleStatus = "Connected"
                isConnected = true
                startPeriodicSending()
            }

            override fun onDisconnected() {
                bleStatus = "Disconnected"
                isConnected = false
                stopPeriodicSending()
            }

            override fun onDataSent(success: Boolean) {
                bleStatus = "Connected (Data Sent: $success)"
            }

            override fun onError(message: String) {
                bleStatus = "Error: $message"
            }
        }

        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
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
            bleStatus = "Scanning..."
            bleManager.startScan()
            initializeNavigationApi()
        }

        navigationView = layoutInflater.inflate(R.layout.navigation_view_layout, null) as NavigationView
        navigationView.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            ARHUDTheme {
                ARHUDApp(
                    status = bleStatus,
                    navigationView = navigationView,
                    onRetryScan = {
                        bleStatus = "Scanning..."
                        bleManager.startScan()
                    },
                    onStartNavigation = { destination ->
                        startNavigation(destination)
                    }
                )
            }
        }
    }

    private fun runDirectApiDiagnosis() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Read the API key currently injected into application metadata
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val apiKey = appInfo.metaData?.getString("com.google.android.geo.API_KEY") ?: ""
                Log.e("NavDebug", "Routes API v2 Diagnosis started. API Key length: ${apiKey.length}")

                if (apiKey.isEmpty() || apiKey == "\${mapsApiKey}") {
                    Log.e("NavDebug", "CRITICAL ERROR: API Key is empty or placeholder was not replaced!")
                    return@launch
                }

                // Target the exact endpoint used by Navigation SDK: Routes API v2 (computeRoutes)
                val urlString = "https://routes.googleapis.com/directions/v2:computeRoutes"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.doInput = true

                // Set mandatory headers required by Google Routes API v2
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Goog-Api-Key", apiKey)
                connection.setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")

                // Build request JSON payload targeting Sydney coordinates
                val jsonPayload = """
                {
                  "origin":{
                    "location":{
                      "latLng":{
                        "latitude": -33.8688,
                        "longitude": 151.2093
                      }
                    }
                  },
                  "destination":{
                    "location":{
                      "latLng":{
                        "latitude": -33.8731,
                        "longitude": 151.2063
                      }
                    }
                  },
                  "travelMode": "DRIVE"
                }
                """.trimIndent()

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(jsonPayload)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                Log.e("NavDebug", "Routes API v2 Response Code: $responseCode")

                val stream = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val reader = BufferedReader(InputStreamReader(stream))
                val responseBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    responseBuilder.append(line)
                }
                reader.close()
                connection.disconnect()

                val rawResponse = responseBuilder.toString()
                Log.e("NavDebug", "Routes API v2 Raw Server Response:\n$rawResponse")

                withContext(Dispatchers.Main) {
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        Toast.makeText(this@MainActivity, "Routes API v2 Error: $responseCode", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("NavDebug", "Routes API v2 Connection Failed", e)
            }
        }
    }

    private fun initializeNavigationApi() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)
        Log.e("NavDebug", "GooglePlayServices resultCode=$resultCode")
        if (resultCode != ConnectionResult.SUCCESS) {
            availability.getErrorDialog(this, resultCode, 0)?.show()
            return
        }

        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(newNavigator: Navigator) {
                Log.e("NavDebug", "onNavigatorReady - navigator obtained successfully")
                navigator = newNavigator

                // Set audio guidance to silent using the correct reference under Navigator
                newNavigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)

                // Inject a simulated starting coordinate (Sydney Airport) into the SDK engine
                try {
                    val simulatedOrigin = LatLng(-33.9399, 151.1753)
                    newNavigator.simulator.setUserLocation(simulatedOrigin)
                    Log.d("NavDebug", "Simulated origin injected at Sydney Airport: $simulatedOrigin")

                    // Move camera to simulated origin to verify map tiles
                    navigationView.getMapAsync { googleMap ->
                        googleMap.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(simulatedOrigin, 15f))
                        Log.d("NavDebug", "Camera moved to simulated origin")
                    }
                } catch (e: Exception) {
                    Log.e("NavDebug", "Failed to inject simulated origin location", e)
                }

                Toast.makeText(this@MainActivity, "Navigator Ready & Origin Initialized", Toast.LENGTH_SHORT).show()
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                Log.e("NavDebug", "getNavigator onError - errorCode=$errorCode")
                when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> {
                        Toast.makeText(this@MainActivity, "Navigation SDK not authorized", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(this@MainActivity, "Navigation SDK error: $errorCode", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun startNavigation(destinationQuery: String) {
        val navigator = navigator ?: run {
            Log.e("NavDebug", "startNavigation called but navigator is null")
            Toast.makeText(this, "Navigator not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val waypoint = if (destinationQuery.lowercase() == "sydney") {
            Waypoint.builder().setLatLng(-33.8688, 151.2093).setTitle("Sydney").build()
        } else {
            Waypoint.builder().setLatLng(-33.8731, 151.2063).setTitle(destinationQuery).build()
        }
        Log.e("NavDebug", "Calling setDestination with waypoint=$waypoint")

        try {
            val pendingRoute = navigator.setDestination(waypoint)
            pendingRoute.setOnResultListener { result ->
                Log.e("NavDebug", "setDestination onResult -> RouteStatus=$result")
                if (result == Navigator.RouteStatus.OK) {
                    navigator.startGuidance()
                    navigator.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(5f))
                } else {
                    Toast.makeText(this, "Failed to set destination: $result", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("NavDebug", "setDestination threw exception", e)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::navigationView.isInitialized) {
            navigationView.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::navigationView.isInitialized) {
            navigationView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::navigationView.isInitialized) {
            navigationView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::navigationView.isInitialized) {
            navigationView.onStop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::navigationView.isInitialized) {
            navigationView.onDestroy()
        }
        navigator?.cleanup()
        bleManager.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::navigationView.isInitialized) {
            navigationView.onSaveInstanceState(outState)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bleStatus = "Scanning..."
                bleManager.startScan()
                initializeNavigationApi()
            } else {
                bleStatus = "Permissions denied"
                Toast.makeText(this, "Permissions are required for BLE and Navigation", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
    }
}

@Composable
fun ARHUDApp(
    status: String = "Disconnected",
    navigationView: NavigationView? = null,
    onRetryScan: () -> Unit = {},
    onStartNavigation: (String) -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    var destinationText by rememberSaveable { mutableStateOf("") }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            imageVector = it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                Greeting(name = "AR HUD User")
                Spacer(modifier = Modifier.height(16.dp))
                BleStatusComponent(status = status, onRetryScan = onRetryScan)
                Spacer(modifier = Modifier.height(16.dp))

                NavigationControlComponent(
                    destinationText = destinationText,
                    onDestinationChanged = { destinationText = it },
                    onStartNavigation = { onStartNavigation(destinationText) }
                )

                Spacer(modifier = Modifier.height(16.dp))
                if (navigationView != null) {
                    AndroidView(
                        factory = {
                            navigationView!!
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        update = { view ->
                            // Update the view if needed
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationControlComponent(
    destinationText: String,
    onDestinationChanged: (String) -> Unit,
    onStartNavigation: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        TextField(
            value = destinationText,
            onValueChange = onDestinationChanged,
            label = { Text("Enter Destination (e.g. Sydney)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onStartNavigation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Navigation")
        }
    }
}

@Composable
fun BleStatusComponent(status: String, onRetryScan: () -> Unit) {
    Column {
        Text(text = "BLE Status: $status")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetryScan) {
            Text("Retry Scan")
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    FAVORITES("Favorites", Icons.Default.Favorite),
    PROFILE("Profile", Icons.Default.AccountBox),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ARHUDTheme {
        Greeting("Android")
    }
}
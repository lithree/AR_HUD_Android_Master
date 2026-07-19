package com.example.arhud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.arhud.ui.theme.ARHUDTheme
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), OnMapReadyCallback {

    private lateinit var bleManager: BleManager
    private var bleStatus by mutableStateOf("Disconnected")
    private var sendJob: Job? = null
    private var BLECounter = 0
    private var isConnected = false
    private lateinit var mapView: MapView

    override fun onMapReady(googleMap: GoogleMap) {
        val sydney = LatLng(-33.8688, 151.2093)
        googleMap.addMarker(MarkerOptions().position(sydney).title("Marker in Sydney"))
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(sydney, 12f))
    }

    private fun startPeriodicSending() {
        sendJob?.cancel()
        sendJob = lifecycleScope.launch {
            while (isConnected) {
                bleManager.sendTurnAndLaneData(BLECounter++, 0, 2, 100)
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

        bleManager = BleManager(this)
        bleManager.callback = object : BleManager.BleCallback {
            override fun onScanFound(device: android.bluetooth.BluetoothDevice) {
                bleStatus = "Device found: ${device.name ?: "Unknown"}. Connecting..."
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
                // Keep the status as connected or active rather than overwriting with data sent success,
                // so the user knows they are still connected.
                bleStatus = "Connected (Data Sent: $success)"
            }

            override fun onError(message: String) {
                bleStatus = "Error: $message"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            bleStatus = "Scanning..."
            bleManager.startScan()
        }

        // Instantiate MapView programmatically to prevent XML parent-child attachment conflicts
        mapView = MapView(this).apply {
            onCreate(savedInstanceState)
            getMapAsync(this@MainActivity)
        }

        enableEdgeToEdge()
        setContent {
            ARHUDTheme {
                ARHUDApp(
                    status = bleStatus,
                    mapView = mapView,
                    onRetryScan = {
                        bleStatus = "Scanning..."
                        bleManager.startScan()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) {
            mapView.onStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) {
            mapView.onStop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) {
            mapView.onDestroy()
        }
        bleManager.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) {
            mapView.onSaveInstanceState(outState)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) {
            mapView.onLowMemory()
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
    }
}

@PreviewScreenSizes
@Composable
fun ARHUDApp(
    status: String = "Disconnected",
    mapView: MapView? = null,
    onRetryScan: () -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
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
                Greeting(name = "Android")
                Spacer(modifier = Modifier.height(16.dp))
                BleStatusComponent(status = status, onRetryScan = onRetryScan)
                Spacer(modifier = Modifier.height(16.dp))
                if (mapView != null) {
                    AndroidView(
                        factory = { mapView },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }
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
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    PROFILE("Profile", R.drawable.ic_account_box),
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
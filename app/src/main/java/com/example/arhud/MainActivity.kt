package com.example.arhud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.arhud.ui.theme.ARHUDTheme
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity()
{
    private lateinit var bleManager: BleManager
    private var bleStatus = mutableStateOf("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        bleManager = BleManager(this)
        bleManager.callback = object : BleManager.BleCallback {
            override fun onScanFound(device: android.bluetooth.BluetoothDevice) {
                bleStatus.value = "Device found: ${device.name ?: "Unknown"}. Connecting..."
            }

            override fun onConnected() {
                bleStatus.value = "Connected"
                bleManager.sendTurnAndLaneData(1, 0, 2, 100)
            }

            override fun onDisconnected() {
                bleStatus.value = "Disconnected"
            }

            override fun onDataSent(success: Boolean) {
                bleStatus.value = "Data Sent Success: $success"
            }

            override fun onError(message: String) {
                bleStatus.value = "Error: $message"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSIONS_REQUEST_CODE)
        } else {
            bleStatus.value = "Scanning..."
            bleManager.startScan()
        }

        enableEdgeToEdge()
        setContent {
            ARHUDTheme {
                ARHUDApp(status = bleStatus.value, onRetryScan = {
                    bleStatus.value = "Scanning..."
                    bleManager.startScan()
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.release()
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
    }
}

@PreviewScreenSizes @Composable fun ARHUDApp(status: String = "Disconnected", onRetryScan: () -> Unit = {})
{
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(icon = {
                    Icon(
                        painterResource(it.icon), contentDescription = it.label
                    )
                }, label = { Text(it.label) }, selected = it == currentDestination, onClick = { currentDestination = it })
            }
        }) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                Greeting(name = "Android")
                Spacer(modifier = Modifier.height(16.dp))
                BleStatusComponent(status = status, onRetryScan = onRetryScan)
            }
        }
    }
}

@Composable fun BleStatusComponent(status: String, onRetryScan: () -> Unit) {
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
)
{
    HOME("Home", R.drawable.ic_home), FAVORITES("Favorites", R.drawable.ic_favorite), PROFILE("Profile", R.drawable.ic_account_box),
}

@Composable fun Greeting(name: String, modifier: Modifier = Modifier)
{
    Text(
        text = "Hello $name!", modifier = modifier
    )
}

@Preview(showBackground = true) @Composable fun GreetingPreview()
{
    ARHUDTheme {
        Greeting("Android")
    }
}

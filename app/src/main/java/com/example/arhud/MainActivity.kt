package com.example.arhud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.arhud.ui.theme.ARHUDTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager.getInstance(this)
        
        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            val bleStatus by bleManager.status.collectAsState()
            
            ARHUDTheme {
                ARHUDMainScreen(
                    bleStatus = bleStatus,
                    onRetryScan = {
                        bleManager.startScan()
                    },
                    onDisconnect = {
                        bleManager.disconnect()
                    },
                    onOpenNavigation = {
                        startActivity(Intent(this, NavigationActivity::class.java))
                    },
                    onOpenConfig = {
                        startActivity(Intent(this, ConfigActivity::class.java))
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
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
            bleManager.startScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // We don't release singleton here as it might be used by NavigationActivity
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bleManager.startScan()
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector
) {
    BLE_STATUS("BLE Status", Icons.Default.Home),
    NAVIGATION("Navigation", Icons.Default.LocationOn),
    CONFIG("Config", Icons.Default.Settings)
}

@Composable
fun ARHUDMainScreen(
    bleStatus: String,
    onRetryScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenConfig: () -> Unit
) {
    var currentDestination by remember { mutableStateOf(AppDestinations.BLE_STATUS) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label
                        )
                    },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { 
                        when (destination) {
                            AppDestinations.NAVIGATION -> onOpenNavigation()
                            AppDestinations.CONFIG -> onOpenConfig()
                            AppDestinations.BLE_STATUS -> currentDestination = destination
                        }
                    }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                if (currentDestination == AppDestinations.BLE_STATUS) {
                    BleTabScreen(
                        status = bleStatus,
                        onRetryScan = onRetryScan,
                        onDisconnect = onDisconnect,
                        onOpenConfig = onOpenConfig
                    )
                }
            }
        }
    }
}

@Composable
fun BleTabScreen(
    status: String,
    onRetryScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConfig: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "BLE Device Controller", modifier = Modifier.padding(bottom = 16.dp))
        Text(text = "Current Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetryScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry Scan")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Disconnect")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onOpenConfig,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Text("Open Configuration")
        }
    }
}

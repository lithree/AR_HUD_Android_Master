package com.example.arhud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.arhud.ui.theme.ARHUDTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private const val PREFS_NAME = "ar_hud_config"
        private const val KEY_LUMINANCE = "key_luminance"
        private const val KEY_POWER = "key_power"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager.getInstance(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        checkAndRequestPermissions()

        enableEdgeToEdge()
        setContent {
            val bleStatus by bleManager.status.collectAsState()
            val isConnected by bleManager.isConnected.collectAsState()
            val imuData by bleManager.imuData.collectAsState()
            val hudDebugData by bleManager.hudDebugData.collectAsState()

            var luminance by remember { mutableFloatStateOf(prefs.getFloat(KEY_LUMINANCE, 75f)) }
            var power by remember { mutableStateOf(prefs.getBoolean(KEY_POWER, true)) }

            // Sync shared preferences when activity resumes
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        luminance = prefs.getFloat(KEY_LUMINANCE, 75f)
                        power = prefs.getBoolean(KEY_POWER, true)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            ARHUDTheme(darkTheme = true) {
                MinimalMetroScreen(
                    bleStatus = bleStatus,
                    isConnected = isConnected,
                    imuData = imuData,
                    hudDebugData = hudDebugData,
                    luminance = luminance,
                    power = power,
                    onLuminanceChange = { newLuminance ->
                        luminance = newLuminance
                        prefs.edit().putFloat(KEY_LUMINANCE, newLuminance).apply()
                    },
                    onPowerToggle = { newPower ->
                        power = newPower
                        prefs.edit().putBoolean(KEY_POWER, newPower).apply()
                    },
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
}

// -------------------------------------------------------------------------
// Minimal Microsoft Modern UI Tile Colors
// -------------------------------------------------------------------------
private object MetroColors {
    val Background = Color(0xFF0C0C0E)
    val SurfaceDark = Color(0xFF16161B)
    val TileCobalt = Color(0xFF0078D7)     // Microsoft Blue
    val TileTeal = Color(0xFF008272)       // Modern Teal
    val TileEmerald = Color(0xFF107C41)    // Emerald Green
    val TilePurple = Color(0xFF6B69D6)     // Modern Violet
    val TileAmber = Color(0xFFD83B01)      // Vibrant Amber
    val TileSapphire = Color(0xFF005A9E)   // Sapphire Blue
    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFF8E8E9F)
    val StatusOnline = Color(0xFF10893E)
    val StatusScanning = Color(0xFFFFB900)
    val StatusOffline = Color(0xFFD13438)
}

@Composable
fun MinimalMetroScreen(
    bleStatus: String,
    isConnected: Boolean,
    imuData: ImuData?,
    hudDebugData: HudDebugData,
    luminance: Float,
    power: Boolean,
    onLuminanceChange: (Float) -> Unit,
    onPowerToggle: (Boolean) -> Unit,
    onRetryScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenConfig: () -> Unit
) {
    Scaffold(
        containerColor = MetroColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
        ) {
            // Minimal Header
            MinimalHeader(isConnected = isConnected, bleStatus = bleStatus)

            Spacer(modifier = Modifier.height(14.dp))

            // 2-Column Minimal Modern UI Live Tile Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. HERO TILE: Navigation (Span 2)
                item(span = { GridItemSpan(2) }) {
                    MinimalNavHeroTile(onClick = onOpenNavigation)
                }

                // 2. TILE: BLE Status & Action (1 col)
                item {
                    MinimalBleTile(
                        isConnected = isConnected,
                        bleStatus = bleStatus,
                        onScan = onRetryScan,
                        onDisconnect = onDisconnect
                    )
                }

                // 3. TILE: Display Power & Luminance Slider (1 col)
                item {
                    MinimalPowerTile(
                        power = power,
                        luminance = luminance,
                        onLuminanceChange = onLuminanceChange,
                        onToggle = { onPowerToggle(!power) }
                    )
                }

                // 4. TILE: Heading (1 col)
                item {
                    MinimalHeadingTile(imuData = imuData)
                }

                // 5. TILE: Speedometer (1 col)
                item {
                    MinimalSpeedTile(speedKmH = hudDebugData.speedKmH)
                }

                // 6. HERO TILE: Config (Span 2)
                item(span = { GridItemSpan(2) }) {
                    MinimalConfigHeroTile(onClick = onOpenConfig)
                }

                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Minimal Top Header
// -------------------------------------------------------------------------
@Composable
private fun MinimalHeader(isConnected: Boolean, bleStatus: String) {
    val statusColor = when {
        isConnected -> MetroColors.StatusOnline
        bleStatus.contains("Scanning", ignoreCase = true) -> MetroColors.StatusScanning
        else -> MetroColors.StatusOffline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ARHUD",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = MetroColors.TextPrimary
        )

        // Minimal Status Indicator Pill
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = statusColor.copy(alpha = 0.15f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isConnected -> "LIVE"
                        bleStatus.contains("Scanning", ignoreCase = true) -> "SCAN"
                        else -> "OFF"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = statusColor
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Base Tile Card
// -------------------------------------------------------------------------
@Composable
private fun MetroCard(
    backgroundColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.96f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "tileScale"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        color = backgroundColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        content()
    }
}

// -------------------------------------------------------------------------
// 1. Navigation Hero Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalNavHeroTile(onClick: () -> Unit) {
    MetroCard(
        backgroundColor = MetroColors.TileCobalt,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Navigation",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "START >",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

// -------------------------------------------------------------------------
// 2. BLE Controller Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalBleTile(
    isConnected: Boolean,
    bleStatus: String,
    onScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isScanning = bleStatus.contains("Scanning", ignoreCase = true)
    val isConnecting = bleStatus.contains("Connecting", ignoreCase = true) || bleStatus.contains("Discovering", ignoreCase = true)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    MetroCard(
        backgroundColor = if (isConnected) MetroColors.TileSapphire else MetroColors.SurfaceDark,
        onClick = {
            if (isConnected) onDisconnect() else onScan()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(152.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BLE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = if (isConnected) Color.White.copy(alpha = 0.8f) else MetroColors.TextMuted
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when {
                                isConnected -> MetroColors.StatusOnline
                                isScanning || isConnecting -> MetroColors.StatusScanning.copy(alpha = pulseAlpha)
                                else -> MetroColors.StatusOffline
                            },
                            CircleShape
                        )
                )
            }

            Text(
                text = when {
                    isConnected -> "Linked"
                    isConnecting -> "Connecting"
                    isScanning -> "Scanning"
                    else -> "Offline"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                shape = RoundedCornerShape(4.dp),
                color = if (isConnected) Color.White.copy(alpha = 0.2f) else MetroColors.TileCobalt
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isConnected) "Disconnect" else if (isScanning) "Scanning..." else "Scan",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 3. Display Power & Luminance Slider Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalPowerTile(
    power: Boolean,
    luminance: Float,
    onLuminanceChange: (Float) -> Unit,
    onToggle: () -> Unit
) {
    MetroCard(
        backgroundColor = if (power) MetroColors.TileAmber else MetroColors.SurfaceDark,
        onClick = onToggle, // The whole tile surface triggers power toggle
        modifier = Modifier
            .fillMaxWidth()
            .height(152.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Upper Section: Header, Percentage Readout & Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "DISPLAY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (power) Color.White.copy(alpha = 0.85f) else MetroColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (power) "${luminance.roundToInt()}%" else "OFF",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (power) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (power) MetroColors.StatusOnline else MetroColors.StatusOffline,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (power) "ON" else "OFF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Luminance Slider
            Slider(
                value = luminance,
                onValueChange = onLuminanceChange,
                valueRange = 0f..100f,
                enabled = power,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledThumbColor = Color.White.copy(alpha = 0.3f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.2f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            )
        }
    }
}

// -------------------------------------------------------------------------
// 4. Heading Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalHeadingTile(imuData: ImuData?) {
    MetroCard(
        backgroundColor = MetroColors.TileEmerald,
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "HEADING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.85f)
            )

            Text(
                text = imuData?.yaw?.let { "$it°" } ?: "--°",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Degrees",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

// -------------------------------------------------------------------------
// 5. Speedometer Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalSpeedTile(speedKmH: Int) {
    MetroCard(
        backgroundColor = MetroColors.TilePurple,
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SPEED",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.85f)
            )

            Text(
                text = "$speedKmH",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "km/h",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

// -------------------------------------------------------------------------
// 6. Configuration Hero Tile
// -------------------------------------------------------------------------
@Composable
private fun MinimalConfigHeroTile(onClick: () -> Unit) {
    MetroCard(
        backgroundColor = MetroColors.TileTeal,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Developer Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

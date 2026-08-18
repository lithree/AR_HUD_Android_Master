package com.example.arhud

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arhud.ui.theme.ARHUDTheme

class ConfigActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "ar_hud_config"
        private const val KEY_DEV_MODE_SELECTED = "key_dev_mode_selected"
        private const val KEY_DEV_MODE_1 = "key_dev_mode_1"
        private const val KEY_DEV_MODE_2 = "key_dev_mode_2"
        private const val KEY_DISPLAY_HUD_SIMULATOR = "key_display_hud_simulator"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager.getInstance(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Default to mode 3 (Normal mode) if not set
        val initialDevMode = prefs.getInt(
            KEY_DEV_MODE_SELECTED,
            when {
                prefs.getBoolean(KEY_DEV_MODE_1, false) -> 1
                prefs.getBoolean(KEY_DEV_MODE_2, false) -> 2
                else -> 3
            }
        )
        val initialDisplayHudSimulator = prefs.getBoolean(KEY_DISPLAY_HUD_SIMULATOR, true)

        enableEdgeToEdge()
        setContent {
            val bleStatus by bleManager.status.collectAsState()
            val isConnected by bleManager.isConnected.collectAsState()

            ARHUDTheme {
                ConfigScreen(
                    initialDevMode = initialDevMode,
                    initialDisplayHudSimulator = initialDisplayHudSimulator,
                    bleStatus = bleStatus,
                    isConnected = isConnected,
                    onDevModeSelected = { mode ->
                        prefs.edit()
                            .putInt(KEY_DEV_MODE_SELECTED, mode)
                            .putBoolean(KEY_DEV_MODE_1, mode == 1)
                            .putBoolean(KEY_DEV_MODE_2, mode == 2)
                            .apply()
                    },
                    onDisplayHudSimulatorChange = { enabled ->
                        prefs.edit().putBoolean(KEY_DISPLAY_HUD_SIMULATOR, enabled).apply()
                    },
                    onBackPressed = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    initialDevMode: Int,
    initialDisplayHudSimulator: Boolean,
    bleStatus: String,
    isConnected: Boolean,
    onDevModeSelected: (Int) -> Unit,
    onDisplayHudSimulatorChange: (Boolean) -> Unit,
    onBackPressed: () -> Unit
) {
    var selectedMode by remember { mutableIntStateOf(initialDevMode) }
    var displayHudSimulator by remember { mutableStateOf(initialDisplayHudSimulator) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Configuration",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // BLE Connection Status Card
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Status",
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "BLE Device Status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = bleStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Developer Mode Section Card (Single-choice Radio Buttons)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Developer Options",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Developer Mode 1: Simulated Navigation
                    DevModeRadioRow(
                        title = "Developer mode 1",
                        subtitle = "Simulated Navigation",
                        isSelected = selectedMode == 1,
                        onSelect = {
                            selectedMode = 1
                            onDevModeSelected(1)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Developer Mode 2: Simulated GPS
                    DevModeRadioRow(
                        title = "Developer mode 2",
                        subtitle = "Simulated GPS",
                        isSelected = selectedMode == 2,
                        onSelect = {
                            selectedMode = 2
                            onDevModeSelected(2)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Developer Mode 3: Normal mode
                    DevModeRadioRow(
                        title = "Developer mode 3",
                        subtitle = "Normal mode",
                        isSelected = selectedMode == 3,
                        onSelect = {
                            selectedMode = 3
                            onDevModeSelected(3)
                        }
                    )
                }
            }

            // HUD Simulator Display Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val next = !displayHudSimulator
                                displayHudSimulator = next
                                onDisplayHudSimulatorChange(next)
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Display HUD simulator",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Show real-time HUD preview widget during navigation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = displayHudSimulator,
                            onCheckedChange = { isChecked ->
                                displayHudSimulator = isChecked
                                onDisplayHudSimulatorChange(isChecked)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DevModeRadioRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            )
        }

        RadioButton(
            selected = isSelected,
            onClick = onSelect
        )
    }
}

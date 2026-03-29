package com.conuhacks.blearduinov1

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.conuhacks.blearduinov1.ui.*
import com.conuhacks.blearduinov1.ui.theme.VesperTheme

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private val handler = Handler(Looper.getMainLooper())

    // Compose state
    private var statusText by mutableStateOf("En attente...")
    private var connectionState by mutableStateOf(ConnectionState.IDLE)
    private var currentActivity by mutableStateOf<ActivityType?>(null)
    private var emergencyNumber by mutableStateOf("")
    private var isScanning by mutableStateOf(false)
    private var fallCount by mutableIntStateOf(0)
    private val foundDevices = mutableStateListOf<DeviceInfo>()
    private val logLines = mutableStateListOf<String>()

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("vesper", MODE_PRIVATE)
        emergencyNumber = prefs.getString("emergency_number", "") ?: ""

        requestPermissions()
        requestOverlayPermission()

        setContent {
            VesperTheme {
                BroadcastReceiverEffect()

                VesperScreen(
                    statusText = statusText,
                    connectionState = connectionState,
                    currentActivity = currentActivity,
                    emergencyNumber = emergencyNumber,
                    onEmergencyNumberChange = { emergencyNumber = it },
                    isScanning = isScanning,
                    devices = foundDevices,
                    onScanClick = ::onScanClicked,
                    onDisconnectClick = ::onDisconnectClicked,
                    onDeviceClick = ::connectToDevice,
                    onSettingsClick = ::openAppSettings,
                    onHistoryClick = ::openHistory,
                    fallCount = fallCount,
                    logLines = logLines,
                )
            }
        }
    }

    @Composable
    private fun BroadcastReceiverEffect() {
        val context = this
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BleService.BROADCAST_STATUS -> {
                            val msg = intent.getStringExtra(BleService.EXTRA_STATUS_MSG) ?: return
                            statusText = msg
                            addLog(msg)
                            updateConnectionState(msg)
                        }
                        BleService.BROADCAST_FALL -> {
                            statusText = "!!! CHUTE DÉTECTÉE !!!"
                            connectionState = ConnectionState.FALL_DETECTED
                            fallCount++
                            addLog("CHUTE DÉTECTÉE — appel dans 5s")
                        }
                        BleService.BROADCAST_ACTIVITY -> {
                            val activity = intent.getStringExtra(BleService.EXTRA_ACTIVITY) ?: return
                            val confidence = intent.getFloatExtra(BleService.EXTRA_CONFIDENCE, 0f)
                            currentActivity = when (activity) {
                                "walk" -> ActivityType.WALK
                                "stand" -> ActivityType.STAND
                                "sit" -> ActivityType.SIT
                                "lying" -> ActivityType.LYING
                                else -> null
                            }
                            addLog("Activity: $activity (${(confidence * 100).toInt()}%)")
                        }
                        BleService.BROADCAST_NEAR_FALL -> {
                            addLog("Near-fall detected!")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BleService.BROADCAST_STATUS)
                addAction(BleService.BROADCAST_FALL)
                addAction(BleService.BROADCAST_ACTIVITY)
                addAction(BleService.BROADCAST_NEAR_FALL)
            }
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    private fun updateConnectionState(msg: String) {
        val lower = msg.lowercase()
        when {
            lower.contains("surveillance active") || lower.contains("notifications activées") -> {
                connectionState = ConnectionState.CONNECTED
            }
            lower.contains("connecté!") || lower.contains("recherche des services") -> {
                connectionState = ConnectionState.CONNECTED
            }
            lower.contains("déconnecté") -> {
                connectionState = ConnectionState.DISCONNECTED
            }
            lower.contains("chute") -> {
                connectionState = ConnectionState.FALL_DETECTED
            }
        }
        // Parse activity from BLE data
        when {
            lower.contains("walking") || lower.contains("running") -> currentActivity = ActivityType.WALK
            lower.contains("standing") -> currentActivity = ActivityType.STAND
            lower.contains("sitting") -> currentActivity = ActivityType.SIT
            lower.contains("lying") || lower.contains("fall") -> currentActivity = ActivityType.LYING
        }
    }

    // ---- Permissions ----

    private fun openHistory() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isNotEmpty()) {
                addLog("Permissions manquantes: ${denied.joinToString()}")
            }
        }
    }

    // ---- BLE Scan ----

    private fun onScanClicked() {
        if (isScanning) {
            stopScan()
            return
        }
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            addLog("Active le Bluetooth d'abord!")
            return
        }
        startScan(btAdapter)
    }

    private fun startScan(adapter: BluetoothAdapter) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            addLog("Permission BLUETOOTH_SCAN manquante")
            return
        }
        foundDevices.clear()
        isScanning = true
        statusText = "Scan en cours..."
        adapter.bluetoothLeScanner?.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 10000)
    }

    private fun stopScan() {
        if (!isScanning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED) {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        isScanning = false
        statusText = "Scan terminé — ${foundDevices.size} appareil(s)"
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return
            val name = result.device.name ?: return
            val address = result.device.address
            if (foundDevices.any { it.address == address }) return
            val info = DeviceInfo(name, address)
            runOnUiThread {
                foundDevices.add(info)
                addLog("Trouvé: $name")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                addLog("Erreur de scan: $errorCode")
                isScanning = false
            }
        }
    }

    // ---- Connect / Disconnect ----

    private fun connectToDevice(device: DeviceInfo) {
        stopScan()
        val phone = emergencyNumber.trim()
        if (phone.isEmpty()) {
            addLog("Entre un numéro d'urgence d'abord!")
            return
        }
        getSharedPreferences("vesper", MODE_PRIVATE)
            .edit().putString("emergency_number", phone).apply()

        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.ACTION_CONNECT
            putExtra(BleService.EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(BleService.EXTRA_PHONE_NUMBER, phone)
        }
        startForegroundService(intent)
        addLog("Connexion à ${device.name}...")
        statusText = "Connexion en cours..."
    }

    private fun onDisconnectClicked() {
        val intent = Intent(this, BleService::class.java).apply {
            action = BleService.ACTION_DISCONNECT
        }
        startService(intent)
        statusText = "Déconnecté"
        connectionState = ConnectionState.DISCONNECTED
        addLog("Déconnecté")
    }

    // ---- Log ----

    private fun addLog(msg: String) {
        logLines.add(msg)
        if (logLines.size > 50) logLines.removeAt(0)
    }
}

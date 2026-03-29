package com.conuhacks.blearduinov1

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.conuhacks.blearduinov1.data.VesperDatabase
import com.conuhacks.blearduinov1.data.VesperEvent
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class BleService : Service() {

    companion object {
        const val TAG = "VesperBLE"

        val HM10_SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val HM10_CHAR_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

        const val CHANNEL_ID = "vesper_channel"
        const val NOTIFICATION_ID = 1
        const val FALL_NOTIFICATION_ID = 2

        const val ACTION_CONNECT = "ACTION_CONNECT"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_SEND_PING = "ACTION_SEND_PING"
        const val ACTION_TEST_CALL = "ACTION_TEST_CALL"
        const val EXTRA_DEVICE_ADDRESS = "DEVICE_ADDRESS"
        const val EXTRA_PHONE_NUMBER = "PHONE_NUMBER"

        const val BROADCAST_STATUS = "com.conuhacks.blearduinov1.STATUS"
        const val BROADCAST_FALL = "com.conuhacks.blearduinov1.FALL"
        const val BROADCAST_ACTIVITY = "com.conuhacks.blearduinov1.ACTIVITY"
        const val BROADCAST_NEAR_FALL = "com.conuhacks.blearduinov1.NEAR_FALL"
        const val EXTRA_STATUS_MSG = "STATUS_MSG"
        const val EXTRA_ACTIVITY = "ACTIVITY"
        const val EXTRA_CONFIDENCE = "CONFIDENCE"

        const val BUFFER_SIZE = 312
        const val SLIDE_STEP = 156
        const val NEAR_FALL_THRESHOLD = 3.5f
        const val NEAR_FALL_COOLDOWN_MS = 10_000L
        private val CLASSES = arrayOf("lying", "sit", "stand", "walk")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var emergencyNumber: String = ""
    private var isConnected = false
    private var wakeLock: PowerManager.WakeLock? = null

    // TFLite
    private var tfliteInterpreter: Interpreter? = null
    private val accBuffer = FloatArray(BUFFER_SIZE * 3) // ax,ay,az interleaved
    private var bufferIndex = 0
    private var bufferFull = false
    private var lastPredictedActivity = ""

    // Room
    private lateinit var db: VesperDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // BLE data accumulation (HM-10 may split messages)
    private val bleDataBuffer = StringBuilder()
    private var lastNearFallTime = 0L

    // ---- Service Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        db = VesperDatabase.getInstance(this)
        initTflite()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vesper::BleWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun initTflite() {
        try {
            val afd = assets.openFd("cnn_model.tflite")
            val fis = afd.createInputStream()
            val fileChannel = fis.channel
            val mappedBuffer = fileChannel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                afd.startOffset, afd.declaredLength,
            )
            tfliteInterpreter = Interpreter(mappedBuffer)
            Log.d(TAG, "TFLite model loaded")
        } catch (e: Exception) {
            Log.e(TAG, "TFLite init failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isConnected) {
            startForeground(NOTIFICATION_ID, buildNotification("En attente de connexion..."))
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_STICKY
                emergencyNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                connectToDevice(address)
            }
            ACTION_DISCONNECT -> {
                disconnect()
                stopSelf()
            }
            ACTION_SEND_PING -> sendPing()
            ACTION_TEST_CALL -> {
                sendStatus("TEST: Lancement appel vers $emergencyNumber")
                makeEmergencyCall()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        disconnect()
        tfliteInterpreter?.close()
        tfliteInterpreter = null
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- BLE Connection ----

    private fun connectToDevice(address: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Erreur: Permission Bluetooth manquante")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            sendStatus("Erreur: Bluetooth non disponible"); return
        }
        val device = adapter.getRemoteDevice(address)
        sendStatus("Connexion à ${device.name ?: address}...")
        bluetoothGatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        isConnected = false
    }

    // ---- GATT Callbacks ----

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@BleService, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    sendStatus("Connecté! Recherche des services...")
                    updateNotification("Connecté à Vesper")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    sendStatus("Déconnecté")
                    updateNotification("Déconnecté — tentative de reconnexion...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isConnected && bluetoothGatt != null) gatt.connect()
                    }, 3000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                sendStatus("Erreur: services non trouvés"); return
            }
            if (ActivityCompat.checkSelfPermission(this@BleService, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return

            val service = gatt.getService(HM10_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HM10_CHAR_UUID)
            if (characteristic == null) {
                sendStatus("Erreur: Service/Caractéristique HM-10 non trouvé"); return
            }

            gatt.setCharacteristicNotification(characteristic, true)

            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }

            sendStatus("Prêt — surveillance active")
            updateNotification("Surveillance active")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD write success")
            } else {
                sendStatus("ERREUR activation notifications: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleBleData(String(value))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleBleData(characteristic.getStringValue(0))
        }
    }

    // ---- BLE Data Parsing ----

    private fun handleBleData(raw: String) {
        bleDataBuffer.append(raw)
        while (true) {
            val newlineIdx = bleDataBuffer.indexOf('\n')
            if (newlineIdx == -1) break
            val line = bleDataBuffer.substring(0, newlineIdx).trim()
            bleDataBuffer.delete(0, newlineIdx + 1)
            if (line.isNotEmpty()) processLine(line)
        }
    }

    private var lineCount = 0

    private fun processLine(data: String) {
        lineCount++
        Log.d(TAG, "BLE line #$lineCount: $data")
        // Show first 5 lines in app so user can see the format
        if (lineCount <= 5) {
            sendStatus("BLE[$lineCount]: $data")
        }
        when {
            data.startsWith("D,") -> parseDataLine(data)
            data.contains("FALL") && !data.startsWith("D,") -> {
                Log.w(TAG, "!!! CHUTE DÉTECTÉE (Arduino) !!! raw='$data'")
                sendStatus("CHUTE DÉTECTÉE!")
                sendBroadcast(Intent(BROADCAST_FALL).apply { setPackage(packageName) })
                logEvent("FALL", lastPredictedActivity, 0f, "Arduino threshold detection")
                onFallDetected()
            }
            data.contains("VESPER_READY") -> sendStatus("Arduino prêt — surveillance active")
            data.contains("PONG") -> sendStatus("Connexion vérifiée (PONG reçu)")
            else -> {
                // Try parsing as raw CSV (ax,ay,az or ax,ay,az,gx,gy,gz)
                val parts = data.split(",")
                if (parts.size >= 3 && parts[0].toFloatOrNull() != null) {
                    parseDataLine("D,$data")
                }
            }
        }
    }

    private fun parseDataLine(data: String) {
        val parts = data.split(",")
        if (parts.size < 4) return
        var ax = parts[1].toFloatOrNull() ?: return
        var ay = parts[2].toFloatOrNull() ?: return
        var az = parts[3].toFloatOrNull() ?: return
        // Arduino sends integers (value * 100) — convert back to g
        if (ax > 50f || ax < -50f || ay > 50f || ay < -50f || az > 50f || az < -50f) {
            ax /= 100f; ay /= 100f; az /= 100f
        }

        // Near-fall detection (with cooldown to avoid flooding)
        val accMag = sqrt(ax * ax + ay * ay + az * az)
        val now = System.currentTimeMillis()
        if (accMag > NEAR_FALL_THRESHOLD && now - lastNearFallTime > NEAR_FALL_COOLDOWN_MS) {
            lastNearFallTime = now
            sendBroadcast(Intent(BROADCAST_NEAR_FALL).apply { setPackage(packageName) })
            logEvent("NEAR_FALL", lastPredictedActivity, accMag, "acc=${"%.2f".format(accMag)}g")
        }

        // Add to buffer
        val base = bufferIndex * 3
        accBuffer[base] = ax
        accBuffer[base + 1] = ay
        accBuffer[base + 2] = az
        bufferIndex++

        // Show buffer progress every 50 samples
        if (bufferIndex % 50 == 0) {
            sendStatus("Buffer: $bufferIndex/$BUFFER_SIZE samples")
        }

        if (bufferIndex >= BUFFER_SIZE) {
            bufferFull = true
            runInference()
            // Slide by SLIDE_STEP: copy last (BUFFER_SIZE - SLIDE_STEP) samples to beginning
            val keepSamples = BUFFER_SIZE - SLIDE_STEP
            System.arraycopy(accBuffer, SLIDE_STEP * 3, accBuffer, 0, keepSamples * 3)
            bufferIndex = keepSamples
        }
    }

    // ---- TFLite Inference ----

    private fun runInference() {
        val interpreter = tfliteInterpreter ?: return

        // Build input: (1, 312, 3) with channels: norm_xyz, norm_yz, abs_x
        val inputBuffer = ByteBuffer.allocateDirect(1 * BUFFER_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until BUFFER_SIZE) {
            val ax = accBuffer[i * 3]
            val ay = accBuffer[i * 3 + 1]
            val az = accBuffer[i * 3 + 2]
            inputBuffer.putFloat(sqrt(ax * ax + ay * ay + az * az)) // norm_xyz
            inputBuffer.putFloat(sqrt(ay * ay + az * az))           // norm_yz
            inputBuffer.putFloat(abs(ax))                           // abs_x
        }

        val output = Array(1) { FloatArray(4) }
        try {
            interpreter.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed: ${e.message}")
            return
        }

        val probs = output[0]
        var maxIdx = 0
        for (i in 1 until probs.size) {
            if (probs[i] > probs[maxIdx]) maxIdx = i
        }
        val predicted = CLASSES[maxIdx]
        val confidence = probs[maxIdx]

        // Broadcast activity
        sendBroadcast(Intent(BROADCAST_ACTIVITY).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVITY, predicted)
            putExtra(EXTRA_CONFIDENCE, confidence)
        })

        // Log activity change
        if (predicted != lastPredictedActivity) {
            // Detect fall via model: lying with high confidence after walk/stand
            if (predicted == "lying" && confidence > 0.8f &&
                (lastPredictedActivity == "walk" || lastPredictedActivity == "stand")) {
                Log.w(TAG, "!!! CHUTE DÉTECTÉE (modèle) !!!")
                sendStatus("CHUTE DÉTECTÉE (modèle)!")
                sendBroadcast(Intent(BROADCAST_FALL).apply { setPackage(packageName) })
                logEvent("FALL", predicted, 0f, "Model: $lastPredictedActivity→lying (${
                    "%.0f".format(confidence * 100)
                }%)")
                onFallDetected()
            }

            logEvent("ACTIVITY_CHANGE", predicted, 0f,
                "$lastPredictedActivity→$predicted (${"%.0f".format(confidence * 100)}%)")
            lastPredictedActivity = predicted
        }
    }

    // ---- Room logging ----

    private fun logEvent(type: String, activity: String, accMag: Float, details: String) {
        serviceScope.launch {
            try {
                db.vesperEventDao().insert(
                    VesperEvent(
                        eventType = type,
                        activity = activity,
                        accMagnitude = accMag,
                        details = details,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "DB insert failed: ${e.message}")
            }
        }
    }

    // ---- Envoi BLE (test PING) ----

    private fun sendPing() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Erreur: Permission Bluetooth manquante"); return
        }
        val gatt = bluetoothGatt
        if (gatt == null || !isConnected) {
            sendStatus("Erreur: Non connecté"); return
        }
        val characteristic = gatt.getService(HM10_SERVICE_UUID)?.getCharacteristic(HM10_CHAR_UUID)
        if (characteristic == null) {
            sendStatus("Erreur: Caractéristique non trouvée"); return
        }
        val data = "PING\n".toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        sendStatus("PING envoyé")
    }

    // ---- Réaction à une chute ----

    private fun onFallDetected() {
        if (emergencyNumber.isNotBlank()) {
            launchFallAlert()
        } else {
            showFallNotification()
            sendStatus("Pas de numéro d'urgence configuré")
        }
    }

    private fun launchFallAlert() {
        val alertIntent = Intent(this, FallAlertActivity::class.java).apply {
            putExtra(FallAlertActivity.EXTRA_PHONE, emergencyNumber)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 1, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CHUTE DÉTECTÉE!")
            .setContentText("Appel d'urgence dans 5 secondes...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(FALL_NOTIFICATION_ID, notification)

        var activityLaunched = false
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(alertIntent); activityLaunched = true }
            catch (e: Exception) { Log.w(TAG, "Activity launch failed: ${e.message}") }
        }
        if (!activityLaunched) {
            sendStatus("Alerte chute — appel dans 5s")
            Handler(Looper.getMainLooper()).postDelayed({ wakeScreenAndCall() }, 5000)
        } else {
            sendStatus("Alerte chute — appel dans 5s")
        }
    }

    private fun wakeScreenAndCall() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val screenWake = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Vesper::CallWake",
        )
        screenWake.acquire(10_000L)
        Handler(Looper.getMainLooper()).postDelayed({
            makeEmergencyCall()
            screenWake.release()
        }, 500)
    }

    private fun makeEmergencyCall() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            sendStatus("Erreur: Permission d'appel manquante"); return
        }
        startActivity(Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$emergencyNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        sendStatus("Appel en cours: $emergencyNumber")
    }

    // ---- Notifications ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Vesper Fall Detection", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Surveillance des chutes en arrière-plan"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vesper").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun showFallNotification() {
        getSystemService(NotificationManager::class.java).notify(FALL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CHUTE DÉTECTÉE!")
                .setContentText("Appel d'urgence dans 5 secondes...")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true).build())
    }

    private fun sendStatus(message: String) {
        Log.d(TAG, "Status: $message")
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MSG, message)
        })
    }
}

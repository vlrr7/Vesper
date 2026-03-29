package com.conuhacks.blearduinov1

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.conuhacks.blearduinov1.ui.theme.VesperTheme

class FallAlertActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PHONE = "PHONE"
        const val TAG = "VesperFallAlert"
    }

    private var countdownSeconds by mutableIntStateOf(5)
    private var cancelled = false
    private var timer: CountDownTimer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forcer l'écran à s'allumer
        wakeScreen()

        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""

        timer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownSeconds = (millisUntilFinished / 1000).toInt() + 1
            }

            override fun onFinish() {
                if (!cancelled) {
                    makeCall(phone)
                }
            }
        }.start()

        setContent {
            VesperTheme {
                FallAlertScreen(
                    seconds = countdownSeconds,
                    phone = phone,
                    onCancel = {
                        cancelled = true
                        timer?.cancel()
                        finish()
                    },
                )
            }
        }
    }

    private fun wakeScreen() {
        // Allumer l'écran
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "Vesper::FallAlert"
        )
        wakeLock?.acquire(30_000L)

        // Passer par-dessus le lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, null)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun makeCall(phone: String) {
        if (phone.isBlank()) {
            Log.e(TAG, "Numéro vide")
            finish()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission CALL_PHONE manquante")
            finish()
            return
        }
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phone")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(callIntent)
        Log.w(TAG, "Appel d'urgence lancé: $phone")
        finish()
    }

    override fun onDestroy() {
        timer?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

@Composable
private fun FallAlertScreen(seconds: Int, phone: String, onCancel: () -> Unit) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF3B3B),
                modifier = Modifier
                    .size(80.dp)
                    .scale(scale),
            )

            Text(
                text = "CHUTE DÉTECTÉE",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF3B3B),
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Appel vers $phone\ndans $seconds secondes",
                fontSize = 18.sp,
                color = Color(0xFFF5F5F5),
                textAlign = TextAlign.Center,
                lineHeight = 26.sp,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF242424),
                    contentColor = Color(0xFFF5F5F5),
                ),
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Annuler", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

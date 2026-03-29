package com.conuhacks.blearduinov1

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.conuhacks.blearduinov1.data.VesperDatabase
import com.conuhacks.blearduinov1.ui.HistoryScreen
import com.conuhacks.blearduinov1.ui.theme.VesperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : ComponentActivity() {

    private val dao by lazy { VesperDatabase.getInstance(this).vesperEventDao() }
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VesperTheme {
                val events by dao.getAllEvents().collectAsState(initial = emptyList())

                HistoryScreen(
                    events = events,
                    onBackClick = { finish() },
                    onExportClick = { exportCsv(events) },
                    onClearClick = {
                        scope.launch { dao.deleteAll() }
                        Toast.makeText(this@HistoryActivity, "History cleared", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    private fun exportCsv(events: List<com.conuhacks.blearduinov1.data.VesperEvent>) {
        scope.launch {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@launch
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
                val file = File(dir, "vesper_${dateFormat.format(Date())}.csv")
                FileWriter(file).use { w ->
                    w.append("id,timestamp,eventType,activity,accMagnitude,details\n")
                    events.forEach { e ->
                        w.append("${e.id},${e.timestamp},${e.eventType},${e.activity},${e.accMagnitude},\"${e.details}\"\n")
                    }
                }
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                    // Share file
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@HistoryActivity,
                        "$packageName.fileprovider",
                        file,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Export Vesper History"))
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@HistoryActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

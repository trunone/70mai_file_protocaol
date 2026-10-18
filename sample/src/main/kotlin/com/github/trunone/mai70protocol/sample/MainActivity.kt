package com.github.trunone.mai70protocol.sample

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.trunone.mai70protocol.Mai70Client
import com.github.trunone.mai70protocol.android.AndroidNetworkTransport
import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var editIp: EditText
    private lateinit var editPort: EditText
    private lateinit var chkHttpLogging: CheckBox
    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var lastListedFiles = mutableListOf<FileEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editIp = findViewById(R.id.editIp)
        editPort = findViewById(R.id.editPort)
        chkHttpLogging = findViewById(R.id.chkHttpLogging)
        txtLog = findViewById(R.id.txtLog)
        scrollLog = findViewById(R.id.scrollLog)

        findViewById<Button>(R.id.btnPair).setOnClickListener { executeAction { client -> pair(client) } }
        findViewById<Button>(R.id.btnRegister).setOnClickListener { executeAction { client -> register(client) } }
        findViewById<Button>(R.id.btnAlbumModeEnable).setOnClickListener { executeAction { client -> setAlbumMode(client, true) } }
        findViewById<Button>(R.id.btnAlbumModeDisable).setOnClickListener { executeAction { client -> setAlbumMode(client, false) } }
        findViewById<Button>(R.id.btnGetCounts).setOnClickListener { executeAction { client -> getFileCounts(client) } }
        findViewById<Button>(R.id.btnListFiles).setOnClickListener { executeAction { client -> listFiles(client) } }
        findViewById<Button>(R.id.btnDownloadSample).setOnClickListener { executeAction { client -> downloadSample(client) } }
        findViewById<Button>(R.id.btnGetThumbnailSample).setOnClickListener { executeAction { client -> getThumbnailSample(client) } }
        findViewById<Button>(R.id.btnDeleteLast).setOnClickListener { executeAction { client -> deleteLast(client) } }
    }

    private fun log(message: String) {
        val currentText = txtLog.text.toString()
        txtLog.text = "$currentText\n$message"
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun executeAction(block: suspend (Mai70Client) -> Unit) {
        val host = editIp.text.toString().ifEmpty { "192.168.0.1" }
        val port = editPort.text.toString().toIntOrNull() ?: 80

        activityScope.launch {
            try {
                val transport = AndroidNetworkTransport(
                    context = this@MainActivity,
                    defaultCameraIp = host,
                    onHttpLog = if (chkHttpLogging.isChecked) { logMsg ->
                        activityScope.launch(Dispatchers.Main) {
                            log("[HTTP] $logMsg")
                        }
                    } else null
                )
                val client = Mai70Client(transport = transport, host = host, port = port)
                block(client)
            } catch (e: Exception) {
                log("Error: ${e.message}")
            }
        }
    }

    private suspend fun pair(client: Mai70Client) {
        log("--> Initiating pair()...")
        val token = withContext(Dispatchers.IO) {
            client.pair(
                onWaitingForButton = {
                    withContext(Dispatchers.Main) {
                        log("[WAITING] Press power button on dashcam to confirm...")
                    }
                }
            )
        }
        log("<-- Pair success! Session Token: $token")
    }

    private suspend fun register(client: Mai70Client) {
        log("--> Registering client...")
        val success = withContext(Dispatchers.IO) { client.register() }
        log("<-- Register result: $success")
    }

    private suspend fun setAlbumMode(client: Mai70Client, enable: Boolean) {
        log("--> setAlbumMode(enable=$enable)...")
        val success = withContext(Dispatchers.IO) { client.setAlbumMode(enable) }
        log("<-- setAlbumMode result: $success")
    }

    private suspend fun getFileCounts(client: Mai70Client) {
        log("--> getFileCounts()...")
        val counts = withContext(Dispatchers.IO) { client.getFileCounts() }
        log("<-- Counts: $counts")
    }

    private suspend fun listFiles(client: Mai70Client) {
        log("--> listFiles(Category.NORMAL)...")
        val files = withContext(Dispatchers.IO) { client.listFiles(Category.NORMAL) }
        lastListedFiles.clear()
        lastListedFiles.addAll(files)
        log("<-- Listed ${files.size} normal files:")
        files.take(5).forEach { f ->
            log("    - ${f.name} (${f.size} bytes, path=${f.path})")
        }
        if (files.size > 5) {
            log("    ... and ${files.size - 5} more files")
        }
    }

    private suspend fun downloadSample(client: Mai70Client) {
        if (lastListedFiles.isEmpty()) {
            log("No listed files available. Run 'List Normal Files' first.")
            return
        }
        val targetFile = lastListedFiles.first()
        log("--> Downloading sample stream for ${targetFile.name}...")
        val bytesRead = withContext(Dispatchers.IO) {
            client.openFile(targetFile).use { input: InputStream ->
                val buffer = ByteArray(4096)
                input.read(buffer)
            }
        }
        log("<-- Successfully opened stream and read $bytesRead header bytes.")
    }

    private suspend fun getThumbnailSample(client: Mai70Client) {
        if (lastListedFiles.isEmpty()) {
            log("No listed files available. Run 'List Normal Files' first.")
            return
        }
        val targetFile = lastListedFiles.first()
        log("--> Fetching thumbnail stream for ${targetFile.name}...")
        val bytesRead = withContext(Dispatchers.IO) {
            client.getThumbnail(targetFile).use { input: InputStream ->
                val buffer = ByteArray(4096)
                input.read(buffer)
            }
        }
        log("<-- Successfully opened thumbnail stream and read $bytesRead header bytes.")
    }

    private suspend fun deleteLast(client: Mai70Client) {
        if (lastListedFiles.isEmpty()) {
            log("No listed files available. Run 'List Normal Files' first.")
            return
        }
        val targetFile = lastListedFiles.last()
        log("--> Deleting file ${targetFile.name}...")
        withContext(Dispatchers.IO) { client.delete(targetFile) }
        log("<-- Delete command sent successfully for ${targetFile.name}.")
    }
}

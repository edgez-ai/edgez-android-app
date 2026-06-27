package ai.edgez.edgez

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.edgez.edgez.ui.theme.EdgeZTheme
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_ECHO_RESP
import ai.edgez.edgez.usb.EDGEZ_TYPE_ERROR
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Composable
fun HomeScreen(client: EdgezUsbClient) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var echoPayload by rememberSaveable { mutableStateOf("hello esp32s3") }
    var status by remember { mutableStateOf("Connect USB in Settings, then send an echo.") }
    var response by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }
    val activity = context as? ComponentActivity

    fun appendLog(line: String) {
        log = (listOf(line) + log).take(16)
    }

    fun handleFrame(frame: ByteArray) {
        if (frame.size < EDGEZ_HEADER_LEN ||
            frame[0] != EDGEZ_MAGIC_0 ||
            frame[1] != EDGEZ_MAGIC_1 ||
            frame[2] != EDGEZ_VERSION) {
            return
        }

        val responseType = frame[3].toInt() and 0xff
        val responseSeq = ByteBuffer.wrap(frame, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val responseLen = ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        if (responseLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + responseLen > frame.size) {
            return
        }

        val text = String(frame, EDGEZ_HEADER_LEN, responseLen, StandardCharsets.UTF_8)
        activity?.runOnUiThread {
            when (responseType) {
                EDGEZ_TYPE_ECHO_RESP -> {
                    response = text
                    status = "RX seq=$responseSeq: $text"
                }
                EDGEZ_TYPE_ERROR -> {
                    status = "Device error on seq=$responseSeq: $text"
                }
                else -> {
                    status = "Packet on seq=$responseSeq: type=$responseType"
                }
            }
            appendLog(status)
        }
    }

    DisposableEffect(Unit) {
        client.setFrameListener(::handleFrame)
        onDispose {
            client.setFrameListener(null)
            executor.shutdownNow()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Home", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = echoPayload,
                onValueChange = { echoPayload = it.take(EDGEZ_MAX_PAYLOAD) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Echo payload") },
                singleLine = true,
            )

            Button(onClick = {
                status = "Sending echo..."
                appendLog(status)
                executor.execute {
                    val result = client.sendEcho(echoPayload)
                    activity?.runOnUiThread {
                        result.fold(
                            onSuccess = {
                                status = it
                                appendLog(status)
                            },
                            onFailure = {
                                status = it.message ?: "Echo failed"
                                appendLog(status)
                            },
                        )
                    }
                }
            }) {
                Text("Send Echo")
            }

            ResponseCard(response)
            Spacer(Modifier.height(4.dp))
            LogCard(log)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    EdgeZTheme {
        HomeScreen(EdgezUsbClient(LocalContext.current.applicationContext))
    }
}

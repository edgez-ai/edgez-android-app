package ai.edgez.edgez.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArraySet

const val ACTION_USB_PERMISSION = "ai.edgez.edgez.USB_PERMISSION"
const val EDGEZ_MAGIC_0 = 'E'.code.toByte()
const val EDGEZ_MAGIC_1 = 'Z'.code.toByte()
const val EDGEZ_VERSION = 1.toByte()
const val EDGEZ_TYPE_ECHO_RESP = 2
const val EDGEZ_TYPE_CONTROL_RESP = 4
const val EDGEZ_TYPE_ERROR = 0x7f
const val EDGEZ_HEADER_LEN = 8
const val EDGEZ_MAX_PAYLOAD = 256

const val USB_CONTROL_ACTION_SET_BLE_ENABLED = 1
const val USB_CONTROL_ACTION_SET_PAIRING_ENABLED = 2
const val USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS = 3
const val USB_CONTROL_ACTION_GET_STATUS = 4
const val USB_CONTROL_ACTION_ECHO = 5

private const val ESPRESSIF_VID = 0x303A
private const val EDGEZ_TYPE_CONTROL_REQ = 3.toByte()
private const val EDGEZ_MAX_FRAME = EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD
private const val USB_CONTROL_STATUS_OK = 1
private const val TAG = "EdgezUsbClient"

data class UsbCandidate(
    val driver: UsbSerialDriver,
    val port: UsbSerialPort,
    val portIndex: Int,
) {
    val device: UsbDevice get() = driver.device

    val label: String
        get() = "USB serial VID=%04x PID=%04x ${device.productName ?: "USB device"} ${driver.javaClass.simpleName} port=$portIndex"
            .format(device.vendorId, device.productId)
}

data class UsbControlResponse(
    val action: Int = 0,
    val status: Int = 0,
    val espErr: Int = 0,
    val message: String = "",
    val bleEnabled: Boolean = false,
    val pairingEnabled: Boolean = false,
    val echoPayload: String = "",
) {
    val ok: Boolean get() = status == USB_CONTROL_STATUS_OK && espErr == 0
}

fun UsbEndpoint.describe(): String {
    val directionName = if (direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
    return "%s ep=0x%02x max=%d".format(directionName, address, maxPacketSize)
}

private fun UsbInterface.describeClass(): String {
    return "cls=$interfaceClass sub=$interfaceSubclass proto=$interfaceProtocol"
}

object EdgezUsbControlProto {
    fun encodeRequest(
        action: Int,
        bleEnabled: Boolean = false,
        pairingEnabled: Boolean = false,
        wifiSsid: String = "",
        wifiPassphrase: String = "",
        connectAfterSet: Boolean = false,
        echoPayload: String = "",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, action.toLong())
        when (action) {
            USB_CONTROL_ACTION_SET_BLE_ENABLED -> writeVarintField(out, 2, if (bleEnabled) 1 else 0)
            USB_CONTROL_ACTION_SET_PAIRING_ENABLED -> writeVarintField(out, 3, if (pairingEnabled) 1 else 0)
            USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS -> {
                writeStringField(out, 4, wifiSsid.take(32))
                writeStringField(out, 5, wifiPassphrase.take(64))
                writeVarintField(out, 6, if (connectAfterSet) 1 else 0)
            }
            USB_CONTROL_ACTION_ECHO -> writeStringField(out, 7, echoPayload.take(128))
        }
        return out.toByteArray()
    }

    fun decodeResponse(payload: ByteArray): UsbControlResponse? {
        var offset = 0
        var action = 0
        var status = 0
        var espErr = 0
        var message = ""
        var bleEnabled = false
        var pairingEnabled = false
        var echoPayload = ""

        while (offset < payload.size) {
            val tagRead = readVarint(payload, offset) ?: return null
            offset = tagRead.nextOffset
            val field = (tagRead.value ushr 3).toInt()
            val wireType = (tagRead.value and 0x07).toInt()

            when (wireType) {
                0 -> {
                    val valueRead = readVarint(payload, offset) ?: return null
                    offset = valueRead.nextOffset
                    when (field) {
                        1 -> action = valueRead.value.toInt()
                        2 -> status = valueRead.value.toInt()
                        3 -> espErr = valueRead.value.toInt()
                        5 -> bleEnabled = valueRead.value != 0L
                        6 -> pairingEnabled = valueRead.value != 0L
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val text = String(payload, offset, len, StandardCharsets.UTF_8)
                    when (field) {
                        4 -> message = text
                        7 -> echoPayload = text
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return UsbControlResponse(
            action = action,
            status = status,
            espErr = espErr,
            message = message,
            bleEnabled = bleEnabled,
            pairingEnabled = pairingEnabled,
            echoPayload = echoPayload,
        )
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, rawValue: Long) {
        var value = rawValue
        while ((value and 0x7f.inv().toLong()) != 0L) {
            out.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }

    private fun readVarint(data: ByteArray, start: Int): VarintRead? {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < data.size && shift < 64) {
            val b = data[offset].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            offset++
            if ((b and 0x80) == 0) {
                return VarintRead(result, offset)
            }
            shift += 7
        }
        return null
    }

    private data class VarintRead(val value: Long, val nextOffset: Int)
}

class EdgezUsbClient(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private val frameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    private val debugListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 4)
    private var rxLen = 0
    @Volatile
    private var rxTaskRunning = false
    private var rxTask: Thread? = null
    private var seq = 0

    fun scan(): List<UsbCandidate> {
        val devices = usbManager.deviceList.values.toList()
        emitDebug("SCAN devices=${devices.size}")
        return devices.flatMap { device ->
            emitDebug("SCAN device VID=%04x PID=%04x name=${device.productName ?: "USB device"} ifaces=${device.interfaceCount}".format(device.vendorId, device.productId))
            logInterfaces(device)
            findSerialCandidates(device).also { candidates ->
                if (candidates.isEmpty()) {
                    emitDebug("SCAN unsupported serial device VID=%04x PID=%04x".format(device.vendorId, device.productId))
                }
                candidates.forEach { emitDebug("SCAN candidate ${it.label}") }
            }
        }.sortedWith(compareBy({ if (it.device.vendorId == ESPRESSIF_VID) 0 else 1 }, { it.label }))
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun isConnected(): Boolean = serialPort?.isOpen == true

    fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, intent)
    }

    fun connect(candidate: UsbCandidate): String {
        close()
        if (!usbManager.hasPermission(candidate.device)) {
            return "Permission required"
        }

        val opened = usbManager.openDevice(candidate.device) ?: return "Open failed"
        try {
            candidate.port.open(opened)
            candidate.port.setParameters(
                115200,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            candidate.port.setDTR(true)
            candidate.port.setRTS(true)
            candidate.port.purgeHwBuffers(true, true)
        } catch (e: IOException) {
            try {
                candidate.port.close()
            } catch (_: IOException) {
            }
            opened.close()
            return "Serial open failed: ${e.message}"
        }

        connection = opened
        serialPort = candidate.port
        emitDebug("CONNECT ${candidate.label}")
        startRxTask(candidate.port)
        return "Connected to ${candidate.label}"
    }

    fun close() {
        stopRxTask()
        try {
            serialPort?.close()
        } catch (_: IOException) {
        }
        connection?.close()
        connection = null
        serialPort = null
        emitDebug("CLOSE")
    }

    fun addFrameListener(listener: (ByteArray) -> Unit): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
    }

    fun addDebugListener(listener: (String) -> Unit): () -> Unit {
        debugListeners.add(listener)
        return { debugListeners.remove(listener) }
    }

    fun setFrameListener(listener: ((ByteArray) -> Unit)?) {
        frameListeners.clear()
        if (listener != null) {
            frameListeners.add(listener)
        }
    }

    fun sendControl(
        action: Int,
        bleEnabled: Boolean = false,
        pairingEnabled: Boolean = false,
        wifiSsid: String = "",
        wifiPassphrase: String = "",
        connectAfterSet: Boolean = false,
        echoPayload: String = "",
        timeoutMs: Int = 1500,
    ): Result<String> {
        val payload = EdgezUsbControlProto.encodeRequest(
            action = action,
            bleEnabled = bleEnabled,
            pairingEnabled = pairingEnabled,
            wifiSsid = wifiSsid,
            wifiPassphrase = wifiPassphrase,
            connectAfterSet = connectAfterSet,
            echoPayload = echoPayload,
        )
        return sendFrame(EDGEZ_TYPE_CONTROL_REQ, payload, timeoutMs)
    }

    fun sendEcho(message: String, timeoutMs: Int = 1500): Result<String> {
        return sendControl(
            action = USB_CONTROL_ACTION_ECHO,
            echoPayload = message.take(128),
            timeoutMs = timeoutMs,
        )
    }

    private fun startRxTask(port: UsbSerialPort) {
        stopRxTask()
        rxLen = 0
        rxTaskRunning = true
        emitDebug("RX task start ${port.getReadEndpoint().describe()}")
        rxTask = Thread {
            val scratch = ByteArray(EDGEZ_MAX_FRAME)
            while (rxTaskRunning) {
                val read = try {
                    port.read(scratch, 100)
                } catch (e: IOException) {
                    emitDebug("RX read failed: ${e.message}")
                    break
                }
                if (!rxTaskRunning) {
                    break
                }
                if (read <= 0) {
                    continue
                }

                if (rxLen + read > rxBuffer.size) {
                    emitDebug("RX overflow buffered=$rxLen read=$read; reset")
                    rxLen = 0
                }
                System.arraycopy(scratch, 0, rxBuffer, rxLen, read)
                rxLen += read
                emitDebug("RX chunk read=$read buffered=$rxLen")

                while (rxLen >= EDGEZ_HEADER_LEN) {
                    val magicOffset = findMagicOffset(rxBuffer, rxLen)
                    if (magicOffset < 0) {
                        emitDebug("RX no magic buffered=$rxLen; drop")
                        rxLen = 0
                        break
                    }
                    if (magicOffset > 0) {
                        emitDebug("RX resync skip=$magicOffset buffered=$rxLen")
                        System.arraycopy(rxBuffer, magicOffset, rxBuffer, 0, rxLen - magicOffset)
                        rxLen -= magicOffset
                    }

                    if (rxLen < EDGEZ_HEADER_LEN) {
                        break
                    }
                    if (rxBuffer[2] != EDGEZ_VERSION) {
                        emitDebug("RX bad version=${rxBuffer[2].toInt() and 0xff}; resync")
                        System.arraycopy(rxBuffer, 1, rxBuffer, 0, rxLen - 1)
                        rxLen -= 1
                        continue
                    }

                    val type = rxBuffer[3].toInt() and 0xff
                    if (!isKnownRxFrameType(type)) {
                        emitDebug("RX bad type=$type; resync")
                        System.arraycopy(rxBuffer, 1, rxBuffer, 0, rxLen - 1)
                        rxLen -= 1
                        continue
                    }

                    val payloadLen = readLe16(rxBuffer, 6)
                    if (payloadLen > EDGEZ_MAX_PAYLOAD) {
                        emitDebug("RX bad len=$payloadLen; resync")
                        System.arraycopy(rxBuffer, 1, rxBuffer, 0, rxLen - 1)
                        rxLen -= 1
                        continue
                    }
                    val frameLen = EDGEZ_HEADER_LEN + payloadLen
                    if (rxLen < frameLen) {
                        emitDebug("RX partial frame need=$frameLen buffered=$rxLen")
                        break
                    }

                    val frame = Arrays.copyOf(rxBuffer, frameLen)
                    val seq = readLe16(frame, 4)
                    emitDebug("RX frame type=$type seq=$seq len=$payloadLen")
                    dispatchFrame(frame)
                    if (rxLen == frameLen) {
                        rxLen = 0
                    } else {
                        System.arraycopy(rxBuffer, frameLen, rxBuffer, 0, rxLen - frameLen)
                        rxLen -= frameLen
                    }
                }
            }
            emitDebug("RX task stop")
        }.also { thread ->
            thread.name = "edgez-usb-rx"
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun stopRxTask() {
        rxTaskRunning = false
        val thread = rxTask
        if (thread != null && thread.isAlive) {
            thread.interrupt()
        }
        rxTask = null
    }

    private fun sendFrame(type: Byte, payload: ByteArray, timeoutMs: Int): Result<String> {
        val port = serialPort ?: return Result.failure(IllegalStateException("USB is not connected"))
        if (payload.size > EDGEZ_MAX_PAYLOAD) {
            return Result.failure(IllegalArgumentException("Payload too large: ${payload.size}/$EDGEZ_MAX_PAYLOAD"))
        }

        val currentSeq = (seq++ and 0xffff)
        val tx = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        tx.put(EDGEZ_MAGIC_0)
        tx.put(EDGEZ_MAGIC_1)
        tx.put(EDGEZ_VERSION)
        tx.put(type)
        tx.putShort(currentSeq.toShort())
        tx.putShort(payload.size.toShort())
        tx.put(payload)

        val txBytes = tx.array()
        emitDebug("TX frame type=${type.toInt() and 0xff} seq=$currentSeq len=${payload.size} ep=${port.getWriteEndpoint().describe()}")
        try {
            port.write(txBytes, timeoutMs)
        } catch (e: IOException) {
            emitDebug("TX failed seq=$currentSeq: ${e.message}")
            return Result.failure(IllegalStateException("USB write failed on ${port.getWriteEndpoint().describe()}: ${e.message}", e))
        }
        emitDebug("TX result seq=$currentSeq written=${txBytes.size}/${txBytes.size}")
        return Result.success("Sent seq=$currentSeq")
    }

    private fun dispatchFrame(frame: ByteArray) {
        emitDebug("DISPATCH listeners=${frameListeners.size} bytes=${frame.size}")
        frameListeners.forEach { listener ->
            listener(frame)
        }
    }

    private fun emitDebug(line: String) {
        Log.d(TAG, line)
        debugListeners.forEach { listener ->
            listener(line)
        }
    }

    private fun findSerialCandidates(device: UsbDevice): List<UsbCandidate> {
        if (!hasCdcAcmInterface(device)) {
            emitDebug("SCAN skip non-CDC control device VID=%04x PID=%04x".format(device.vendorId, device.productId))
            return emptyList()
        }

        val drivers = findSerialDrivers(device)
        return drivers.flatMap { driver ->
            driver.ports.mapIndexed { index, port ->
                UsbCandidate(driver = driver, port = port, portIndex = index)
            }
        }
    }

    private fun findSerialDrivers(device: UsbDevice): List<UsbSerialDriver> {
        val drivers = mutableListOf<UsbSerialDriver>()
        UsbSerialProber.getDefaultProber().probeDevice(device)?.let { drivers += it }
        if (drivers.isEmpty() && CdcAcmSerialDriver.probe(device)) {
            drivers += CdcAcmSerialDriver(device)
        }
        return drivers
    }

    private fun hasCdcAcmInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM &&
                intf.interfaceSubclass == 2 &&
                intf.interfaceProtocol == 1) {
                return true
            }
        }
        return false
    }

    private fun logInterfaces(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val endpoints = (0 until intf.endpointCount).joinToString(" ") { e ->
                intf.getEndpoint(e).describe()
            }
            emitDebug("SCAN if=${intf.id} ${intf.describeClass()} eps=${intf.endpointCount} $endpoints")
        }
    }

    private fun findMagicOffset(data: ByteArray, length: Int): Int {
        var i = 0
        while (i + 2 <= length) {
            if (data[i] == EDGEZ_MAGIC_0 && data[i + 1] == EDGEZ_MAGIC_1) {
                return i
            }
            i++
        }
        return -1
    }

    private fun readLe16(data: ByteArray, start: Int): Int {
        return (data[start].toInt() and 0xff) or ((data[start + 1].toInt() and 0xff) shl 8)
    }

    private fun isKnownRxFrameType(type: Int): Boolean {
        return type == EDGEZ_TYPE_ECHO_RESP || type == EDGEZ_TYPE_CONTROL_RESP || type == EDGEZ_TYPE_ERROR
    }
}

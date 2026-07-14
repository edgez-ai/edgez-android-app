package ai.edgez.edgez.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import ai.edgez.edgez.NodeMapMarker
import ai.edgez.edgez.DeviceSensorScriptConfig
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.ConversationMessage
import ai.edgez.edgez.usb.DeviceSettings
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.PacketMime
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

private const val EDGEZ_MAX_FRAME = EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD
private const val EDGEZ_BLE_REQUESTED_MTU = 517
private val EDGEZ_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val EDGEZ_RX_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val EDGEZ_TX_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
private val EDGEZ_FORWARD_RX_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
private val EDGEZ_FORWARD_TX_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
private val EDGEZ_OTA_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
private val EDGEZ_OTA_STATUS_UUID: UUID = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb")
private val EDGEZ_VOICE_RX_UUID: UUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")
private val EDGEZ_VOICE_TX_UUID: UUID = UUID.fromString("0000fff8-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val EDGEZ_VOICE_PROTOCOL_MAGIC = byteArrayOf('V'.code.toByte(), 'C'.code.toByte(), 1)

data class BleCandidate(
    val device: BluetoothDevice,
    val name: String?,
) {
    val label: String
        @SuppressLint("MissingPermission")
        get() = "${name ?: device.name ?: "BLE device"} ${device.address}"
}

class EdgezBleClient(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val frameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    private val forwardFrameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    private val debugListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 2)
    private var rxLen = 0
    private val forwardRxBuffer = ByteArray(EDGEZ_MAX_FRAME * 2)
    private var forwardRxLen = 0
    private var scanCallback: ScanCallback? = null
    private var pendingBondCandidate: BleCandidate? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var forwardRxCharacteristic: BluetoothGattCharacteristic? = null
    private var forwardTxCharacteristic: BluetoothGattCharacteristic? = null
    private var otaCharacteristic: BluetoothGattCharacteristic? = null
    private var otaStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var voiceRxCharacteristic: BluetoothGattCharacteristic? = null
    private var voiceTxCharacteristic: BluetoothGattCharacteristic? = null
    private val notificationDescriptors = ArrayDeque<BluetoothGattDescriptor>()
    private var notificationDescriptorWriteInFlight = false
    private val otaWriteLock = Object()
    private var otaWriteStatus: Int? = null
    private var negotiatedMtu = 23
    private val txQueue = ArrayDeque<ByteArray>()
    private val forwardTxQueue = ArrayDeque<ByteArray>()
    private var txWriteInFlight = false
    private var forwardTxWriteInFlight = false
    private var forwardEnabled = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val candidate = pendingBondCandidate ?: return
            if (device.address != candidate.device.address) return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondCandidate = null
                    emitDebug("BOND complete ${candidate.label}")
                    connectGatt(candidate)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondCandidate = null
                    emitDebug("BOND failed or canceled ${candidate.label}")
                }
                BluetoothDevice.BOND_BONDING -> emitDebug("BOND awaiting PIN ${candidate.label}")
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondStateReceiver, filter)
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isReady(): Boolean = gatt != null && rxCharacteristic != null

    fun isOtaReady(): Boolean = gatt != null && otaCharacteristic != null

    /** Streams an app-only ESP image through the dedicated OTA characteristic. */
    fun performOta(
        image: InputStream,
        totalSize: Int,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit = { _, _ -> },
    ): Result<String> {
        if (totalSize <= 0) return Result.failure(IllegalArgumentException("OTA image size is invalid"))
        if (!isOtaReady()) return Result.failure(IllegalStateException("BLE OTA service is not ready"))

        return runCatching {
            writeOtaPacket(otaPacket(OTA_BEGIN, totalSize))
            // The ESP32 NimBLE transport uses 255-byte ACL buffers. Keep each encrypted
            // ATT write within one buffer rather than relying on long-write reassembly.
            val chunkSize = (negotiatedMtu - 3 - OTA_DATA_HEADER_SIZE).coerceIn(20, OTA_DATA_MAX_CHUNK_SIZE)
            val buffer = ByteArray(chunkSize)
            var sent = 0
            while (sent < totalSize) {
                val read = image.read(buffer, 0, minOf(buffer.size, totalSize - sent))
                if (read < 0) throw IllegalStateException("OTA image ended at $sent of $totalSize bytes")
                if (read == 0) continue
                writeOtaPacket(otaDataPacket(sent, buffer, read))
                sent += read
                onProgress(sent, totalSize)
            }
            writeOtaPacket(byteArrayOf(OTA_END))
            "Firmware uploaded; the device is restarting"
        }.onFailure {
            runCatching { writeOtaPacket(byteArrayOf(OTA_ABORT)) }
        }
    }

    private fun otaPacket(command: Byte, value: Int): ByteArray = ByteBuffer.allocate(OTA_DATA_HEADER_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(command)
        .putInt(value)
        .array()

    private fun otaDataPacket(offset: Int, bytes: ByteArray, length: Int): ByteArray = ByteBuffer.allocate(OTA_DATA_HEADER_SIZE + length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(OTA_DATA)
        .putInt(offset)
        .put(bytes, 0, length)
        .array()

    @SuppressLint("MissingPermission")
    private fun writeOtaPacket(packet: ByteArray) {
        val activeGatt = gatt ?: throw IllegalStateException("BLE is not connected")
        val characteristic = otaCharacteristic ?: throw IllegalStateException("BLE OTA service is not ready")
        synchronized(otaWriteLock) {
            otaWriteStatus = null
            val started = if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                activeGatt.writeCharacteristic(characteristic)
            }
            if (!started) throw IllegalStateException("BLE OTA write could not start")
            val deadline = System.currentTimeMillis() + OTA_WRITE_TIMEOUT_MS
            while (otaWriteStatus == null && System.currentTimeMillis() < deadline) {
                otaWriteLock.wait((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            }
            val status = otaWriteStatus ?: throw IllegalStateException("BLE OTA write timed out")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw IllegalStateException("BLE OTA write failed: $status")
            }
        }
    }

    fun setForwardingEnabled(enabled: Boolean) {
        synchronized(this) {
            forwardEnabled = enabled
            if (!enabled) {
                clearForwardState(log = true)
            }
        }
        val currentGatt = gatt
        if (enabled && currentGatt != null) {
            currentGatt.discoverServices()
        }
    }

    fun addFrameListener(listener: (ByteArray) -> Unit): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
    }

    fun addForwardFrameListener(listener: (ByteArray) -> Unit): () -> Unit {
        forwardFrameListeners.add(listener)
        return { forwardFrameListeners.remove(listener) }
    }

    fun addDebugListener(listener: (String) -> Unit): () -> Unit {
        debugListeners.add(listener)
        return { debugListeners.remove(listener) }
    }

    @SuppressLint("MissingPermission")
    fun startScan(onCandidate: (BleCandidate) -> Unit): Result<String> {
        if (!hasPermissions()) {
            return Result.failure(IllegalStateException("BLE permission required"))
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: return Result.failure(IllegalStateException("BLE scanner unavailable"))
        stopScan()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                emitDebug("SCAN ${name ?: "BLE device"} ${result.device.address} rssi=${result.rssi}")
                onCandidate(BleCandidate(result.device, name))
            }

            override fun onScanFailed(errorCode: Int) {
                emitDebug("SCAN failed=$errorCode")
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(EDGEZ_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        emitDebug("SCAN start service=$EDGEZ_SERVICE_UUID")
        return Result.success("BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        if (hasPermissions()) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scanCallback = null
        emitDebug("SCAN stop")
    }

    @SuppressLint("MissingPermission")
    fun connect(candidate: BleCandidate): Result<String> {
        if (!hasPermissions()) {
            return Result.failure(IllegalStateException("BLE permission required"))
        }

        close()
        if (candidate.device.bondState != BluetoothDevice.BOND_BONDED) {
            pendingBondCandidate = candidate
            emitDebug("BOND start ${candidate.label}")
            if (!candidate.device.createBond()) {
                pendingBondCandidate = null
                return Result.failure(IllegalStateException("BLE pairing could not start"))
            }
            return Result.success("Enter the device PIN in the Android pairing prompt")
        }

        connectGatt(candidate)
        return Result.success("Connecting to ${candidate.label}")
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(candidate: BleCandidate) {
        emitDebug("CONNECT ${candidate.label}")
        gatt = candidate.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        rxCharacteristic = null
        txCharacteristic = null
        setForwardingEnabled(false)
        clearTxQueue()
        gatt?.close()
        gatt = null
        rxLen = 0
        forwardRxLen = 0
        emitDebug("CLOSE")
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
        if (action != ai.edgez.edgez.usb.USB_CONTROL_ACTION_GET_STATUS) {
            return Result.failure(UnsupportedOperationException("Legacy control action is no longer supported"))
        }
        return sendFrame(EdgezUsbControlProto.encodeStatusRequest())
    }

    fun sendEcho(message: String): Result<String> {
        return Result.failure(UnsupportedOperationException("Legacy echo is no longer supported"))
    }

    fun sendHaLowInit(
        countryCode: String,
        meshId: String,
        passphrase: String,
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        maxHop: Int,
        marker: String,
        latitude: Double?,
        longitude: Double?,
        meshBandwidthMHz: Int,
        meshFrequencyKHz: Int,
    ): Result<String> {
        return sendFrame(
            EdgezUsbControlProto.encodeHaLowInit(countryCode, meshId, passphrase, userIdHigh, userIdLow, userName, userPublicKey, maxHop, marker, latitude, longitude, meshBandwidthMHz, meshFrequencyKHz),
        )
    }

    fun sendHaLowBeacon(
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        meshPassphrase: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        locationTimestampMs: Long = 0,
        marker: String = NodeMapMarker.DEFAULT.id,
    ): Result<String> {
        return sendFrame(
            EdgezUsbControlProto.encodeHaLowBeacon(
                userIdHigh,
                userIdLow,
                userName,
                userPublicKey,
                meshPassphrase,
                latitude,
                longitude,
                locationTimestampMs,
                marker,
            ),
        )
    }

    fun requestDeviceSettings(): Result<String> {
        return sendFrame(EdgezUsbControlProto.encodeDeviceSettingsRequest())
    }

    fun sendDeviceSettings(settings: DeviceSettings): Result<String> {
        val result = sendFrame(EdgezUsbControlProto.encodeDeviceSettingsSet(settings))
        if (result.isFailure) return result
        return waitForControlTxDrain(3000)
    }

    fun sendDeviceSensorScript(config: DeviceSensorScriptConfig): Result<String> {
        val packets = EdgezUsbControlProto.encodeScriptConfigUpload(config)
        for (packet in packets) {
            val result = sendFrame(packet)
            if (result.isFailure) return result
        }
        return waitForControlTxDrain((packets.size * 220).coerceIn(2000, 20000))
    }

    fun sendConversationMessage(
        message: ConversationMessage,
        from: Long,
        to: Long,
        mime: PacketMime = PacketMime.TEXT,
        maxHop: Int = 0,
        sequence: Int = 1,
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        userIdHigh: Long,
        userIdLow: Long,
        groupIdHigh: Long = 0,
        groupIdLow: Long = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationMessage(
                message = message,
                from = from,
                to = to,
                mime = mime,
                maxHop = maxHop,
                sequence = sequence,
                messageIdHigh = messageIdHigh,
                messageIdLow = messageIdLow,
                userIdHigh = userIdHigh,
                userIdLow = userIdLow,
                groupIdHigh = groupIdHigh,
                groupIdLow = groupIdLow,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendFrame(packet)
    }

    /** Sends one realtime voice NetworkPacket on the dedicated FFF7 media characteristic. */
    fun sendVoiceCallMessage(
        message: ConversationMessage,
        from: Long,
        to: Long,
        maxHop: Int = 0,
        sequence: Int = 1,
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        userIdHigh: Long,
        userIdLow: Long,
        groupIdHigh: Long = 0,
        groupIdLow: Long = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationMessage(
                message, from, to, PacketMime.VOICE, maxHop, sequence, messageIdHigh, messageIdLow,
                userIdHigh, userIdLow, groupIdHigh, groupIdLow,
            )
        }.getOrElse { return Result.failure(it) }
        return sendVoicePacket(packet)
    }

    @SuppressLint("MissingPermission")
    private fun sendVoicePacket(packet: ByteArray): Result<String> {
        val activeGatt = gatt ?: return Result.failure(IllegalStateException("BLE is not connected"))
        val voice = voiceRxCharacteristic ?: return Result.failure(
            IllegalStateException("BLE voice characteristic FFF7 is unavailable; flash the FFF7/FFF8 firmware and reconnect"),
        )
        val frame = EDGEZ_VOICE_PROTOCOL_MAGIC + packet
        val maxVoiceFrame = minOf(negotiatedMtu - 3, EDGEZ_MAX_PAYLOAD)
        if (frame.size > maxVoiceFrame) {
            return Result.failure(IllegalArgumentException("Voice packet too large: ${frame.size}/$maxVoiceFrame"))
        }
        val sent = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(voice, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            voice.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            voice.value = frame
            activeGatt.writeCharacteristic(voice)
        }
        return if (sent) Result.success("BLE voice sent") else Result.failure(IllegalStateException("BLE voice write failed"))
    }

    fun sendConversationMessageForward(
        message: ConversationMessage,
        from: Long,
        to: Long,
        mime: PacketMime = PacketMime.TEXT,
        maxHop: Int = 0,
        sequence: Int = 1,
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        userIdHigh: Long,
        userIdLow: Long,
        groupIdHigh: Long = 0,
        groupIdLow: Long = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationMessage(
                message = message,
                from = from,
                to = to,
                mime = mime,
                maxHop = maxHop,
                sequence = sequence,
                messageIdHigh = messageIdHigh,
                messageIdLow = messageIdLow,
                userIdHigh = userIdHigh,
                userIdLow = userIdLow,
                groupIdHigh = groupIdHigh,
                groupIdLow = groupIdLow,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendForwardFrame(packet)
    }

    fun sendConversationAck(
        messageIdHigh: Long,
        messageIdLow: Long,
        from: Long,
        to: Long,
        userIdHigh: Long,
        userIdLow: Long,
        maxHop: Int = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationAck(
                messageIdHigh = messageIdHigh,
                messageIdLow = messageIdLow,
                from = from,
                to = to,
                userIdHigh = userIdHigh,
                userIdLow = userIdLow,
                maxHop = maxHop,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendFrame(packet)
    }

    fun sendConversationAckForward(
        messageIdHigh: Long,
        messageIdLow: Long,
        from: Long,
        to: Long,
        userIdHigh: Long,
        userIdLow: Long,
        maxHop: Int = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationAck(
                messageIdHigh = messageIdHigh,
                messageIdLow = messageIdLow,
                from = from,
                to = to,
                userIdHigh = userIdHigh,
                userIdLow = userIdLow,
                maxHop = maxHop,
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendForwardFrame(packet)
    }

    fun sendForwardPayload(payload: ByteArray): Result<String> {
        return sendForwardFrame(payload)
    }

    @SuppressLint("MissingPermission")
    private fun sendFrame(payload: ByteArray): Result<String> {
        val gatt = gatt ?: return Result.failure(IllegalStateException("BLE is not connected"))
        val rx = rxCharacteristic ?: return Result.failure(IllegalStateException("BLE control service is not ready"))
        if (payload.size > EDGEZ_MAX_PAYLOAD) {
            return Result.failure(IllegalArgumentException("Payload too large: ${payload.size}/$EDGEZ_MAX_PAYLOAD"))
        }

        val tx = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        tx.put(EDGEZ_MAGIC_0)
        tx.put(EDGEZ_MAGIC_1)
        tx.putShort(payload.size.toShort())
        tx.put(payload)

        val frame = tx.array()
        emitDebug("TX protobuf frame len=${payload.size} queue=${synchronized(this) { txQueue.size }}")
        synchronized(this) {
            txQueue.add(frame)
        }
        return if (writeNextFrame(gatt, rx, isForward = false)) {
            Result.success("BLE queued protobuf")
        } else {
            synchronized(this) {
                txQueue.remove(frame)
            }
            Result.failure(IllegalStateException("BLE write failed"))
        }
    }

    private fun waitForControlTxDrain(timeoutMs: Int): Result<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(this) {
                if (!txWriteInFlight && txQueue.isEmpty()) {
                    return Result.success("BLE control TX complete")
                }
            }
            Thread.sleep(10)
        }
        return Result.failure(IllegalStateException("BLE control TX not complete after ${timeoutMs}ms"))
    }

    @SuppressLint("MissingPermission")
    private fun sendForwardFrame(payload: ByteArray): Result<String> {
        if (!forwardEnabled) {
            return Result.failure(IllegalStateException("BLE forward is disabled"))
        }
        val gatt = gatt ?: return Result.failure(IllegalStateException("BLE is not connected"))
        val rx = forwardRxCharacteristic ?: return Result.failure(IllegalStateException("BLE forward service is not ready"))
        if (payload.size > EDGEZ_MAX_PAYLOAD) {
            return Result.failure(IllegalArgumentException("Payload too large: ${payload.size}/$EDGEZ_MAX_PAYLOAD"))
        }

        val tx = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        tx.put(EDGEZ_MAGIC_0)
        tx.put(EDGEZ_MAGIC_1)
        tx.putShort(payload.size.toShort())
        tx.put(payload)

        val frame = tx.array()
        emitDebug("TX forward frame len=${payload.size} queue=${synchronized(this) { forwardTxQueue.size }}")
        synchronized(this) {
            forwardTxQueue.add(frame)
        }
        return if (writeNextFrame(gatt, rx, isForward = true)) {
            Result.success("BLE forward queued protobuf")
        } else {
            synchronized(this) {
                forwardTxQueue.remove(frame)
            }
            Result.failure(IllegalStateException("BLE forward write failed"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextFrame(
        activeGatt: BluetoothGatt?,
        writeCharacteristic: BluetoothGattCharacteristic?,
        isForward: Boolean,
    ): Boolean {
        val gatt = activeGatt ?: return false
        if (isForward && !forwardEnabled) return false
        val rx = writeCharacteristic ?: return false
        val frame = synchronized(this) {
            if (isForward) {
                if (forwardTxWriteInFlight) return true
                val nextFrame = forwardTxQueue.peekFirst() ?: return true
                forwardTxWriteInFlight = true
                nextFrame
            } else {
                if (txWriteInFlight) return true
                val nextFrame = txQueue.peekFirst() ?: return true
                txWriteInFlight = true
                nextFrame
            }
        }

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = frame
            gatt.writeCharacteristic(rx)
        }
        if (ok) {
            emitDebug("TX start frame=${frame.size} queued=${synchronized(this) {
                if (isForward) forwardTxQueue.size else txQueue.size
            }}")
        } else {
            synchronized(this) {
                if (isForward) {
                    forwardTxWriteInFlight = false
                } else {
                    txWriteInFlight = false
                }
            }
        }
        return ok
    }

    @Synchronized
    private fun clearTxQueue() {
        txQueue.clear()
        forwardTxQueue.clear()
        txWriteInFlight = false
        forwardTxWriteInFlight = false
    }

    private fun clearForwardState(log: Boolean) {
        val hadState = forwardRxCharacteristic != null || forwardTxCharacteristic != null
        forwardRxCharacteristic = null
        forwardTxCharacteristic = null
        forwardRxLen = 0
        forwardTxQueue.clear()
        forwardTxWriteInFlight = false
        if (log && hadState) {
            emitDebug("BLE forward disabled; forward state cleared")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextFrame(activeGatt: BluetoothGatt?): Boolean {
        val gatt = activeGatt ?: return false
        val controlRx = rxCharacteristic
        if (controlRx != null && synchronized(this) { !txWriteInFlight && txQueue.isNotEmpty() }) {
            return writeNextFrame(gatt, controlRx, isForward = false)
        }
        if (!forwardEnabled) return false
        val forwardRx = forwardRxCharacteristic
        if (forwardRx != null && synchronized(this) { !forwardTxWriteInFlight && forwardTxQueue.isNotEmpty() }) {
            return writeNextFrame(gatt, forwardRx, isForward = true)
        }
        return false
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            emitDebug("CONN status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val highPriorityRequested = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                emitDebug("CONN high priority requested=$highPriorityRequested")
                gatt.requestMtu(EDGEZ_BLE_REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxCharacteristic = null
                txCharacteristic = null
                otaCharacteristic = null
                otaStatusCharacteristic = null
                voiceRxCharacteristic = null
                voiceTxCharacteristic = null
                notificationDescriptors.clear()
                notificationDescriptorWriteInFlight = false
                negotiatedMtu = 23
                setForwardingEnabled(false)
                rxLen = 0
                forwardRxLen = 0
                clearTxQueue()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            emitDebug("MTU mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            emitDebug("SERVICES status=$status")
            val service: BluetoothGattService? = gatt.getService(EDGEZ_SERVICE_UUID)
            Log.i(
                "EdgezBleClient",
                "EdgeZ service characteristics=" + service?.characteristics?.joinToString {
                    "${it.uuid} properties=0x${it.properties.toString(16)}"
                },
            )
            val rx = service?.getCharacteristic(EDGEZ_RX_UUID)
            val tx = service?.getCharacteristic(EDGEZ_TX_UUID)
            if (rx == null || tx == null) {
                emitDebug("SERVICE missing rx=${rx != null} tx=${tx != null}")
                return
            }

            rxCharacteristic = rx
            txCharacteristic = tx
            otaCharacteristic = service?.getCharacteristic(EDGEZ_OTA_UUID)
            otaStatusCharacteristic = service?.getCharacteristic(EDGEZ_OTA_STATUS_UUID)
            voiceRxCharacteristic = service?.getCharacteristic(EDGEZ_VOICE_RX_UUID)
            voiceTxCharacteristic = service?.getCharacteristic(EDGEZ_VOICE_TX_UUID)
            if (voiceRxCharacteristic == null || voiceTxCharacteristic == null) {
                val detail = "SERVICE voice missing rx=${voiceRxCharacteristic != null} tx=${voiceTxCharacteristic != null}; reconnect after clearing the Android GATT cache"
                emitDebug(detail)
                Log.w("EdgezBleClient", detail)
            }
            notificationDescriptors.clear()
            notificationDescriptorWriteInFlight = false
            queueNotification(gatt, tx)
            voiceTxCharacteristic?.let { queueNotification(gatt, it) }
            if (forwardEnabled) {
                val forwardRx = service?.getCharacteristic(EDGEZ_FORWARD_RX_UUID)
                val forwardTx = service?.getCharacteristic(EDGEZ_FORWARD_TX_UUID)
                forwardRxCharacteristic = forwardRx
                forwardTxCharacteristic = forwardTx
                if (forwardRx != null && forwardTx != null) {
                    queueNotification(gatt, forwardTx)
                } else {
                    emitDebug("SERVICE forward missing rx=${forwardRx != null} tx=${forwardTx != null}")
                    clearForwardState(log = false)
                }
            } else {
                clearForwardState(log = false)
                emitDebug("SERVICE forward disabled by settings")
            }
            otaStatusCharacteristic?.let { queueNotification(gatt, it) }
            writeNextNotificationDescriptor(gatt)
            emitDebug("SERVICE ready")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            notificationDescriptorWriteInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) emitDebug("CCCD write failed status=$status uuid=${descriptor.characteristic.uuid}")
            writeNextNotificationDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                txCharacteristic?.uuid -> handleBytes(value)
                voiceTxCharacteristic?.uuid -> handleVoiceBytes(value)
                if (forwardEnabled) forwardTxCharacteristic?.uuid else null -> handleForwardBytes(value)
                otaStatusCharacteristic?.uuid -> emitDebug("OTA status=${value.joinToString("") { "%02x".format(it) }}")
                else -> emitDebug("RX unknown char=${characteristic.uuid}")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                txCharacteristic?.uuid -> handleBytes(value)
                voiceTxCharacteristic?.uuid -> handleVoiceBytes(value)
                if (forwardEnabled) forwardTxCharacteristic?.uuid else null -> handleForwardBytes(value)
                otaStatusCharacteristic?.uuid -> emitDebug("OTA status=${value.joinToString("") { "%02x".format(it) }}")
                else -> emitDebug("RX unknown char=${characteristic.uuid}")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == otaCharacteristic?.uuid) {
                synchronized(otaWriteLock) {
                    otaWriteStatus = status
                    otaWriteLock.notifyAll()
                }
                return
            }
            val isForwardWrite = characteristic.uuid == forwardTxCharacteristic?.uuid && forwardEnabled
            synchronized(this@EdgezBleClient) {
                if (isForwardWrite) {
                    forwardTxWriteInFlight = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        forwardTxQueue.pollFirst()
                    } else {
                        forwardTxQueue.clear()
                    }
                } else {
                    txWriteInFlight = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        txQueue.pollFirst()
                    } else {
                        txQueue.clear()
                    }
                }
            }
            emitDebug("TX complete status=$status remaining=${synchronized(this@EdgezBleClient) {
                if (isForwardWrite) forwardTxQueue.size else txQueue.size
            }}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (isForwardWrite) {
                    writeNextFrame(gatt, forwardRxCharacteristic, isForward = true)
                } else {
                    writeNextFrame(gatt, rxCharacteristic, isForward = false)
                }
            }
        }
    }

    private companion object {
        const val OTA_BEGIN: Byte = 1
        const val OTA_DATA: Byte = 2
        const val OTA_END: Byte = 3
        const val OTA_ABORT: Byte = 4
        const val OTA_DATA_HEADER_SIZE = 5
        const val OTA_DATA_MAX_CHUNK_SIZE = 220
        const val OTA_WRITE_TIMEOUT_MS = 15_000L
    }

    private fun handleBytes(bytes: ByteArray) {
        if (rxLen + bytes.size > rxBuffer.size) {
            emitDebug("RX overflow buffered=$rxLen read=${bytes.size}; reset")
            rxLen = 0
        }

        System.arraycopy(bytes, 0, rxBuffer, rxLen, bytes.size)
        rxLen += bytes.size
        emitDebug("RX chunk read=${bytes.size} buffered=$rxLen")

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
            val payloadLen = readLe16(rxBuffer, 2)
            if (payloadLen > EDGEZ_MAX_PAYLOAD) {
                emitDebug("RX bad len=$payloadLen; resync")
                System.arraycopy(rxBuffer, 1, rxBuffer, 0, rxLen - 1)
                rxLen -= 1
                continue
            }
            val frameLen = EDGEZ_HEADER_LEN + payloadLen
            if (rxLen < frameLen) {
                break
            }

            val frame = rxBuffer.copyOf(frameLen)
            emitDebug("RX protobuf frame len=$payloadLen")
            frameListeners.forEach { it(frame) }
            if (rxLen == frameLen) {
                rxLen = 0
            } else {
                System.arraycopy(rxBuffer, frameLen, rxBuffer, 0, rxLen - frameLen)
                rxLen -= frameLen
            }
        }
    }

    private fun handleForwardBytes(bytes: ByteArray) {
        if (forwardRxLen + bytes.size > forwardRxBuffer.size) {
            emitDebug("RX forward overflow buffered=$forwardRxLen read=${bytes.size}; reset")
            forwardRxLen = 0
        }

        System.arraycopy(bytes, 0, forwardRxBuffer, forwardRxLen, bytes.size)
        forwardRxLen += bytes.size
        emitDebug("RX forward chunk read=${bytes.size} buffered=$forwardRxLen")

        while (forwardRxLen >= EDGEZ_HEADER_LEN) {
            val magicOffset = findMagicOffset(forwardRxBuffer, forwardRxLen)
            if (magicOffset < 0) {
                emitDebug("RX forward no magic buffered=$forwardRxLen; drop")
                forwardRxLen = 0
                break
            }
            if (magicOffset > 0) {
                emitDebug("RX forward resync skip=$magicOffset buffered=$forwardRxLen")
                System.arraycopy(forwardRxBuffer, magicOffset, forwardRxBuffer, 0, forwardRxLen - magicOffset)
                forwardRxLen -= magicOffset
            }

            if (forwardRxLen < EDGEZ_HEADER_LEN) {
                break
            }
            val payloadLen = readLe16(forwardRxBuffer, 2)
            if (payloadLen > EDGEZ_MAX_PAYLOAD) {
                emitDebug("RX forward bad len=$payloadLen; resync")
                System.arraycopy(forwardRxBuffer, 1, forwardRxBuffer, 0, forwardRxLen - 1)
                forwardRxLen -= 1
                continue
            }
            val frameLen = EDGEZ_HEADER_LEN + payloadLen
            if (forwardRxLen < frameLen) {
                break
            }

            val frame = forwardRxBuffer.copyOf(frameLen)
            emitDebug("RX forward protobuf frame len=$payloadLen")
            forwardFrameListeners.forEach { it(frame) }
            if (forwardRxLen == frameLen) {
                forwardRxLen = 0
            } else {
                System.arraycopy(forwardRxBuffer, frameLen, forwardRxBuffer, 0, forwardRxLen - frameLen)
                forwardRxLen -= frameLen
            }
        }
    }

    private fun emitDebug(line: String) {
        debugListeners.forEach { it(line) }
    }

    @SuppressLint("MissingPermission")
    private fun queueNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(CCCD_UUID)?.let { notificationDescriptors.addLast(it) }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor(gatt: BluetoothGatt) {
        if (notificationDescriptorWriteInFlight) return
        if (notificationDescriptors.isEmpty()) return
        val descriptor: BluetoothGattDescriptor = notificationDescriptors.removeFirst()
        notificationDescriptorWriteInFlight = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        if (!started) {
            notificationDescriptorWriteInFlight = false
            emitDebug("CCCD write start failed uuid=${descriptor.characteristic.uuid}")
            writeNextNotificationDescriptor(gatt)
        }
    }

    private fun handleVoiceBytes(bytes: ByteArray) {
        if (bytes.size <= EDGEZ_VOICE_PROTOCOL_MAGIC.size ||
            !bytes.copyOfRange(0, EDGEZ_VOICE_PROTOCOL_MAGIC.size).contentEquals(EDGEZ_VOICE_PROTOCOL_MAGIC)) {
            emitDebug("RX voice invalid frame len=${bytes.size}")
            return
        }
        val payload = bytes.copyOfRange(EDGEZ_VOICE_PROTOCOL_MAGIC.size, bytes.size)
        if (payload.size > EDGEZ_MAX_PAYLOAD) {
            emitDebug("RX voice too large len=${payload.size}")
            return
        }
        val frame = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(EDGEZ_MAGIC_0).put(EDGEZ_MAGIC_1).putShort(payload.size.toShort()).put(payload).array()
        frameListeners.forEach { it(frame) }
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

}

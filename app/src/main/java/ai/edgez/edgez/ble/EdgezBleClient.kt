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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.ConversationMessage
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.PacketMime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

private const val EDGEZ_MAX_FRAME = EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD
private val EDGEZ_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val EDGEZ_RX_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val EDGEZ_TX_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
    private val debugListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 2)
    private var rxLen = 0
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val txQueue = ArrayDeque<ByteArray>()
    private var txWriteInFlight = false

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

    fun addFrameListener(listener: (ByteArray) -> Unit): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
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
        emitDebug("CONNECT ${candidate.label}")
        gatt = candidate.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return Result.success("Connecting to ${candidate.label}")
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        rxCharacteristic = null
        clearTxQueue()
        gatt?.close()
        gatt = null
        rxLen = 0
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
    ): Result<String> {
        return sendFrame(
            EdgezUsbControlProto.encodeHaLowInit(countryCode, meshId, passphrase, userIdHigh, userIdLow, userName, userPublicKey, maxHop),
        )
    }

    fun sendHaLowBeacon(
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        latitude: Double? = null,
        longitude: Double? = null,
        locationTimestampMs: Long = 0,
    ): Result<String> {
        return sendFrame(
            EdgezUsbControlProto.encodeHaLowBeacon(
                userIdHigh,
                userIdLow,
                userName,
                userPublicKey,
                latitude,
                longitude,
                locationTimestampMs,
            ),
        )
    }

    fun sendConversationMessage(
        message: ConversationMessage,
        from: Long,
        to: Long,
        mime: PacketMime = PacketMime.TEXT,
        maxHop: Int = 0,
        sequence: Int = 0,
    ): Result<String> {
        val packet = runCatching {
            EdgezUsbControlProto.encodeConversationMessage(message, from, to, mime, maxHop, sequence)
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendFrame(packet)
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
        return if (writeNextFrame(gatt, rx)) {
            Result.success("BLE queued protobuf")
        } else {
            synchronized(this) {
                txQueue.remove(frame)
            }
            Result.failure(IllegalStateException("BLE write failed"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextFrame(
        activeGatt: BluetoothGatt? = gatt,
        writeCharacteristic: BluetoothGattCharacteristic? = rxCharacteristic,
    ): Boolean {
        val frame = synchronized(this) {
            if (txWriteInFlight) return true
            txQueue.peekFirst() ?: return true
        }
        val gatt = activeGatt ?: return false
        val rx = writeCharacteristic ?: return false

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = frame
            gatt.writeCharacteristic(rx)
        }
        if (ok) {
            synchronized(this) {
                txWriteInFlight = true
            }
            emitDebug("TX start frame=${frame.size} queued=${synchronized(this) { txQueue.size }}")
        }
        return ok
    }

    @Synchronized
    private fun clearTxQueue() {
        txQueue.clear()
        txWriteInFlight = false
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            emitDebug("CONN status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(256)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxCharacteristic = null
                rxLen = 0
                clearTxQueue()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            emitDebug("MTU mtu=$mtu status=$status")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            emitDebug("SERVICES status=$status")
            val service: BluetoothGattService? = gatt.getService(EDGEZ_SERVICE_UUID)
            val rx = service?.getCharacteristic(EDGEZ_RX_UUID)
            val tx = service?.getCharacteristic(EDGEZ_TX_UUID)
            if (rx == null || tx == null) {
                emitDebug("SERVICE missing rx=${rx != null} tx=${tx != null}")
                return
            }

            rxCharacteristic = rx
            gatt.setCharacteristicNotification(tx, true)
            val descriptor = tx.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
            emitDebug("SERVICE ready")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleBytes(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleBytes(characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(this@EdgezBleClient) {
                txWriteInFlight = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    txQueue.pollFirst()
                } else {
                    txQueue.clear()
                }
            }
            emitDebug("TX complete status=$status remaining=${synchronized(this@EdgezBleClient) { txQueue.size }}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeNextFrame(gatt, characteristic)
            }
        }
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

    private fun emitDebug(line: String) {
        debugListeners.forEach { it(line) }
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

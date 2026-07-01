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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

const val ACTION_USB_PERMISSION = "ai.edgez.edgez.USB_PERMISSION"
const val EDGEZ_MAGIC_0 = 'E'.code.toByte()
const val EDGEZ_MAGIC_1 = 'Z'.code.toByte()
const val EDGEZ_HEADER_LEN = 4
const val EDGEZ_MAX_PAYLOAD = 512
const val EDGEZ_NETWORK_PACKET_MAX_PAYLOAD = 350

const val USB_CONTROL_ACTION_SET_BLE_ENABLED = 1
const val USB_CONTROL_ACTION_SET_PAIRING_ENABLED = 2
const val USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS = 3
const val USB_CONTROL_ACTION_GET_STATUS = 4
private const val NETWORK_OPERATION_REQUEST = 1
private const val NETWORK_INTERFACE_HALOW = 5
private const val NETWORK_PACKET_PAYLOAD_TAG = 100
private const val NETWORK_PACKET_BEACON_TAG = 101
private const val NETWORK_PACKET_STATUS_TAG = 102
private const val NETWORK_PACKET_INIT_TAG = 103

private const val ESPRESSIF_VID = 0x303A
private const val EDGEZ_MAX_FRAME = EDGEZ_HEADER_LEN + EDGEZ_MAX_PAYLOAD
private const val USB_CONTROL_STATUS_OK = 1
private const val TAG = "EdgezUsbClient"
private const val USB_READ_TIMEOUT_MS = 200

data class UsbCandidate(
    val device: UsbDevice,
    val intf: UsbInterface,
    val readEndpoint: UsbEndpoint,
    val writeEndpoint: UsbEndpoint,
    val interfaceIndex: Int,
) {
    val label: String
        get() = "USB vendor VID=%04x PID=%04x ${device.productName ?: "USB device"} if=$interfaceIndex id=${intf.id} ${intf.describeClass()} ${writeEndpoint.describe()} ${readEndpoint.describe()}"
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
    val halowStatus: HaLowInterfaceStatus? = null,
) {
    val ok: Boolean get() = status == USB_CONTROL_STATUS_OK && espErr == 0
}

data class HaLowInterfaceStatus(
    val supported: Boolean = false,
    val stackInitialized: Boolean = false,
    val meshMode: Boolean = false,
    val linkUp: Boolean = false,
    val routeReady: Boolean = false,
    val readyForReport: Boolean = false,
    val ethertype: Int = 0,
    val meshId: String = "",
    val ipAddr: String = "",
    val gateway: String = "",
    val macAddress: Long = 0,
) {
    val isUsable: Boolean get() = supported && stackInitialized && linkUp && routeReady
}

data class EdgeZAssocMetadata(
    val userIdHigh: Long = 0,
    val userIdLow: Long = 0,
    val userName: String = "",
    val userPublicKey: ByteArray = ByteArray(0),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestampMs: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EdgeZAssocMetadata
        return userIdHigh == other.userIdHigh &&
            userIdLow == other.userIdLow &&
            userName == other.userName &&
            userPublicKey.contentEquals(other.userPublicKey) &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            locationTimestampMs == other.locationTimestampMs
    }

    override fun hashCode(): Int {
        var result = userIdHigh.hashCode()
        result = 31 * result + userIdLow.hashCode()
        result = 31 * result + userName.hashCode()
        result = 31 * result + userPublicKey.contentHashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + locationTimestampMs.hashCode()
        return result
    }
}

data class HaLowInitConfig(
    val countryCode: String = "",
    val meshId: String = "",
    val passphrase: String = "",
    val maxHop: Int = 0,
    val userIdHigh: Long = 0,
    val userIdLow: Long = 0,
    val userName: String = "",
    val userPublicKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HaLowInitConfig
        return countryCode == other.countryCode &&
            meshId == other.meshId &&
            passphrase == other.passphrase &&
            maxHop == other.maxHop &&
            userIdHigh == other.userIdHigh &&
            userIdLow == other.userIdLow &&
            userName == other.userName &&
            userPublicKey.contentEquals(other.userPublicKey)
    }

    override fun hashCode(): Int {
        var result = countryCode.hashCode()
        result = 31 * result + meshId.hashCode()
        result = 31 * result + passphrase.hashCode()
        result = 31 * result + maxHop
        result = 31 * result + userIdHigh.hashCode()
        result = 31 * result + userIdLow.hashCode()
        result = 31 * result + userName.hashCode()
        result = 31 * result + userPublicKey.contentHashCode()
        return result
    }
}

data class NetworkPacket(
    val messageIdHigh: Long = 0,
    val messageIdLow: Long = 0,
    val from: Long = 0,
    val to: Long = 0,
    val operation: Int = 0,
    val interfaceId: Int = 0,
    val sequence: Int = 0,
    val userHigh: Long = 0,
    val userLow: Long = 0,
    val mime: PacketMime = PacketMime.UNSPECIFIED,
    val maxHop: Int = 0,
    val payload: ByteArray = ByteArray(0),
    val beacon: EdgeZAssocMetadata? = null,
    val beaconRaw: String = "",
    val halowStatus: HaLowInterfaceStatus? = null,
    val init: HaLowInitConfig? = null,
) {
    val conversationMessage: ConversationMessage?
        get() = if (mime != PacketMime.UNSPECIFIED) {
            EdgezUsbControlProto.decodeConversationMessage(payload)
        } else {
            null
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NetworkPacket
        return messageIdHigh == other.messageIdHigh &&
            messageIdLow == other.messageIdLow &&
            from == other.from &&
            to == other.to &&
            operation == other.operation &&
            interfaceId == other.interfaceId &&
            sequence == other.sequence &&
            userHigh == other.userHigh &&
            userLow == other.userLow &&
            mime == other.mime &&
            maxHop == other.maxHop &&
            payload.contentEquals(other.payload) &&
            beacon == other.beacon &&
            beaconRaw == other.beaconRaw &&
            halowStatus == other.halowStatus &&
            init == other.init
    }

    override fun hashCode(): Int {
        var result = messageIdHigh.hashCode()
        result = 31 * result + messageIdLow.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + operation
        result = 31 * result + interfaceId
        result = 31 * result + sequence
        result = 31 * result + userHigh.hashCode()
        result = 31 * result + userLow.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + maxHop
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (beacon?.hashCode() ?: 0)
        result = 31 * result + beaconRaw.hashCode()
        result = 31 * result + (halowStatus?.hashCode() ?: 0)
        result = 31 * result + (init?.hashCode() ?: 0)
        return result
    }
}

enum class PacketMime(val wireValue: Int) {
    UNSPECIFIED(0),
    TEXT(1),
    VOICE(2),
    IMAGE(3),
    VIDEO(4),
    BINARY(5);

    companion object {
        fun fromWireValue(value: Int): PacketMime {
            return values().firstOrNull { it.wireValue == value } ?: UNSPECIFIED
        }
    }
}

data class ConversationMessage(
    val nonce: ByteArray = ByteArray(0),
    val ciphertext: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConversationMessage
        return nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
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
        var halowStatus: HaLowInterfaceStatus? = null

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
                    if (field == 8) {
                        halowStatus = decodeHaLowInterfaceStatus(payload.copyOfRange(offset, offset + len))
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
            halowStatus = halowStatus,
        )
    }

    fun encodeHaLowInit(
        countryCode: String,
        meshId: String,
        passphrase: String,
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        maxHop: Int,
    ): ByteArray {
        val init = ByteArrayOutputStream()
        writeStringField(init, 1, countryCode.take(2).uppercase())
        writeStringField(init, 2, meshId.take(32))
        writeStringField(init, 3, passphrase.take(64))
        writeVarintField(init, 4, maxHop.coerceIn(0, 255).toLong())
        writeVarintField(init, 5, userIdHigh)
        writeVarintField(init, 6, userIdLow)
        writeStringField(init, 7, userName.take(64))
        writeBytesField(init, 8, userPublicKey.copyOf(minOf(userPublicKey.size, 32)))

        return encodeNetworkPacket(
            userIdHigh = userIdHigh,
            userIdLow = userIdLow,
        ) { out ->
            writeBytesField(out, NETWORK_PACKET_INIT_TAG, init.toByteArray())
        }
    }

    fun encodeHaLowBeacon(
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        latitude: Double? = null,
        longitude: Double? = null,
        locationTimestampMs: Long = 0,
    ): ByteArray {
        val beacon = encodeBeacon(userIdHigh, userIdLow, userName, userPublicKey, latitude, longitude, locationTimestampMs)
        return encodeNetworkPacket(
            userIdHigh = userIdHigh,
            userIdLow = userIdLow,
        ) { out ->
            writeStringField(out, NETWORK_PACKET_BEACON_TAG, beacon)
        }
    }

    fun encodeStatusRequest(): ByteArray {
        return encodeNetworkPacket { out ->
            writeBytesField(out, NETWORK_PACKET_STATUS_TAG, ByteArray(0))
        }
    }

    fun encodeConversationMessage(
        message: ConversationMessage,
        from: Long,
        to: Long,
        mime: PacketMime = PacketMime.TEXT,
        maxHop: Int = 0,
        sequence: Int = 0,
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        userIdHigh: Long = 0,
        userIdLow: Long = 0,
    ): ByteArray {
        val conversation = ByteArrayOutputStream()
        writeBytesField(conversation, 1, message.nonce)
        writeBytesField(conversation, 2, message.ciphertext)
        val conversationPayload = conversation.toByteArray()
        require(conversationPayload.size <= EDGEZ_NETWORK_PACKET_MAX_PAYLOAD) {
            "NetworkPacket payload too large: ${conversationPayload.size}/$EDGEZ_NETWORK_PACKET_MAX_PAYLOAD"
        }

        return encodeNetworkPacket(
            messageIdHigh = messageIdHigh,
            messageIdLow = messageIdLow,
            from = from,
            to = to,
            userIdHigh = userIdHigh,
            userIdLow = userIdLow,
            mime = mime,
            maxHop = maxHop,
            sequence = sequence,
        ) { out ->
            writeBytesField(out, NETWORK_PACKET_PAYLOAD_TAG, conversationPayload)
        }
    }

    fun decodeMobileFromRadio(payload: ByteArray): HaLowInterfaceStatus? {
        return decodeNetworkPacket(payload)?.halowStatus
    }

    fun decodeMobileFromRadioMessage(payload: ByteArray): NetworkPacket? {
        return decodeNetworkPacket(payload)
    }

    fun decodeNetworkPacket(payload: ByteArray): NetworkPacket? {
        var offset = 0
        var messageIdHigh = 0L
        var messageIdLow = 0L
        var from = 0L
        var to = 0L
        var operation = 0
        var interfaceId = 0
        var sequence = 0
        var userHigh = 0L
        var userLow = 0L
        var mime = PacketMime.UNSPECIFIED
        var maxHop = 0
        var packetPayload = ByteArray(0)
        var beacon: EdgeZAssocMetadata? = null
        var beaconRaw = ""
        var halowStatus: HaLowInterfaceStatus? = null
        var init: HaLowInitConfig? = null

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
                        1 -> messageIdHigh = valueRead.value
                        2 -> messageIdLow = valueRead.value
                        3 -> from = valueRead.value
                        4 -> to = valueRead.value
                        5 -> operation = valueRead.value.toInt()
                        6 -> interfaceId = valueRead.value.toInt()
                        7 -> sequence = valueRead.value.toInt()
                        8 -> userHigh = valueRead.value
                        9 -> userLow = valueRead.value
                        10 -> mime = PacketMime.fromWireValue(valueRead.value.toInt())
                        11 -> maxHop = valueRead.value.toInt()
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        NETWORK_PACKET_PAYLOAD_TAG -> packetPayload = bytes
                        NETWORK_PACKET_BEACON_TAG -> {
                            beaconRaw = String(bytes, StandardCharsets.UTF_8)
                            beacon = decodeBeaconString(bytes)
                        }
                        NETWORK_PACKET_STATUS_TAG -> halowStatus = decodeHaLowInterfaceStatus(bytes)
                        NETWORK_PACKET_INIT_TAG -> init = decodeHaLowInitConfig(bytes)
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return NetworkPacket(
            messageIdHigh = messageIdHigh,
            messageIdLow = messageIdLow,
            from = from,
            to = to,
            operation = operation,
            interfaceId = interfaceId,
            sequence = sequence,
            userHigh = userHigh,
            userLow = userLow,
            mime = mime,
            maxHop = maxHop,
            payload = packetPayload,
            beacon = beacon,
            beaconRaw = beaconRaw,
            halowStatus = halowStatus,
            init = init,
        )
    }

    fun decodeConversationMessage(payload: ByteArray): ConversationMessage? {
        var offset = 0
        var nonce = ByteArray(0)
        var ciphertext = ByteArray(0)

        while (offset < payload.size) {
            val tagRead = readVarint(payload, offset) ?: return null
            offset = tagRead.nextOffset
            val field = (tagRead.value ushr 3).toInt()
            val wireType = (tagRead.value and 0x07).toInt()

            when (wireType) {
                0 -> {
                    val valueRead = readVarint(payload, offset) ?: return null
                    offset = valueRead.nextOffset
                    if (valueRead.value < 0) return null
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        1 -> nonce = bytes
                        2 -> ciphertext = bytes
                    }
                    offset += len
                }
                else -> return null
            }
        }

        if (nonce.isEmpty() &&
            ciphertext.isEmpty()
        ) {
            return null
        }

        return ConversationMessage(
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }

    fun decodeHaLowInterfaceStatus(payload: ByteArray): HaLowInterfaceStatus? {
        var offset = 0
        var supported = false
        var stackInitialized = false
        var meshMode = false
        var linkUp = false
        var routeReady = false
        var readyForReport = false
        var ethertype = 0
        var meshId = ""
        var ipAddr = ""
        var gateway = ""
        var macAddress = 0L

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
                        1 -> supported = valueRead.value != 0L
                        2 -> stackInitialized = valueRead.value != 0L
                        3 -> meshMode = valueRead.value != 0L
                        4 -> linkUp = valueRead.value != 0L
                        5 -> routeReady = valueRead.value != 0L
                        6 -> readyForReport = valueRead.value != 0L
                        7 -> ethertype = valueRead.value.toInt()
                        11 -> macAddress = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val text = String(payload, offset, len, StandardCharsets.UTF_8)
                    when (field) {
                        8 -> meshId = text
                        9 -> ipAddr = text
                        10 -> gateway = text
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return HaLowInterfaceStatus(
            supported = supported,
            stackInitialized = stackInitialized,
            meshMode = meshMode,
            linkUp = linkUp,
            routeReady = routeReady,
            readyForReport = readyForReport,
            ethertype = ethertype,
            meshId = meshId,
            ipAddr = ipAddr,
            gateway = gateway,
            macAddress = macAddress,
        )
    }

    fun decodeHaLowInitConfig(payload: ByteArray): HaLowInitConfig? {
        var offset = 0
        var countryCode = ""
        var meshId = ""
        var passphrase = ""
        var maxHop = 0
        var userIdHigh = 0L
        var userIdLow = 0L
        var userName = ""
        var userPublicKey = ByteArray(0)

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
                        4 -> maxHop = valueRead.value.toInt()
                        5 -> userIdHigh = valueRead.value
                        6 -> userIdLow = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        1 -> countryCode = String(bytes, StandardCharsets.UTF_8)
                        2 -> meshId = String(bytes, StandardCharsets.UTF_8)
                        3 -> passphrase = String(bytes, StandardCharsets.UTF_8)
                        7 -> userName = String(bytes, StandardCharsets.UTF_8)
                        8 -> userPublicKey = bytes
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return HaLowInitConfig(
            countryCode = countryCode,
            meshId = meshId,
            passphrase = passphrase,
            maxHop = maxHop,
            userIdHigh = userIdHigh,
            userIdLow = userIdLow,
            userName = userName,
            userPublicKey = userPublicKey,
        )
    }

    fun decodeEdgeZAssocMetadata(payload: ByteArray): EdgeZAssocMetadata? {
        var offset = 0
        var userIdHigh = 0L
        var userIdLow = 0L
        var userName = ""
        var userPublicKey = ByteArray(0)
        var latitude: Double? = null
        var longitude: Double? = null
        var locationTimestampMs = 0L

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
                        1 -> userIdHigh = valueRead.value
                        2 -> userIdLow = valueRead.value
                        7 -> locationTimestampMs = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        3 -> userName = String(bytes, StandardCharsets.UTF_8)
                        4 -> userPublicKey = bytes
                        5 -> latitude = String(bytes, StandardCharsets.UTF_8).toDoubleOrNull()
                        6 -> longitude = String(bytes, StandardCharsets.UTF_8).toDoubleOrNull()
                    }
                    offset += len
                }
                5 -> {
                    if (offset + 4 > payload.size) return null
                    val value = readFixed32Float(payload, offset).toDouble()
                    when (field) {
                        5 -> latitude = value
                        6 -> longitude = value
                    }
                    offset += 4
                }
                else -> return null
            }
        }

        if (userIdHigh == 0L && userIdLow == 0L && userName.isBlank() && userPublicKey.isEmpty()) {
            return null
        }

        return EdgeZAssocMetadata(
            userIdHigh = userIdHigh,
            userIdLow = userIdLow,
            userName = userName,
            userPublicKey = userPublicKey,
            latitude = latitude,
            longitude = longitude,
            locationTimestampMs = locationTimestampMs,
        )
    }

    private fun encodeNetworkPacket(
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        from: Long = 0,
        to: Long = 0,
        userIdHigh: Long = 0,
        userIdLow: Long = 0,
        mime: PacketMime = PacketMime.UNSPECIFIED,
        maxHop: Int = 0,
        sequence: Int = 0,
        writeBody: (ByteArrayOutputStream) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val generatedId = if (messageIdHigh == 0L && messageIdLow == 0L) newMessageId() else messageIdHigh to messageIdLow
        writeVarintField(out, 1, generatedId.first)
        writeVarintField(out, 2, generatedId.second)
        if (from != 0L) {
            writeVarintField(out, 3, from)
        }
        if (to != 0L) {
            writeVarintField(out, 4, to)
        }
        writeVarintField(out, 5, NETWORK_OPERATION_REQUEST.toLong())
        writeVarintField(out, 6, NETWORK_INTERFACE_HALOW.toLong())
        if (sequence > 0) {
            writeVarintField(out, 7, sequence.toLong())
        }
        writeVarintField(out, 8, userIdHigh)
        writeVarintField(out, 9, userIdLow)
        if (mime != PacketMime.UNSPECIFIED) {
            writeVarintField(out, 10, mime.wireValue.toLong())
        }
        if (maxHop > 0) {
            writeVarintField(out, 11, maxHop.coerceIn(0, 255).toLong())
        }
        writeBody(out)
        return out.toByteArray()
    }

    private fun newMessageId(): Pair<Long, Long> {
        val uuid = UUID.randomUUID()
        return uuid.mostSignificantBits to uuid.leastSignificantBits
    }

    private fun encodeBeacon(
        userIdHigh: Long,
        userIdLow: Long,
        userName: String,
        userPublicKey: ByteArray,
        latitude: Double?,
        longitude: Double?,
        locationTimestampMs: Long,
    ): String {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, userIdHigh)
        writeVarintField(out, 2, userIdLow)
        writeStringField(out, 3, userName.take(64))
        writeBytesField(out, 4, userPublicKey.copyOf(minOf(userPublicKey.size, 32)))
        if (latitude != null && longitude != null) {
            writeFloatField(out, 5, latitude.toFloat())
            writeFloatField(out, 6, longitude.toFloat())
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun decodeBeaconString(payload: ByteArray): EdgeZAssocMetadata? {
        return try {
            decodeEdgeZAssocMetadata(Base64.getDecoder().decode(payload))
        } catch (_: IllegalArgumentException) {
            decodeEdgeZAssocMetadata(payload)
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeBytesField(out, fieldNumber, bytes)
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeFloatField(out: ByteArrayOutputStream, fieldNumber: Int, value: Float) {
        writeVarint(out, ((fieldNumber shl 3) or 5).toLong())
        val bits = value.toRawBits()
        out.write(bits and 0xff)
        out.write((bits ushr 8) and 0xff)
        out.write((bits ushr 16) and 0xff)
        out.write((bits ushr 24) and 0xff)
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

    private fun readFixed32Float(data: ByteArray, offset: Int): Float {
        val bits = (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
        return Float.fromBits(bits)
    }

    private data class VarintRead(val value: Long, val nextOffset: Int)
}

class EdgezUsbClient(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    @Volatile private var rxTaskRunning = false
    private var rxTask: Thread? = null
    private val frameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    private val debugListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 4)
    private var rxLen = 0

    fun scan(): List<UsbCandidate> {
        val devices = usbManager.deviceList.values.toList()
        emitDebug("SCAN devices=${devices.size}")
        val candidates = devices.flatMapIndexed { deviceIndex, device ->
            emitDebug(
                "SCAN device[$deviceIndex] VID=%04x PID=%04x name=${safeProductName(device)} manufacturer=${safeManufacturerName(device)} serial=${safeSerialNumber(device)} class=${device.deviceClass} sub=${device.deviceSubclass} proto=${device.deviceProtocol} ifaces=${device.interfaceCount} permission=${usbManager.hasPermission(device)}"
                    .format(device.vendorId, device.productId),
            )
            logInterfaces(device)
            findVendorCandidates(device).also { candidates ->
                if (candidates.isEmpty()) {
                    emitDebug("SCAN unsupported USB vendor device VID=%04x PID=%04x".format(device.vendorId, device.productId))
                }
                candidates.forEach { emitDebug("SCAN candidate ${it.label}") }
            }
        }.sortedWith(compareBy({ if (it.device.vendorId == ESPRESSIF_VID) 0 else 1 }, { it.interfaceIndex }, { it.label }))
        candidates.forEachIndexed { index, candidate ->
            emitDebug("SCAN option[$index] ${candidate.label} permission=${usbManager.hasPermission(candidate.device)}")
        }
        return candidates
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun isConnected(): Boolean = connection != null && claimedInterface != null && readEndpoint != null && writeEndpoint != null

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
            if (!opened.claimInterface(candidate.intf, true)) {
                opened.close()
                return "Claim interface failed"
            }
        } catch (e: RuntimeException) {
            opened.close()
            return "USB open failed: ${e.message}"
        }

        connection = opened
        claimedInterface = candidate.intf
        readEndpoint = candidate.readEndpoint
        writeEndpoint = candidate.writeEndpoint
        emitDebug("CONNECT ${candidate.label}")
        startRxTask(candidate.readEndpoint)
        return "Connected to ${candidate.label}"
    }

    fun close() {
        stopRxTask()
        try {
            claimedInterface?.let { connection?.releaseInterface(it) }
        } catch (_: RuntimeException) {
        }
        connection?.close()
        connection = null
        claimedInterface = null
        readEndpoint = null
        writeEndpoint = null
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
        if (action != USB_CONTROL_ACTION_GET_STATUS) {
            return Result.failure(UnsupportedOperationException("Legacy control action is no longer supported"))
        }
        return sendFrame(EdgezUsbControlProto.encodeStatusRequest(), timeoutMs)
    }

    fun sendEcho(message: String, timeoutMs: Int = 1500): Result<String> {
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
        timeoutMs: Int = 1500,
    ): Result<String> {
        return sendFrame(
            EdgezUsbControlProto.encodeHaLowInit(countryCode, meshId, passphrase, userIdHigh, userIdLow, userName, userPublicKey, maxHop),
            timeoutMs,
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
        timeoutMs: Int = 1500,
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
            timeoutMs,
        )
    }

    fun sendConversationMessage(
        message: ConversationMessage,
        from: Long,
        to: Long,
        mime: PacketMime = PacketMime.TEXT,
        maxHop: Int = 0,
        sequence: Int = 0,
        messageIdHigh: Long = 0,
        messageIdLow: Long = 0,
        userIdHigh: Long = 0,
        userIdLow: Long = 0,
        timeoutMs: Int = 1500,
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
            )
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return sendFrame(packet, timeoutMs)
    }

    private fun startRxTask(endpoint: UsbEndpoint) {
        stopRxTask()
        rxLen = 0
        rxTaskRunning = true
        emitDebug("USB RX task start ${endpoint.describe()}")
        rxTask = Thread {
            val scratch = ByteArray(EDGEZ_MAX_FRAME)
            while (rxTaskRunning) {
                val activeConnection = connection
                val activeEndpoint = readEndpoint
                if (activeConnection == null || activeEndpoint == null) {
                    break
                }
                try {
                    val read = activeConnection.bulkTransfer(
                        activeEndpoint,
                        scratch,
                        scratch.size,
                        USB_READ_TIMEOUT_MS,
                    )
                    if (read > 0) {
                        handleRxBytes(scratch.copyOf(read))
                    } else if (read < 0) {
                        emitDebug("USB RX error: read=$read")
                        break
                    }
                } catch (e: RuntimeException) {
                    if (rxTaskRunning) {
                        emitDebug("USB RX error: ${e.message}")
                    }
                    break
                }
            }
        }.apply {
            name = "edgez-usb-rx"
            isDaemon = true
            start()
        }
    }

    private fun stopRxTask() {
        rxTaskRunning = false
        rxTask?.interrupt()
        rxTask = null
    }

    @Synchronized
    private fun handleRxBytes(data: ByteArray) {
        if (rxLen + data.size > rxBuffer.size) {
            emitDebug("RX overflow buffered=$rxLen read=${data.size}; reset")
            rxLen = 0
        }
        System.arraycopy(data, 0, rxBuffer, rxLen, data.size)
        rxLen += data.size
        emitDebug("RX chunk read=${data.size} buffered=$rxLen")

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
                emitDebug("RX partial frame need=$frameLen buffered=$rxLen")
                break
            }

            val frame = Arrays.copyOf(rxBuffer, frameLen)
            emitDebug("RX protobuf frame len=$payloadLen")
            dispatchFrame(frame)
            if (rxLen == frameLen) {
                rxLen = 0
            } else {
                System.arraycopy(rxBuffer, frameLen, rxBuffer, 0, rxLen - frameLen)
                rxLen -= frameLen
            }
        }
    }

    private fun sendFrame(payload: ByteArray, timeoutMs: Int): Result<String> {
        val activeConnection = connection ?: return Result.failure(IllegalStateException("USB is not connected"))
        val endpoint = writeEndpoint ?: return Result.failure(IllegalStateException("USB write endpoint is not open"))
        if (payload.size > EDGEZ_MAX_PAYLOAD) {
            return Result.failure(IllegalArgumentException("Payload too large: ${payload.size}/$EDGEZ_MAX_PAYLOAD"))
        }

        val tx = ByteBuffer.allocate(EDGEZ_HEADER_LEN + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        tx.put(EDGEZ_MAGIC_0)
        tx.put(EDGEZ_MAGIC_1)
        tx.putShort(payload.size.toShort())
        tx.put(payload)

        val txBytes = tx.array()
        emitDebug("TX protobuf frame len=${payload.size} ep=${endpoint.describe()}")
        try {
            val written = activeConnection.bulkTransfer(endpoint, txBytes, txBytes.size, timeoutMs)
            if (written != txBytes.size) {
                emitDebug("TX short written=$written/${txBytes.size}")
                return Result.failure(IllegalStateException("USB short write on ${endpoint.describe()}: $written/${txBytes.size}"))
            }
        } catch (e: RuntimeException) {
            emitDebug("TX failed: ${e.message}")
            return Result.failure(IllegalStateException("USB write failed on ${endpoint.describe()}: ${e.message}", e))
        }
        emitDebug("TX result written=${txBytes.size}/${txBytes.size}")
        return Result.success("Sent protobuf")
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

    private fun findVendorCandidates(device: UsbDevice): List<UsbCandidate> {
        val candidates = mutableListOf<UsbCandidate>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) {
                continue
            }
            var readEndpoint: UsbEndpoint? = null
            var writeEndpoint: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    readEndpoint = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    writeEndpoint = endpoint
                }
            }
            if (readEndpoint != null && writeEndpoint != null) {
                candidates += UsbCandidate(
                    device = device,
                    intf = intf,
                    readEndpoint = readEndpoint,
                    writeEndpoint = writeEndpoint,
                    interfaceIndex = i,
                )
            }
        }
        return candidates
    }

    private fun logInterfaces(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            emitDebug("SCAN if[$i] id=${intf.id} alt=${intf.alternateSetting} ${intf.describeClass()} name=${intf.name ?: "-"} eps=${intf.endpointCount}")
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                emitDebug(
                    "SCAN if[$i].ep[$e] ${endpoint.describe()} attr=0x%02x type=${endpoint.type} interval=${endpoint.interval}"
                        .format(endpoint.attributes),
                )
            }
        }
    }

    private fun safeProductName(device: UsbDevice): String {
        return try {
            device.productName ?: "USB device"
        } catch (_: SecurityException) {
            "USB device"
        }
    }

    private fun safeManufacturerName(device: UsbDevice): String {
        return try {
            device.manufacturerName ?: "-"
        } catch (_: SecurityException) {
            "permission-required"
        }
    }

    private fun safeSerialNumber(device: UsbDevice): String {
        return try {
            device.serialNumber ?: "-"
        } catch (_: SecurityException) {
            "permission-required"
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

}

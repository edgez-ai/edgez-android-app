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
import java.util.concurrent.CopyOnWriteArraySet

const val ACTION_USB_PERMISSION = "ai.edgez.edgez.USB_PERMISSION"
const val EDGEZ_MAGIC_0 = 'E'.code.toByte()
const val EDGEZ_MAGIC_1 = 'Z'.code.toByte()
const val EDGEZ_VERSION = 1.toByte()
const val EDGEZ_TYPE_ECHO_RESP = 2
const val EDGEZ_TYPE_CONTROL_RESP = 4
const val EDGEZ_TYPE_HALOW_SYNC_TO_RADIO = 16
const val EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO = 17
const val EDGEZ_TYPE_HALOW_SYNC_STATUS_REQ = 18
const val EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP = 19
const val EDGEZ_TYPE_ERROR = 0x7f
const val EDGEZ_HEADER_LEN = 8
const val EDGEZ_MAX_PAYLOAD = 512

const val USB_CONTROL_ACTION_SET_BLE_ENABLED = 1
const val USB_CONTROL_ACTION_SET_PAIRING_ENABLED = 2
const val USB_CONTROL_ACTION_SET_WIFI_CREDENTIALS = 3
const val USB_CONTROL_ACTION_GET_STATUS = 4
const val USB_CONTROL_ACTION_ECHO = 5
const val USB_CONTROL_ACTION_GET_HALOW_SYNC_STATUS = 6
const val MOBILE_RADIO_VARIANT_RAW_RADIO_BUFFER = 1
const val MOBILE_RADIO_VARIANT_WANT_CONFIG = 2
const val MOBILE_RADIO_VARIANT_DISCONNECT = 3
const val MOBILE_RADIO_VARIANT_HEARTBEAT = 4
const val MOBILE_RADIO_VARIANT_CONFIG_COMPLETE = 5
const val MOBILE_RADIO_VARIANT_QUEUE_STATUS = 6
const val MOBILE_RADIO_VARIANT_REBOOTED = 7
const val MOBILE_RADIO_VARIANT_HALOW_STATUS = 8
const val MOBILE_RADIO_VARIANT_NODE_INFO = 9
const val MOBILE_RADIO_VARIANT_INIT_HALOW = 10
const val MOBILE_RADIO_VARIANT_CONVERSATION_MESSAGE = 11
private const val NETWORK_OPERATION_REQUEST = 1
private const val NETWORK_INTERFACE_HALOW = 5
private const val NETWORK_PACKET_PAYLOAD_TAG = 100
private const val NETWORK_PACKET_BEACON_TAG = 101
private const val NETWORK_PACKET_STATUS_TAG = 102
private const val NETWORK_PACKET_INIT_TAG = 103

private const val ESPRESSIF_VID = 0x303A
private const val EDGEZ_TYPE_CONTROL_REQ = 3.toByte()
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
    val userId: Long = 0,
    val userName: String = "",
    val userPublicKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EdgeZAssocMetadata
        return userId == other.userId &&
            userName == other.userName &&
            userPublicKey.contentEquals(other.userPublicKey)
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + userName.hashCode()
        result = 31 * result + userPublicKey.contentHashCode()
        return result
    }
}

data class DiscoveredNodeInfo(
    val bssid: ByteArray = ByteArray(0),
    val ssid: String = "",
    val meshId: String = "",
    val rssi: Int = 0,
    val channelFreqHz: Int = 0,
    val bandwidthMhz: Int = 0,
    val informationElements: ByteArray = ByteArray(0),
    val edgezMetadata: EdgeZAssocMetadata? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiscoveredNodeInfo
        return bssid.contentEquals(other.bssid) &&
            ssid == other.ssid &&
            meshId == other.meshId &&
            rssi == other.rssi &&
            channelFreqHz == other.channelFreqHz &&
            bandwidthMhz == other.bandwidthMhz &&
            informationElements.contentEquals(other.informationElements) &&
            edgezMetadata == other.edgezMetadata
    }

    override fun hashCode(): Int {
        var result = bssid.contentHashCode()
        result = 31 * result + ssid.hashCode()
        result = 31 * result + meshId.hashCode()
        result = 31 * result + rssi
        result = 31 * result + channelFreqHz
        result = 31 * result + bandwidthMhz
        result = 31 * result + informationElements.contentHashCode()
        result = 31 * result + (edgezMetadata?.hashCode() ?: 0)
        return result
    }
}

data class MobileFromRadio(
    val variant: Int = 0,
    val id: Int = 0,
    val rawRadioBuffer: ByteArray = ByteArray(0),
    val halowStatus: HaLowInterfaceStatus? = null,
    val discoveredNode: DiscoveredNodeInfo? = null,
    val conversationMessage: ConversationMessage? = null,
    val user: EdgeZAssocMetadata? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MobileFromRadio
        return variant == other.variant &&
            id == other.id &&
            rawRadioBuffer.contentEquals(other.rawRadioBuffer) &&
            halowStatus == other.halowStatus &&
            discoveredNode == other.discoveredNode &&
            conversationMessage == other.conversationMessage &&
            user == other.user
    }

    override fun hashCode(): Int {
        var result = variant
        result = 31 * result + id
        result = 31 * result + rawRadioBuffer.contentHashCode()
        result = 31 * result + (halowStatus?.hashCode() ?: 0)
        result = 31 * result + (discoveredNode?.hashCode() ?: 0)
        result = 31 * result + (conversationMessage?.hashCode() ?: 0)
        result = 31 * result + (user?.hashCode() ?: 0)
        return result
    }
}

data class ConversationMessage(
    val senderUserId: Long = 0,
    val senderName: String = "",
    val senderPublicKey: ByteArray = ByteArray(0),
    val recipientUserId: Long = 0,
    val nonce: ByteArray = ByteArray(0),
    val ciphertext: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConversationMessage
        return senderUserId == other.senderUserId &&
            senderName == other.senderName &&
            senderPublicKey.contentEquals(other.senderPublicKey) &&
            recipientUserId == other.recipientUserId &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = senderUserId.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + senderPublicKey.contentHashCode()
        result = 31 * result + recipientUserId.hashCode()
        result = 31 * result + nonce.contentHashCode()
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
        userId: Long,
        userName: String,
        userPublicKey: ByteArray,
        maxHop: Int,
    ): ByteArray {
        val init = ByteArrayOutputStream()
        writeStringField(init, 1, countryCode.take(2).uppercase())
        writeStringField(init, 2, meshId.take(32))
        writeStringField(init, 3, passphrase.take(64))
        writeVarintField(init, 4, maxHop.coerceIn(0, 255).toLong())

        return encodeNetworkPacket(
            userId = userId,
        ) { out ->
            writeBytesField(out, NETWORK_PACKET_INIT_TAG, init.toByteArray())
        }
    }

    fun encodeHaLowBeacon(
        userId: Long,
        userName: String,
        userPublicKey: ByteArray,
    ): ByteArray {
        val beacon = encodeBeacon(userId, userName, userPublicKey)
        return encodeNetworkPacket(
            userId = userId,
        ) { out ->
            writeStringField(out, NETWORK_PACKET_BEACON_TAG, beacon)
        }
    }

    fun encodeConversationMessage(message: ConversationMessage): ByteArray {
        val conversation = ByteArrayOutputStream()
        writeVarintField(conversation, 1, message.senderUserId)
        writeStringField(conversation, 2, message.senderName.take(64))
        writeBytesField(conversation, 3, message.senderPublicKey.copyOf(minOf(message.senderPublicKey.size, 32)))
        writeVarintField(conversation, 4, message.recipientUserId)
        writeBytesField(conversation, 5, message.nonce)
        writeBytesField(conversation, 6, message.ciphertext)

        return encodeNetworkPacket(
            userId = message.senderUserId,
        ) { out ->
            writeBytesField(out, NETWORK_PACKET_PAYLOAD_TAG, conversation.toByteArray())
        }
    }

    fun decodeMobileFromRadio(payload: ByteArray): HaLowInterfaceStatus? {
        return decodeMobileFromRadioMessage(payload)?.halowStatus
    }

    fun decodeMobileFromRadioMessage(payload: ByteArray): MobileFromRadio? {
        var offset = 0
        var id = 0L
        var rawRadioBuffer = ByteArray(0)
        var halowStatus: HaLowInterfaceStatus? = null
        var user: EdgeZAssocMetadata? = null

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
                        1 -> id = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        NETWORK_PACKET_PAYLOAD_TAG -> rawRadioBuffer = bytes
                        NETWORK_PACKET_BEACON_TAG -> user = decodeBeaconString(bytes)
                        NETWORK_PACKET_STATUS_TAG -> halowStatus = decodeHaLowInterfaceStatus(bytes)
                    }
                    offset += len
                }
                else -> return null
            }
        }

        val variant = when {
            halowStatus != null -> MOBILE_RADIO_VARIANT_HALOW_STATUS
            rawRadioBuffer.isNotEmpty() -> MOBILE_RADIO_VARIANT_RAW_RADIO_BUFFER
            user != null -> MOBILE_RADIO_VARIANT_NODE_INFO
            else -> 0
        }

        return MobileFromRadio(
            variant = variant,
            id = (id and 0xffffffffL).toInt(),
            rawRadioBuffer = rawRadioBuffer,
            halowStatus = halowStatus,
            user = user,
        )
    }

    fun decodeConversationMessage(payload: ByteArray): ConversationMessage? {
        var offset = 0
        var senderUserId = 0L
        var senderName = ""
        var senderPublicKey = ByteArray(0)
        var recipientUserId = 0L
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
                    when (field) {
                        1 -> senderUserId = valueRead.value
                        4 -> recipientUserId = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        2 -> senderName = String(bytes, StandardCharsets.UTF_8)
                        3 -> senderPublicKey = bytes
                        5 -> nonce = bytes
                        6 -> ciphertext = bytes
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return ConversationMessage(
            senderUserId = senderUserId,
            senderName = senderName,
            senderPublicKey = senderPublicKey,
            recipientUserId = recipientUserId,
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

    fun decodeDiscoveredNodeInfo(payload: ByteArray): DiscoveredNodeInfo? {
        var offset = 0
        var bssid = ByteArray(0)
        var ssid = ""
        var meshId = ""
        var rssi = 0
        var channelFreqHz = 0
        var bandwidthMhz = 0
        var informationElements = ByteArray(0)
        var edgezMetadata: EdgeZAssocMetadata? = null

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
                        4 -> rssi = valueRead.value.toInt()
                        5 -> channelFreqHz = valueRead.value.toInt()
                        6 -> bandwidthMhz = valueRead.value.toInt()
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        1 -> bssid = bytes
                        2 -> ssid = String(bytes, StandardCharsets.UTF_8)
                        3 -> meshId = String(bytes, StandardCharsets.UTF_8)
                        7 -> informationElements = bytes
                        8 -> edgezMetadata = decodeEdgeZAssocMetadata(bytes)
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return DiscoveredNodeInfo(
            bssid = bssid,
            ssid = ssid,
            meshId = meshId,
            rssi = rssi,
            channelFreqHz = channelFreqHz,
            bandwidthMhz = bandwidthMhz,
            informationElements = informationElements,
            edgezMetadata = edgezMetadata,
        )
    }

    fun decodeEdgeZAssocMetadata(payload: ByteArray): EdgeZAssocMetadata? {
        var offset = 0
        var userId = 0L
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
                    if (field == 1) {
                        userId = valueRead.value
                    }
                }
                2 -> {
                    val lenRead = readVarint(payload, offset) ?: return null
                    offset = lenRead.nextOffset
                    val len = lenRead.value.toInt()
                    if (len < 0 || offset + len > payload.size) return null
                    val bytes = payload.copyOfRange(offset, offset + len)
                    when (field) {
                        2 -> userName = String(bytes, StandardCharsets.UTF_8)
                        3 -> userPublicKey = bytes
                    }
                    offset += len
                }
                else -> return null
            }
        }

        return EdgeZAssocMetadata(userId, userName, userPublicKey)
    }

    private fun encodeNetworkPacket(
        userId: Long,
        writeBody: (ByteArrayOutputStream) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val packetId = System.currentTimeMillis() and 0xffffffffL
        writeVarintField(out, 1, packetId)
        writeVarintField(out, 2, userId)
        writeVarintField(out, 4, NETWORK_OPERATION_REQUEST.toLong())
        writeVarintField(out, 5, NETWORK_INTERFACE_HALOW.toLong())
        writeVarintField(out, 7, userId)
        writeBody(out)
        return out.toByteArray()
    }

    private fun encodeBeacon(
        userId: Long,
        userName: String,
        userPublicKey: ByteArray,
    ): String {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, userId)
        writeStringField(out, 2, userName.take(64))
        writeBytesField(out, 3, userPublicKey.copyOf(minOf(userPublicKey.size, 32)))
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
    private var claimedInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    @Volatile private var rxTaskRunning = false
    private var rxTask: Thread? = null
    private val frameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    private val debugListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val rxBuffer = ByteArray(EDGEZ_MAX_FRAME * 4)
    private var rxLen = 0
    private var seq = 0

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

    fun sendHaLowInit(
        countryCode: String,
        meshId: String,
        passphrase: String,
        userId: Long,
        userName: String,
        userPublicKey: ByteArray,
        maxHop: Int,
        timeoutMs: Int = 1500,
    ): Result<String> {
        return sendFrame(
            EDGEZ_TYPE_HALOW_SYNC_TO_RADIO.toByte(),
            EdgezUsbControlProto.encodeHaLowInit(countryCode, meshId, passphrase, userId, userName, userPublicKey, maxHop),
            timeoutMs,
        )
    }

    fun sendHaLowBeacon(
        userId: Long,
        userName: String,
        userPublicKey: ByteArray,
        timeoutMs: Int = 1500,
    ): Result<String> {
        return sendFrame(
            EDGEZ_TYPE_HALOW_SYNC_TO_RADIO.toByte(),
            EdgezUsbControlProto.encodeHaLowBeacon(userId, userName, userPublicKey),
            timeoutMs,
        )
    }

    fun sendConversationMessage(
        message: ConversationMessage,
        timeoutMs: Int = 1500,
    ): Result<String> {
        return sendFrame(
            EDGEZ_TYPE_HALOW_SYNC_TO_RADIO.toByte(),
            EdgezUsbControlProto.encodeConversationMessage(message),
            timeoutMs,
        )
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

    private fun sendFrame(type: Byte, payload: ByteArray, timeoutMs: Int): Result<String> {
        val activeConnection = connection ?: return Result.failure(IllegalStateException("USB is not connected"))
        val endpoint = writeEndpoint ?: return Result.failure(IllegalStateException("USB write endpoint is not open"))
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
        emitDebug("TX frame type=${type.toInt() and 0xff} seq=$currentSeq len=${payload.size} ep=${endpoint.describe()}")
        try {
            val written = activeConnection.bulkTransfer(endpoint, txBytes, txBytes.size, timeoutMs)
            if (written != txBytes.size) {
                emitDebug("TX short seq=$currentSeq written=$written/${txBytes.size}")
                return Result.failure(IllegalStateException("USB short write on ${endpoint.describe()}: $written/${txBytes.size}"))
            }
        } catch (e: RuntimeException) {
            emitDebug("TX failed seq=$currentSeq: ${e.message}")
            return Result.failure(IllegalStateException("USB write failed on ${endpoint.describe()}: ${e.message}", e))
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

    private fun isKnownRxFrameType(type: Int): Boolean {
        return type == EDGEZ_TYPE_ECHO_RESP ||
            type == EDGEZ_TYPE_CONTROL_RESP ||
            type == EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO ||
            type == EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP ||
            type == EDGEZ_TYPE_ERROR
    }
}

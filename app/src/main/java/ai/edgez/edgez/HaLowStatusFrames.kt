package ai.edgez.edgez

import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.MobileFromRadio
import ai.edgez.edgez.usb.MOBILE_RADIO_VARIANT_RAW_RADIO_BUFFER
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

private const val RADIO_PACKET_HEADER_LEN = 16
private val EDGEZ_NODE_INFO_MAGIC = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'G'.code.toByte(), 'E'.code.toByte(), 'Z'.code.toByte())

data class HaLowUser(
    val nodeNum: Long,
    val shortName: String,
    val longName: String,
    val route: String,
    val lastSeenMs: Long,
    val publicKey: ByteArray = ByteArray(0),
) {
    val nodeId: String get() = "!%08x".format(nodeNum)
    val displayName: String get() = longName.ifBlank { shortName.ifBlank { nodeId } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HaLowUser
        return nodeNum == other.nodeNum &&
            shortName == other.shortName &&
            longName == other.longName &&
            route == other.route &&
            lastSeenMs == other.lastSeenMs &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = nodeNum.hashCode()
        result = 31 * result + shortName.hashCode()
        result = 31 * result + longName.hashCode()
        result = 31 * result + route.hashCode()
        result = 31 * result + lastSeenMs.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

private fun decodeHaLowSyncPayload(frame: ByteArray): ByteArray? {
    if (frame.size < EDGEZ_HEADER_LEN ||
        frame[0] != EDGEZ_MAGIC_0 ||
        frame[1] != EDGEZ_MAGIC_1 ||
        frame[2] != EDGEZ_VERSION
    ) {
        return null
    }

    val type = frame[3].toInt() and 0xff
    if (type != EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO && type != EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP) {
        return null
    }

    val payloadLen = (frame[6].toInt() and 0xff) or ((frame[7].toInt() and 0xff) shl 8)
    if (payloadLen > EDGEZ_MAX_PAYLOAD || EDGEZ_HEADER_LEN + payloadLen > frame.size) {
        return null
    }

    return frame.copyOfRange(EDGEZ_HEADER_LEN, EDGEZ_HEADER_LEN + payloadLen)
}

fun decodeHaLowStatusFrame(frame: ByteArray): HaLowInterfaceStatus? {
    val payload = decodeHaLowSyncPayload(frame) ?: return null
    return EdgezUsbControlProto.decodeMobileFromRadio(payload)
        ?: EdgezUsbControlProto.decodeHaLowInterfaceStatus(payload)
}

fun decodeHaLowSyncFrame(frame: ByteArray): MobileFromRadio? {
    val payload = decodeHaLowSyncPayload(frame) ?: return null
    return EdgezUsbControlProto.decodeMobileFromRadioMessage(payload)
}

fun HaLowInterfaceStatus.summary(): String {
    return "HaLow supported=$supported initialized=$stackInitialized mesh=$meshMode link=$linkUp route=$routeReady ready=$readyForReport meshId=$meshId ip=$ipAddr gateway=$gateway"
}

fun MobileFromRadio.summary(): String {
    if (variant == MOBILE_RADIO_VARIANT_RAW_RADIO_BUFFER && rawRadioBuffer.isNotEmpty()) {
        parseEdgeZUserFromRawRadioBuffer(rawRadioBuffer, "HaLow")?.let { user ->
            return "RadioBuffer user=${user.displayName} node=${user.nodeId} short=${user.shortName}"
        }
        return "RadioBuffer bytes=${rawRadioBuffer.size}"
    }
    discoveredNode?.let { node ->
        val metadata = node.edgezMetadata
        val user = metadata?.let { " userId=${it.userId} userName=${it.userName}" } ?: ""
        return "NodeInfo meshId=${node.meshId} ssid=${node.ssid} rssi=${node.rssi} freq=${node.channelFreqHz} bw=${node.bandwidthMhz}$user"
    }
    halowStatus?.let { return it.summary() }
    return "variant=$variant id=$id"
}

fun MobileFromRadio.toHaLowUser(route: String): HaLowUser? {
    if (variant == MOBILE_RADIO_VARIANT_RAW_RADIO_BUFFER && rawRadioBuffer.isNotEmpty()) {
        return parseEdgeZUserFromRawRadioBuffer(rawRadioBuffer, route)
    }

    val node = discoveredNode ?: return null
    val metadata = node.edgezMetadata ?: return null
    if (metadata.userId == 0L && metadata.userName.isBlank()) return null
    return HaLowUser(
        nodeNum = metadata.userId and 0xffffffffL,
        shortName = metadata.userName.take(4),
        longName = metadata.userName,
        route = route,
        lastSeenMs = System.currentTimeMillis(),
        publicKey = metadata.userPublicKey,
    )
}

fun parseEdgeZUserFromRawRadioBuffer(rawRadioBuffer: ByteArray, route: String): HaLowUser? {
    if (rawRadioBuffer.size <= RADIO_PACKET_HEADER_LEN + EDGEZ_NODE_INFO_MAGIC.size + 1) {
        return null
    }

    val payloadOffset = RADIO_PACKET_HEADER_LEN
    for (i in EDGEZ_NODE_INFO_MAGIC.indices) {
        if (rawRadioBuffer[payloadOffset + i] != EDGEZ_NODE_INFO_MAGIC[i]) {
            return null
        }
    }

    var offset = payloadOffset + EDGEZ_NODE_INFO_MAGIC.size
    val version = rawRadioBuffer[offset++].toInt() and 0xff
    if (version != 1) {
        return null
    }

    val shortRead = readNullTerminatedUtf8(rawRadioBuffer, offset) ?: return null
    offset = shortRead.nextOffset
    val longRead = readNullTerminatedUtf8(rawRadioBuffer, offset) ?: return null
    offset = longRead.nextOffset

    val publicKey = if (offset < rawRadioBuffer.size) {
        rawRadioBuffer.copyOfRange(offset, rawRadioBuffer.size)
    } else {
        ByteArray(0)
    }
    val fromNode = ByteBuffer.wrap(rawRadioBuffer, 4, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffffffffL

    return HaLowUser(
        nodeNum = fromNode,
        shortName = shortRead.value,
        longName = longRead.value,
        route = route,
        lastSeenMs = System.currentTimeMillis(),
        publicKey = publicKey,
    )
}

private data class StringRead(
    val value: String,
    val nextOffset: Int,
)

private fun readNullTerminatedUtf8(data: ByteArray, startOffset: Int): StringRead? {
    if (startOffset >= data.size) return null
    var endOffset = startOffset
    while (endOffset < data.size && data[endOffset] != 0.toByte()) {
        endOffset++
    }
    if (endOffset >= data.size) return null
    val value = String(data, startOffset, endOffset - startOffset, StandardCharsets.UTF_8)
    return StringRead(value, endOffset + 1)
}

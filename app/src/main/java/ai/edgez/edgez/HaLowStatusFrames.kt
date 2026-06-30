package ai.edgez.edgez

import android.util.Log
import ai.edgez.edgez.usb.EDGEZ_HEADER_LEN
import ai.edgez.edgez.usb.EDGEZ_MAGIC_0
import ai.edgez.edgez.usb.EDGEZ_MAGIC_1
import ai.edgez.edgez.usb.EDGEZ_MAX_PAYLOAD
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_FROM_RADIO
import ai.edgez.edgez.usb.EDGEZ_TYPE_HALOW_SYNC_STATUS_RESP
import ai.edgez.edgez.usb.EDGEZ_VERSION
import ai.edgez.edgez.usb.EdgezUsbControlProto
import ai.edgez.edgez.usb.HaLowInterfaceStatus
import ai.edgez.edgez.usb.NetworkPacket

private const val TAG_USERS = "EdgeZUsers"

data class HaLowUser(
    val nodeNum: Long,
    val userId: Long = 0,
    val userUuid: String = "",
    val shortName: String,
    val longName: String,
    val route: String,
    val lastSeenMs: Long,
    val publicKey: ByteArray = ByteArray(0),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestampMs: Long = 0,
) {
    val nodeId: String get() = formatMacAddress(nodeNum)
    val userIdText: String get() = displayUserId(userUuid, userId)
    val displayName: String get() = longName.ifBlank { shortName.ifBlank { nodeId } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HaLowUser
        return nodeNum == other.nodeNum &&
            userId == other.userId &&
            userUuid == other.userUuid &&
            shortName == other.shortName &&
            longName == other.longName &&
            route == other.route &&
            lastSeenMs == other.lastSeenMs &&
            publicKey.contentEquals(other.publicKey) &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            locationTimestampMs == other.locationTimestampMs
    }

    override fun hashCode(): Int {
        var result = nodeNum.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + userUuid.hashCode()
        result = 31 * result + shortName.hashCode()
        result = 31 * result + longName.hashCode()
        result = 31 * result + route.hashCode()
        result = 31 * result + lastSeenMs.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + locationTimestampMs.hashCode()
        return result
    }
}

private fun formatMacAddress(value: Long): String {
    val mac = value and 0xffffffffffffL
    return "%02x:%02x:%02x:%02x:%02x:%02x".format(
        (mac ushr 40) and 0xff,
        (mac ushr 32) and 0xff,
        (mac ushr 24) and 0xff,
        (mac ushr 16) and 0xff,
        (mac ushr 8) and 0xff,
        mac and 0xff,
    )
}

private fun formatUserId(value: Long): String {
    val text = "%016x".format(value)
    return "${text.substring(0, 4)}-${text.substring(4, 8)}-${text.substring(8, 12)}-${text.substring(12, 16)}"
}

private fun formatUuid(high: Long, low: Long): String {
    val highText = "%016x".format(high)
    val lowText = "%016x".format(low)
    return "${highText.substring(0, 8)}-${highText.substring(8, 12)}-${highText.substring(12, 16)}-" +
        "${lowText.substring(0, 4)}-${lowText.substring(4, 16)}"
}

private fun displayUserId(userUuid: String, userId: Long): String {
    return if (userUuid.isNotBlank() && !userUuid.startsWith("00000000-0000-0000-")) {
        userUuid
    } else {
        formatUserId(userId)
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

fun decodeHaLowSyncFrame(frame: ByteArray): NetworkPacket? {
    val payload = decodeHaLowSyncPayload(frame) ?: return null
    return EdgezUsbControlProto.decodeMobileFromRadioMessage(payload)
}

fun HaLowInterfaceStatus.summary(): String {
    val mac = if (macAddress != 0L) " mac=%012x".format(macAddress) else ""
    return "HaLow supported=$supported initialized=$stackInitialized mesh=$meshMode link=$linkUp route=$routeReady ready=$readyForReport meshId=$meshId ip=$ipAddr gateway=$gateway$mac"
}

fun NetworkPacket.summary(): String {
    beacon?.let {
        return "NetworkPacket beacon user=${it.userName.ifBlank { "unknown" }} node=${formatMacAddress(from)} userId=${formatUuid(it.userIdHigh, it.userIdLow)} id=$id"
    }
    conversationMessage?.let { message ->
        return "Conversation id=$id mime=$mime seq=$sequence from=0x%012x to=0x%012x bytes=${message.ciphertext.size}".format(from, to)
    }
    if (payload.isNotEmpty()) {
        return "NetworkPacket payload mime=$mime maxHop=$maxHop bytes=${payload.size} from=0x%012x id=$id".format(from)
    }
    halowStatus?.let { return it.summary() }
    init?.let {
        return "NetworkPacket init country=${it.countryCode} meshId=${it.meshId} maxHop=${it.maxHop} user=${it.userName.ifBlank { "unknown" }} id=$id"
    }
    return "NetworkPacket op=$operation iface=$interfaceId seq=$sequence id=$id from=0x%012x to=0x%012x user=${formatUuid(userHigh, userLow)}".format(from, to)
}

fun NetworkPacket.toHaLowUser(route: String): HaLowUser? {
    beacon?.let { metadata ->
        if (metadata.userIdHigh == 0L && metadata.userIdLow == 0L && metadata.userName.isBlank()) return null
        val user = HaLowUser(
            nodeNum = from,
            userId = metadata.userIdLow,
            userUuid = formatUuid(metadata.userIdHigh, metadata.userIdLow),
            shortName = metadata.userName.take(4),
            longName = metadata.userName,
            route = route,
            lastSeenMs = System.currentTimeMillis(),
            publicKey = metadata.userPublicKey,
            latitude = metadata.latitude,
            longitude = metadata.longitude,
            locationTimestampMs = metadata.locationTimestampMs,
        )
        Log.d(
            TAG_USERS,
            "decoded beacon node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs} " +
                "publicKeyBytes=${user.publicKey.size} route=$route",
        )
        return user
    }

    return null
}

fun HaLowUser.withFallbackLocation(previous: HaLowUser?): HaLowUser {
    if (latitude != null && longitude != null) return this
    if (previous?.latitude == null || previous.longitude == null) return this
    val updated = copy(
        latitude = previous.latitude,
        longitude = previous.longitude,
        locationTimestampMs = previous.locationTimestampMs,
    )
    Log.d(
        TAG_USERS,
        "kept previous location node=${updated.nodeId} lat=${updated.latitude} lon=${updated.longitude} locTs=${updated.locationTimestampMs}",
    )
    return updated
}

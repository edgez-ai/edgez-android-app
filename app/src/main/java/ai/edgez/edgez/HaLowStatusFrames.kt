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

fun decodeHaLowStatusFrame(frame: ByteArray): HaLowInterfaceStatus? {
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

    val payload = frame.copyOfRange(EDGEZ_HEADER_LEN, EDGEZ_HEADER_LEN + payloadLen)
    return EdgezUsbControlProto.decodeMobileFromRadio(payload)
        ?: EdgezUsbControlProto.decodeHaLowInterfaceStatus(payload)
}

fun HaLowInterfaceStatus.summary(): String {
    return "HaLow supported=$supported initialized=$stackInitialized mesh=$meshMode link=$linkUp route=$routeReady ready=$readyForReport meshId=$meshId ip=$ipAddr gateway=$gateway"
}

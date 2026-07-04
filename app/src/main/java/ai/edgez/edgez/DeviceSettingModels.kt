package ai.edgez.edgez

import java.util.UUID

enum class GeoFenceAlertCondition(val protoValue: Int, val label: String) {
    UNSPECIFIED(0, "Unspecified"),
    ENTER(1, "Enter"),
    EXIT(2, "Exit"),
    NEAR(3, "Near"),
    FAR(4, "Far"),
    LOW_BATTERY(5, "Low battery");

    companion object {
        fun fromProtoValue(value: Int): GeoFenceAlertCondition {
            return entries.firstOrNull { it.protoValue == value } ?: UNSPECIFIED
        }

        fun fromName(name: String?): GeoFenceAlertCondition {
            return entries.firstOrNull { it.name == name } ?: UNSPECIFIED
        }
    }
}

data class DeviceGeoFence(
    val idHigh: Long,
    val idLow: Long,
    val name: String,
    val marker: String = NodeMapMarker.DEFAULT.id,
    val alertCondition: GeoFenceAlertCondition = GeoFenceAlertCondition.UNSPECIFIED,
) {
    val key: String get() = "$idHigh:$idLow"

    companion object {
        fun create(
            name: String,
            marker: String = NodeMapMarker.DEFAULT.id,
            alertCondition: GeoFenceAlertCondition = GeoFenceAlertCondition.UNSPECIFIED,
        ): DeviceGeoFence {
            val uuid = UUID.randomUUID()
            return DeviceGeoFence(
                idHigh = uuid.mostSignificantBits,
                idLow = uuid.leastSignificantBits,
                name = name.ifBlank { "Geo fence" }.take(64),
                marker = NodeMapMarker.normalize(marker),
                alertCondition = alertCondition,
            )
        }
    }
}

enum class DeviceSensorType(val protoValue: Int, val label: String) {
    UNSPECIFIED(0, "Unspecified"),
    NONE(1, "None"),
    TEMPERATURE(2, "Temperature"),
    HUMIDITY(3, "Humidity"),
    PRESSURE(4, "Pressure"),
    TEMPERATURE_HUMIDITY(5, "Temperature + humidity"),
    TEMPERATURE_HUMIDITY_PRESSURE(6, "Temperature + humidity + pressure"),
    GPS(7, "GPS");

    companion object {
        fun fromProtoValue(value: Int): DeviceSensorType {
            return entries.firstOrNull { it.protoValue == value } ?: UNSPECIFIED
        }

        fun fromName(name: String?): DeviceSensorType {
            return entries.firstOrNull { it.name == name } ?: UNSPECIFIED
        }
    }
}

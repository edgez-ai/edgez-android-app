package ai.edgez.edgez

import android.content.Context
import org.json.JSONObject
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
    val geoIndex: Int = 0,
) {
    val key: String get() = keyFor(idHigh, idLow)
    val legacyKey: String get() = "$idHigh:$idLow"
    val isEmptyId: Boolean get() = idHigh == 0L && idLow == 0L

    companion object {
        fun keyFor(idHigh: Long, idLow: Long): String = UUID(idHigh, idLow).toString()

        fun matchesKey(geoFence: DeviceGeoFence, key: String): Boolean {
            return geoFence.key == key || geoFence.legacyKey == key
        }

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

enum class DeviceSensorConnector(val assetFolder: String) {
    UART_I2C("uart_i2c"),
    RS485("rs485"),
}

data class DeviceSensorDefinition(
    val key: String,
    val id: Int,
    val version: Int,
    val name: String,
    val script: String,
) {
    val label: String
        get() = if (key.isBlank()) name else "$name [id=$id, v=$version]"
}

data class DeviceSensorScriptConfig(
    val scriptId: Int,
    val version: Int,
    val name: String,
    val sensorType: String,
    val selectUartI2c: Boolean,
    val selectRs485: Boolean,
    val script: String,
    val globalBufferSize: Int = 4096,
    val mimeType: String = "application/x-lua",
)

object DeviceSensorCatalog {
    private val noneSensor = DeviceSensorDefinition(
        key = "",
        id = 0,
        version = 0,
        name = "None",
        script = "",
    )

    fun sensorDefinitionsFor(context: Context, connector: DeviceSensorConnector): List<DeviceSensorDefinition> {
        val assetDir = "sensors/${connector.assetFolder}"
        val names = context.assets.list(assetDir).orEmpty()
        val definitions = names
            .filter { it.endsWith(".json") }
            .mapNotNull { fileName ->
                runCatching {
                    val raw = context.assets.open("$assetDir/$fileName").bufferedReader().use { it.readText() }
                    val json = JSONObject(raw)
                    val id = json.optInt("id", 0)
                    val version = json.optInt("version", 0)
                    val script = json.optString("script")
                    if (id <= 0 || version <= 0 || script.isBlank()) {
                        null
                    } else {
                        DeviceSensorDefinition(
                            key = fileName.removeSuffix(".json"),
                            id = id,
                            version = version,
                            name = json.optString("name").ifBlank { fileName.removeSuffix(".json") },
                            script = script,
                        )
                    }
                }.getOrNull()
            }
            .sortedWith(compareBy<DeviceSensorDefinition> { it.name.lowercase() }.thenBy { it.key })
        return listOf(noneSensor) + definitions
    }

    fun definitionFor(
        context: Context,
        connector: DeviceSensorConnector,
        key: String,
    ): DeviceSensorDefinition? {
        if (key.isBlank()) return null
        return sensorDefinitionsFor(context, connector).firstOrNull { it.key == key }
    }

    fun labelFor(
        context: Context,
        connector: DeviceSensorConnector,
        key: String,
    ): String {
        return if (key.isBlank()) {
            noneSensor.label
        } else {
            definitionFor(context, connector, key)?.label ?: key
        }
    }

    fun scriptConfigFor(
        context: Context,
        connector: DeviceSensorConnector,
        key: String,
    ): DeviceSensorScriptConfig? {
        val definition = definitionFor(context, connector, key) ?: return null
        return DeviceSensorScriptConfig(
            scriptId = definition.id,
            version = definition.version,
            name = definition.name,
            sensorType = definition.key,
            selectUartI2c = connector == DeviceSensorConnector.UART_I2C,
            selectRs485 = connector == DeviceSensorConnector.RS485,
            script = definition.script,
        )
    }

    fun scriptConfigsFor(
        context: Context,
        uartI2cSensorType: String,
        rs485SensorType: String,
    ): List<DeviceSensorScriptConfig> {
        return listOfNotNull(
            scriptConfigFor(context, DeviceSensorConnector.UART_I2C, uartI2cSensorType),
            scriptConfigFor(context, DeviceSensorConnector.RS485, rs485SensorType),
        )
    }
}

enum class EdgeZDeviceType(val protoValue: Int, val label: String) {
    UNSPECIFIED(0, "Unspecified"),
    UNKNOWN(1, "Unknown"),
    USER(2, "User"),
    GATEWAY(3, "Gateway"),
    BEACON(4, "Beacon"),
    SENSOR(5, "Sensor");

    companion object {
        fun fromProtoValue(value: Int): EdgeZDeviceType {
            return entries.firstOrNull { it.protoValue == value } ?: UNSPECIFIED
        }
    }
}

data class EdgeZSensorData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val pressure: Double? = null,
) {
    val hasAnyValue: Boolean
        get() = latitude != null ||
            longitude != null ||
            altitude != null ||
            temperature != null ||
            humidity != null ||
            pressure != null
}

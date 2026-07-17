package ai.edgez.edgez

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
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
    val image: String = "",
    val description: String = "",
    val purchaseUrl: String = "",
    val globalBufferSize: Int = 4096,
) {
    val label: String
        get() = if (key.isBlank()) name else "$name [id=$id, v=$version]"
}

enum class DeviceSensorScriptAction {
    UPLOAD,
    DELETE,
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
    val action: DeviceSensorScriptAction = DeviceSensorScriptAction.UPLOAD,
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
        val definitions = (readManifestDefinitions(context, connector) + installedMarketplaceDriverDefinitions(context, connector))
            .associateBy { it.key }
            .values
            .sortedWith(compareBy<DeviceSensorDefinition> { it.name.lowercase() }.thenBy { it.key })
        return listOf(noneSensor) + definitions
    }

    private fun readManifestDefinitions(
        context: Context,
        connector: DeviceSensorConnector,
    ): List<DeviceSensorDefinition> {
        return runCatching {
            context.assets.open("sensors/manifest.xml").use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, Charsets.UTF_8.name())
                val definitions = mutableListOf<DeviceSensorDefinition>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "sensor") {
                        parseSensorDefinition(context, connector, parser)?.let { definitions += it }
                    }
                    event = parser.next()
                }
                definitions
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSensorDefinition(
        context: Context,
        connector: DeviceSensorConnector,
        parser: XmlPullParser,
    ): DeviceSensorDefinition? {
        val sensorInterface = parser.getAttributeValue(null, "interface").orEmpty()
        if (sensorInterface != connector.assetFolder) return null

        val id = parser.getAttributeValue(null, "id")?.toIntOrNull() ?: 0
        val version = parser.getAttributeValue(null, "version")?.toIntOrNull() ?: 0
        val key = parser.getAttributeValue(null, "key").orEmpty().ifBlank { "$id-$version" }
        val name = parser.getAttributeValue(null, "name").orEmpty().ifBlank { key }
        val scriptPath = parser.getAttributeValue(null, "script").orEmpty()
        val image = parser.getAttributeValue(null, "image").orEmpty()
        val description = parser.getAttributeValue(null, "description").orEmpty()
        val purchaseUrl = parser.getAttributeValue(null, "purchase_url").orEmpty()
        if (id <= 0 || version <= 0 || scriptPath.isBlank()) return null

        val script = runCatching {
            context.assets.open("sensors/$scriptPath").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (script.isBlank()) return null

        return DeviceSensorDefinition(
            key = key,
            id = id,
            version = version,
            name = name,
            script = script,
            image = image,
            description = description,
            purchaseUrl = purchaseUrl,
        )
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
            globalBufferSize = definition.globalBufferSize,
        )
    }

    private fun deleteScriptConfigFor(
        context: Context,
        connector: DeviceSensorConnector,
        key: String,
    ): DeviceSensorScriptConfig? {
        if (key.isBlank()) return null
        val definition = definitionFor(context, connector, key)
        val scriptId = definition?.id ?: key.substringBefore("-").toIntOrNull() ?: return null
        val version = definition?.version ?: key.substringAfter("-", "0").toIntOrNull().orZero()
        return DeviceSensorScriptConfig(
            scriptId = scriptId,
            version = version,
            name = definition?.name ?: key,
            sensorType = key,
            selectUartI2c = connector == DeviceSensorConnector.UART_I2C,
            selectRs485 = connector == DeviceSensorConnector.RS485,
            script = "",
            action = DeviceSensorScriptAction.DELETE,
        )
    }

    fun scriptConfigsFor(
        context: Context,
        uartI2cSensorType: String,
        rs485SensorType: String,
        previousUartI2cSensorType: String = "",
        previousRs485SensorType: String = "",
    ): List<DeviceSensorScriptConfig> {
        val configs = mutableListOf<DeviceSensorScriptConfig>()
        if (previousUartI2cSensorType.isNotBlank() && uartI2cSensorType.isBlank()) {
            deleteScriptConfigFor(context, DeviceSensorConnector.UART_I2C, previousUartI2cSensorType)?.let { configs += it }
        } else {
            scriptConfigFor(context, DeviceSensorConnector.UART_I2C, uartI2cSensorType)?.let { configs += it }
        }
        if (previousRs485SensorType.isNotBlank() && rs485SensorType.isBlank()) {
            deleteScriptConfigFor(context, DeviceSensorConnector.RS485, previousRs485SensorType)?.let { configs += it }
        } else {
            scriptConfigFor(context, DeviceSensorConnector.RS485, rs485SensorType)?.let { configs += it }
        }
        return configs
    }
}

private fun Int?.orZero(): Int = this ?: 0

enum class EdgeZDeviceType(val protoValue: Int, val label: String) {
    UNSPECIFIED(0, "Unspecified"),
    UNKNOWN(1, "Unknown"),
    USER(2, "User"),
    GATEWAY(3, "Gateway"),
    BEACON(4, "Beacon"),
    SENSOR(5, "Sensor"),
    RELAY(6, "Relay"),
    GROUP(100, "Group");

    companion object {
        fun fromProtoValue(value: Int): EdgeZDeviceType {
            return entries.firstOrNull { it.protoValue == value } ?: UNSPECIFIED
        }
    }
}

val EdgeZDeviceType.isDeviceProfile: Boolean
    get() = this == EdgeZDeviceType.BEACON || this == EdgeZDeviceType.SENSOR

data class EdgeZSensorData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val pressure: Double? = null,
    val vibrationAverage: Double? = null,
    val binaryLengthBytes: Int? = null,
    val binaryImagePath: String? = null,
) {
    val hasAnyValue: Boolean
        get() = latitude != null ||
            longitude != null ||
            altitude != null ||
            temperature != null ||
            humidity != null ||
            pressure != null ||
            vibrationAverage != null ||
            binaryLengthBytes != null ||
            binaryImagePath != null
}

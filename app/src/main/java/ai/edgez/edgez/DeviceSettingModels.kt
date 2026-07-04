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
    GPS(7, "GPS"),
    SHT3X_TEMP_HUMIDTY(8, "SHT3x temperature + humidity");

    companion object {
        fun fromProtoValue(value: Int): DeviceSensorType {
            return entries.firstOrNull { it.protoValue == value } ?: UNSPECIFIED
        }

        fun fromName(name: String?): DeviceSensorType {
            return entries.firstOrNull { it.name == name } ?: UNSPECIFIED
        }
    }
}

enum class DeviceSensorConnector {
    UART_I2C,
    RS485,
}

data class DeviceSensorScriptConfig(
    val scriptId: Int,
    val version: Int,
    val name: String,
    val sensorType: DeviceSensorType,
    val selectUartI2c: Boolean,
    val selectRs485: Boolean,
    val script: String,
    val globalBufferSize: Int = 4096,
    val mimeType: String = "application/x-lua",
)

object DeviceSensorCatalog {
    private val baseSensorTypes = listOf(
        DeviceSensorType.UNSPECIFIED,
        DeviceSensorType.NONE,
        DeviceSensorType.TEMPERATURE,
        DeviceSensorType.HUMIDITY,
        DeviceSensorType.PRESSURE,
        DeviceSensorType.TEMPERATURE_HUMIDITY,
        DeviceSensorType.TEMPERATURE_HUMIDITY_PRESSURE,
        DeviceSensorType.GPS,
    )

    fun sensorTypesFor(connector: DeviceSensorConnector): List<DeviceSensorType> {
        return when (connector) {
            DeviceSensorConnector.UART_I2C -> baseSensorTypes + DeviceSensorType.SHT3X_TEMP_HUMIDTY
            DeviceSensorConnector.RS485 -> baseSensorTypes
        }
    }

    fun scriptConfigFor(connector: DeviceSensorConnector, sensorType: DeviceSensorType): DeviceSensorScriptConfig? {
        if (connector != DeviceSensorConnector.UART_I2C || sensorType != DeviceSensorType.SHT3X_TEMP_HUMIDTY) {
            return null
        }
        return DeviceSensorScriptConfig(
            scriptId = 1002,
            version = 1,
            name = "SHT3x Temperature/Humidity",
            sensorType = sensorType,
            selectUartI2c = true,
            selectRs485 = false,
            script = SHT3X_SCRIPT,
        )
    }

    fun scriptConfigsFor(
        uartI2cSensorType: DeviceSensorType,
        rs485SensorType: DeviceSensorType,
    ): List<DeviceSensorScriptConfig> {
        return listOfNotNull(
            scriptConfigFor(DeviceSensorConnector.UART_I2C, uartI2cSensorType),
            scriptConfigFor(DeviceSensorConnector.RS485, rs485SensorType),
        )
    }
}

private const val SHT3X_SCRIPT = """local result = {}

local i2c_address = 0x44
local i2c_rx_size = 6
local i2c_read_delay = 0.5
local i2c_repeatability = "high"

local i2c_temp_object = 3303
local i2c_temp_resource = 5700
local i2c_humidity_object = 3304
local i2c_humidity_resource = 5700

local i2c_log_cfg = { quiet = false }

local SHT3X_CMD_RESET = string.char(0x30, 0xA2)
local SHT3X_CMD_SINGLE_SHOT_HIGH = string.char(0x2C, 0x06)
local SHT3X_CMD_SINGLE_SHOT_MED  = string.char(0x2C, 0x0D)
local SHT3X_CMD_SINGLE_SHOT_LOW  = string.char(0x2C, 0x10)

local function sht3x_read_values()
  local ok, err = i2c_reset_rx_cursor()
  if not ok then return nil, "failed to reset rx cursor: " .. tostring(err) end

  ok, err = i2c_write(SHT3X_CMD_RESET)
  if not ok then return nil, "failed to write reset command: " .. tostring(err) end

  i2c_sleep(0.002)

  local cmd
  if i2c_repeatability == "high" then
    cmd = SHT3X_CMD_SINGLE_SHOT_HIGH
  elseif i2c_repeatability == "med" then
    cmd = SHT3X_CMD_SINGLE_SHOT_MED
  else
    cmd = SHT3X_CMD_SINGLE_SHOT_LOW
  end

  ok, err = i2c_set_rx_size(i2c_rx_size)
  if not ok then return nil, "failed to set rx size: " .. tostring(err) end

  ok, err = i2c_reset_rx_cursor()
  if not ok then return nil, "failed to reset rx cursor: " .. tostring(err) end

  ok, err = i2c_write(cmd)
  if not ok then return nil, "failed to write measurement command: " .. tostring(err) end

  i2c_sleep(i2c_read_delay)

  ok, err = i2c_reset_rx_cursor()
  if not ok then return nil, "failed to reset rx cursor before read: " .. tostring(err) end

  ok, err = i2c_set_rx_size(i2c_rx_size)
  if not ok then return nil, "failed to set rx size before read: " .. tostring(err) end

  local data = i2c_read_chunk()
  if not data or #data < 6 then
    return nil, "incomplete data received (got " .. tostring(data and #data or 0) .. " bytes)"
  end

  util_log(i2c_log_cfg, "SHT3x", "RX: " .. util_bytes_to_hex(data))

  local temp_hi = string.byte(data, 1)
  local temp_lo = string.byte(data, 2)
  local temp_crc = string.byte(data, 3)
  local hum_hi = string.byte(data, 4)
  local hum_lo = string.byte(data, 5)
  local hum_crc = string.byte(data, 6)

  local temp_raw = (temp_hi << 8) | temp_lo
  local hum_raw = (hum_hi << 8) | hum_lo

  local temp_crc_valid = util_crc8(data:sub(1, 2)) == temp_crc
  local hum_crc_valid = util_crc8(data:sub(4, 5)) == hum_crc

  local temperature = nil
  local humidity = nil

  if temp_crc_valid then
    temperature = -45 + (175 * temp_raw / 65535.0)
  else
    util_log(i2c_log_cfg, "SHT3x", "Temperature CRC check failed")
  end

  if hum_crc_valid then
    humidity = 100 * hum_raw / 65535.0
  else
    util_log(i2c_log_cfg, "SHT3x", "Humidity CRC check failed")
  end

  return {
    temperature = temperature,
    humidity = humidity,
    temp_raw = temp_raw,
    hum_raw = hum_raw,
    temp_crc_valid = temp_crc_valid,
    hum_crc_valid = hum_crc_valid,
  }
end

local i2c_ok, i2c_err = i2c_connect(i2c_address)
if not i2c_ok then
  i2c_safe_close()
  error("failed to open i2c: " .. tostring(i2c_err))
end

local i2c_result, i2c_read_err = sht3x_read_values()
i2c_safe_close()
if not i2c_result then
  error(i2c_read_err)
end

if i2c_result.temperature ~= nil then
  table.insert(result, {
    object = i2c_temp_object,
    instance = 0,
    resource = i2c_temp_resource,
    value = i2c_result.temperature,
  })
end

if i2c_result.humidity ~= nil then
  table.insert(result, {
    object = i2c_humidity_object,
    instance = 0,
    resource = i2c_humidity_resource,
    value = i2c_result.humidity,
  })
end

return result
"""

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

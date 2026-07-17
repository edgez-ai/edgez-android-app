local result = {}
local SENSOR_TEMPERATURE = 1
local SENSOR_HUMIDITY = 2

local i2c_address = 0x44
local i2c_rx_size = 6
local i2c_read_delay = 0.5
local i2c_repeatability = "high"

local i2c_log_cfg = { quiet = false }

-- SHT3x commands
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
    type = SENSOR_TEMPERATURE,
    float_value = i2c_result.temperature,
  })
end

if i2c_result.humidity ~= nil then
  table.insert(result, {
    type = SENSOR_HUMIDITY,
    float_value = i2c_result.humidity,
  })
end

return result

local result = {}

local cfg = {
  baud = tonumber(rawget(_G, "RS485_BAUD")) or 9600,
  unit_id = tonumber(rawget(_G, "RS485_UNIT_ID")) or 0x50,
  modbus_timeout = tonumber(rawget(_G, "RS485_MODBUS_TIMEOUT")) or 1.0,
  quiet = rawget(_G, "VIBRATION_QUIET") == true,
}

local VIBRATION_OBJECT = tonumber(rawget(_G, "VIBRATION_LWM2M_OBJECT")) or 0
local VIBRATION_RESOURCE = tonumber(rawget(_G, "VIBRATION_LWM2M_RESOURCE")) or 10
local VIBRATION_INSTANCE = tonumber(rawget(_G, "VIBRATION_LWM2M_INSTANCE")) or 0
local ACCEL_START = 0x34
local ACCEL_COUNT = 3
local SAMPLE_HZ = tonumber(rawget(_G, "VIBRATION_SAMPLE_HZ")) or 100
local SAMPLE_SECONDS = tonumber(rawget(_G, "VIBRATION_SAMPLE_SECONDS")) or 1
local SAMPLE_INTERVAL = 1.0 / SAMPLE_HZ
local SAMPLE_COUNT = SAMPLE_HZ * SAMPLE_SECONDS

local function log(msg)
  util_log({ quiet = cfg.quiet }, "Vibration", msg)
end

local function sleep_seconds(seconds)
  if seconds == nil or seconds <= 0 then
    return
  end
  if type(rs485_sleep) == "function" then
    rs485_sleep(seconds)
    return
  end
  local deadline = os.clock() + seconds
  while os.clock() < deadline do
  end
end

local function to_signed16(v)
  if (v & 0x8000) ~= 0 then
    return v - 0x10000
  end
  return v
end

local function read_holding_registers(address, count)
  local request = util_build_read_holding_request(cfg.unit_id, address, count)
  log("TX Read Holding Registers: " .. util_bytes_to_hex(request))

  local ok, err = rs485_reset_rx_cursor()
  if not ok then
    return nil, "failed to reset rx cursor: " .. tostring(err)
  end

  ok, err = rs485_write(request)
  if not ok then
    return nil, "failed to write tx payload: " .. tostring(err)
  end

  local byte_count = count * 2
  local deadline = os.clock() + cfg.modbus_timeout
  local buffer = ""

  while os.clock() < deadline do
    local chunk = rs485_read_chunk()
    if chunk and #chunk > 0 then
      buffer = buffer .. chunk
      local frame = util_extract_modbus_frame(buffer, cfg.unit_id, 0x03, byte_count)
      if frame then
        local payload = frame:sub(4, -3)
        local regs = {}
        for i = 1, #payload, 2 do
          local hi = string.byte(payload, i)
          local lo = string.byte(payload, i + 1)
          regs[#regs + 1] = (hi << 8) | lo
        end
        return regs
      end
    end
    sleep_seconds(0.002)
  end

  return nil, "no valid Modbus response frame received"
end

local function acceleration_g(reg)
  return to_signed16(reg) / 32768.0 * 16.0
end

local function read_vibration_magnitude_g()
  local regs, err = read_holding_registers(ACCEL_START, ACCEL_COUNT)
  if not regs then
    return nil, err
  end

  local ax = acceleration_g(regs[1])
  local ay = acceleration_g(regs[2])
  local az = acceleration_g(regs[3])
  return math.sqrt((ax * ax) + (ay * ay) + (az * az))
end

local ok, err = rs485_connect(cfg.baud)
if not ok then
  rs485_safe_close()
  error("failed to open rs485: " .. tostring(err))
end

local sum = 0
for sample = 1, SAMPLE_COUNT do
  local cycle_start = os.clock()
  local magnitude, read_err = read_vibration_magnitude_g()
  if magnitude == nil then
    rs485_safe_close()
    error("vibration read failed at sample " .. tostring(sample) .. ": " .. tostring(read_err))
  end
  sum = sum + magnitude

  local sleep_time = SAMPLE_INTERVAL - (os.clock() - cycle_start)
  if sample < SAMPLE_COUNT and sleep_time > 0 then
    sleep_seconds(sleep_time)
  end
end

rs485_safe_close()

table.insert(result, {
  object = VIBRATION_OBJECT,
  instance = VIBRATION_INSTANCE,
  resource = VIBRATION_RESOURCE,
  value = sum / SAMPLE_COUNT,
})

return result

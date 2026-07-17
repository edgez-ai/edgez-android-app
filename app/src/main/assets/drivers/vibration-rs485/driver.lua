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
local FEATURE_START = 0x3A
local FEATURE_END = 0x6A
local FEATURE_COUNT = FEATURE_END - FEATURE_START + 1
local SAMPLE_COUNT = tonumber(rawget(_G, "VIBRATION_SCORE_SAMPLES")) or 5
local SAMPLE_INTERVAL = tonumber(rawget(_G, "VIBRATION_SCORE_INTERVAL")) or 0.2
local VX_SCALE = tonumber(rawget(_G, "VIBRATION_VELOCITY_SCALE")) or 100.0
local VRMS_SCALE = tonumber(rawget(_G, "VIBRATION_VRMS_SCALE")) or 100.0
local DRMS_SCALE = tonumber(rawget(_G, "VIBRATION_DRMS_SCALE")) or 100.0
local INSTANT_VELOCITY_WEIGHT = tonumber(rawget(_G, "VIBRATION_INSTANT_VELOCITY_WEIGHT")) or 0.35
local RMS_VELOCITY_WEIGHT = tonumber(rawget(_G, "VIBRATION_RMS_VELOCITY_WEIGHT")) or 0.50
local RMS_DISPLACEMENT_WEIGHT = tonumber(rawget(_G, "VIBRATION_RMS_DISPLACEMENT_WEIGHT")) or 0.15

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

local function reg_at(regs, address)
  return regs[address - FEATURE_START + 1] or 0
end

local function vector_magnitude(x, y, z)
  return math.sqrt((x * x) + (y * y) + (z * z))
end

local function decode_velocity(raw)
  return raw / VX_SCALE
end

local function decode_vrms(raw)
  return raw / VRMS_SCALE
end

local function decode_drms(raw)
  return raw / DRMS_SCALE
end

local function read_passby_score()
  local regs, err = read_holding_registers(FEATURE_START, FEATURE_COUNT)
  if not regs then
    return nil, err
  end

  local vx_mag = vector_magnitude(
    decode_velocity(reg_at(regs, 0x3A)),
    decode_velocity(reg_at(regs, 0x3B)),
    decode_velocity(reg_at(regs, 0x3C))
  )
  local vrms_mag = vector_magnitude(
    decode_vrms(reg_at(regs, 0x50)),
    decode_vrms(reg_at(regs, 0x5C)),
    decode_vrms(reg_at(regs, 0x68))
  )
  local drms_mag = vector_magnitude(
    decode_drms(reg_at(regs, 0x52)),
    decode_drms(reg_at(regs, 0x5E)),
    decode_drms(reg_at(regs, 0x6A))
  )

  return (INSTANT_VELOCITY_WEIGHT * vx_mag) +
    (RMS_VELOCITY_WEIGHT * vrms_mag) +
    (RMS_DISPLACEMENT_WEIGHT * drms_mag)
end

local ok, err = rs485_connect(cfg.baud)
if not ok then
  rs485_safe_close()
  error("failed to open rs485: " .. tostring(err))
end

local sum = 0
for sample = 1, SAMPLE_COUNT do
  local cycle_start = os.clock()
  local score, read_err = read_passby_score()
  if score == nil then
    rs485_safe_close()
    error("vibration passby score read failed at sample " .. tostring(sample) .. ": " .. tostring(read_err))
  end
  sum = sum + score

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

local result = {}
local SENSOR_TEMPERATURE = 1

math.randomseed((os.time() % 100000) + math.floor((os.clock() or 0) * 1000))

local value = math.random(180, 320) / 10

table.insert(result, {
  type = SENSOR_TEMPERATURE,
  float_value = value,
})

return result

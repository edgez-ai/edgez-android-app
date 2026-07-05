local result = {}

math.randomseed((os.time() % 100000) + math.floor((os.clock() or 0) * 1000))

local value = math.random(180, 320) / 10

table.insert(result, {
  object = 3303,
  instance = 0,
  resource = 5700,
  value = value,
})

return result

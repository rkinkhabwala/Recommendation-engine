-- Idempotent compare-and-set upsert.
-- KEYS[1] = feature key, KEYS[2] (optional) = user deletion marker (same hash slot)
-- ARGV[1] = seq, ARGV[2] = data, ARGV[3] = ttl seconds (0 = none)
-- Returns 1 applied, 0 stale (stored seq >= incoming), -1 user deleted.
if #KEYS == 2 and redis.call('EXISTS', KEYS[2]) == 1 then
  return -1
end
local current = redis.call('HGET', KEYS[1], 'seq')
if current and tonumber(current) >= tonumber(ARGV[1]) then
  return 0
end
redis.call('HSET', KEYS[1], 'seq', ARGV[1], 'data', ARGV[2])
local ttl = tonumber(ARGV[3])
if ttl > 0 then
  redis.call('EXPIRE', KEYS[1], ttl)
end
return 1

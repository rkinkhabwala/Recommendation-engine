-- Atomically deletes all of a user's keys and sets the deletion marker.
-- KEYS[1] = marker, KEYS[2..n] = user keys (all share the {userId} hash tag)
-- ARGV[1] = marker ttl seconds
for i = 2, #KEYS do
  redis.call('DEL', KEYS[i])
end
redis.call('SET', KEYS[1], '1', 'EX', tonumber(ARGV[1]))
return #KEYS - 1

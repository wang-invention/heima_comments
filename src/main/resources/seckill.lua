-- 秒杀券ID
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 订单ID
local orderId = ARGV[3]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存是否充足（修复 nil 问题）
local stock = redis.call('get', stockKey)
if (stock == nil or tonumber(stock) <= 0) then
    return 1
end

-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 扣库存
redis.call('decr', stockKey)
-- 下单
redis.call('sadd', orderKey, userId)
-- 发送消息
redis.call('xadd', 'streams:orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0
# Phase 6 — Caching (Tasks 28–30)
**Estimated Time:** 3 hours | **Status:** ⬜ Not Started

## Core Annotations
```java
@Cacheable(value = "orders", key = "#id", unless = "#result == null")
public OrderResponse getOrderById(Long id) { ... } // Cache hit → skip method

@CacheEvict(value = "orders", key = "#id")
public OrderResponse updateOrder(Long id, OrderRequest req) { ... }

@CachePut(value = "orders", key = "#result.id")
public OrderResponse createOrder(OrderRequest req) { ... } // Always executes, updates cache

@Caching(evict = {
    @CacheEvict(value = "orders", key = "#id"),
    @CacheEvict(value = "orderSummaries", allEntries = true)
})
public void deleteOrder(Long id) { ... }
```

## Redis Cache Manager
```java
@Configuration @EnableCaching
public class RedisCacheConfig {
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .withCacheConfiguration("orders", config.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("products", config.entryTtl(Duration.ofHours(1)))
                .build();
    }
}
```

## Multi-Level Cache (L1: Caffeine + L2: Redis)
```
Request → L1 Caffeine hit → Return (sub-ms)
        → L1 miss → L2 Redis hit → Promote to L1 → Return (~1ms)
        → L2 miss → DB → Store in Redis + Caffeine → Return
```

```java
public OrderResponse getOrder(Long id) {
    // L1: Check Caffeine
    OrderResponse cached = caffeineCache.getIfPresent(id);
    if (cached != null) return cached;
    // L2: Check Redis
    OrderResponse redisCached = (OrderResponse) redisTemplate.opsForValue().get("orders:" + id);
    if (redisCached != null) { caffeineCache.put(id, redisCached); return redisCached; }
    // DB
    OrderResponse order = loadFromDb(id);
    redisTemplate.opsForValue().set("orders:" + id, order, Duration.ofMinutes(30));
    caffeineCache.put(id, order);
    return order;
}
```

## Distributed Lock with Redis
```java
public boolean tryLock(String lockKey, Duration ttl) {
    return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", ttl));
}
public void unlock(String lockKey) { redisTemplate.delete(lockKey); }
```

## Interview Q&A
- **@Cacheable vs @CachePut?** @Cacheable: cache hit → skip method. @CachePut: always execute → update cache. Use @CachePut for writes (always need freshness).
- **Caffeine vs Redis?** Caffeine: local, sub-ms, lost on restart. Redis: distributed, shared across instances, persistent. Multi-level = fastest + shared.
- **Cache stampede?** Many simultaneous misses flood DB. Solution: lock (one populates, others wait), early expiration (refresh before TTL expires).
- **Cache penetration?** Requests for non-existent keys bypass cache every time. Solution: cache null with short TTL, bloom filter.
- **TTL strategy?** Short (seconds): inventory/prices. Medium (minutes): product listings, user data. Long (hours): reference data (currencies, categories). Always evict on write.

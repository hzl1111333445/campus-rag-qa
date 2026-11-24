package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 缓存门面：问答结果、embedding、限流计数共用这一层。
 *
 * <p><b>这个类的第一设计目标是"挂掉不影响主链路"，不是"快"</b>：
 * <ul>
 *   <li>所有 Redis 操作都包 try/catch，异常只记日志并返回"未命中"</li>
 *   <li>Redis 超时设得很短（yml 里 800ms），比业务超时小一个量级</li>
 *   <li>{@code campus.cache.enabled=false} 时整层直通，等于没有缓存</li>
 * </ul>
 * 缓存是"加速器"，绝不能变成单点故障。
 *
 * <p><b>为什么不用 Spring 的 {@code @Cacheable}</b>：注解式缓存会把键的拼装藏进 SpEL，
 * 键里漏因子时很难发现；而且我们要的"命中率统计 + 按前缀清理 + 限流计数"注解都不好表达。
 * 手写一层 60 行，逻辑全在眼前。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int qaTtlSeconds;
    private final int qaTtlJitterSeconds;
    private final int embeddingTtlSeconds;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public CacheService(StringRedisTemplate redis,
                        @Value("${campus.cache.enabled:true}") boolean enabled,
                        @Value("${campus.cache.qa-ttl-seconds:3600}") int qaTtlSeconds,
                        @Value("${campus.cache.qa-ttl-jitter-seconds:300}") int qaTtlJitterSeconds,
                        @Value("${campus.cache.embedding-ttl-seconds:604800}") int embeddingTtlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.qaTtlSeconds = qaTtlSeconds;
        this.qaTtlJitterSeconds = Math.max(0, qaTtlJitterSeconds);
        this.embeddingTtlSeconds = embeddingTtlSeconds;
        log.info("缓存{}：redis={}", enabled ? "已启用" : "已关闭",
                enabled ? "见 spring.data.redis" : "（CACHE_ENABLED=false）");
    }

    /** 缓存总开关（压测、评测时要能一键关掉，否则延迟数字被缓冲污染）。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 问答结果的有效期：基础 TTL 上叠一个随机抖动。
     *
     * <p>为什么要抖动：如果一批问题在同一时刻写入、又用同样的 TTL，它们会在同一秒集体过期，
     * 然后同时打到模型接口上——这就是"缓存雪崩"。抖动把过期时间摊平到 5 分钟内。
     */
    public Duration qaTtl() {
        int base = Math.max(60, qaTtlSeconds);
        int jitter = qaTtlJitterSeconds == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(qaTtlJitterSeconds + 1);
        return Duration.ofSeconds(base + jitter);
    }

    /** embedding 有效期（向量基本不变，给 7 天）。 */
    public Duration embeddingTtl() {
        return Duration.ofSeconds(Math.max(60, embeddingTtlSeconds));
    }

    /**
     * 读字符串。未命中、关闭、异常都返回 {@code null} —— 调用方不需要区分"没有"和"坏了"。
     */
    public String get(String key) {
        if (!enabled) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                misses.incrementAndGet();
            } else {
                hits.incrementAndGet();
            }
            return value;
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("缓存读取失败，按未命中处理：key={} err={}", key, e.getMessage());
            return null;
        }
    }

    /** 写字符串。失败只记日志。 */
    public void put(String key, String value, Duration ttl) {
        if (!enabled || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
            writes.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("缓存写入失败，忽略：key={} err={}", key, e.getMessage());
        }
    }

    /**
     * 按前缀批量删除（用 SCAN，不用 KEYS）。
     *
     * <p>为什么必须 SCAN：{@code KEYS pattern} 会遍历整个库并阻塞 Redis 主线程，
     * 生产上属于"禁止操作"。SCAN 是游标式分批遍历，代价可控。
     *
     * @return 删掉的键数量
     */
    public int deleteByPrefix(String prefix) {
        if (!enabled) {
            return 0;
        }
        int deleted = 0;
        try {
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(200).build();
            List<String> batch = new ArrayList<>(200);
            try (Cursor<byte[]> cursor = redis.execute(connection -> connection.scan(options),
                    true)) {
                if (cursor == null) {
                    return 0;
                }
                while (cursor.hasNext()) {
                    batch.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    if (batch.size() >= 200) {
                        deleted += deleteBatch(batch);
                        batch.clear();
                    }
                }
            }
            deleted += deleteBatch(batch);
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("按前缀清理缓存失败：prefix={} err={}", prefix, e.getMessage());
        }
        if (deleted > 0) {
            log.info("清理缓存 {} 个：prefix={}", deleted, prefix);
        }
        return deleted;
    }

    private int deleteBatch(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        return removed == null ? 0 : removed.intValue();
    }

    /**
     * 计数并返回当前窗口内的次数（用于限流）。
     *
     * <p>第一次自增时设置过期时间 —— 这样不需要定时任务清理过期键。
     * 注意：先 INCR 再判断是否为 1 才 EXPIRE，这两步不是原子的（极端情况下进程在中间挂掉，
     * 这个键就永不过期）。家用/答辩场景可接受；生产应该用 Lua 脚本或 {@code SET NX EX} 组合。
     *
     * @return 本次自增后的计数；Redis 不可用时返回 0（调用方按"不限流"处理）
     */
    public long incrementWithWindow(String key, Duration window) {
        if (!enabled) {
            return 0;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window);
            }
            return count == null ? 0 : count;
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("限流计数失败，放行本次请求：key={} err={}", key, e.getMessage());
            return 0;
        }
    }

    /** 探活：给监控/自检页用。 */
    public boolean reachable() {
        if (!enabled) {
            return false;
        }
        try {
            String pong = redis.getConnectionFactory() == null ? null
                    : redis.execute(connection -> connection.ping(), true);
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }

    /** 命中率快照，给 {@code /api/dev/cache/stats} 和日志用。 */
    public Stats stats() {
        return new Stats(enabled, reachable(), hits.get(), misses.get(), writes.get(), errors.get());
    }

    /** 重置统计（压测前调用，保证数字干净）。 */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
        writes.set(0);
        errors.set(0);
    }

    /** 统计快照。hitRate 是"命中 / (命中 + 未命中)"，分母为 0 时给 0。 */
    public record Stats(boolean enabled, boolean reachable,
                        long hits, long misses, long writes, long errors) {
        public long total() {
            return hits + misses;
        }

        public double hitRate() {
            long total = total();
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}

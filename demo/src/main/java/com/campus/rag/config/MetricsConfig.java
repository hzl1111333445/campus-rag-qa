package com.campus.rag.config;

import com.campus.rag.service.DocumentDao;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * 指标注册（Gauge 部分）。
 *
 * <p>三个数决定"知识库有多大、能不能用"：
 * <ul>
 *   <li>{@code campus_documents_total} 文档数</li>
 *   <li>{@code campus_chunks_total} 块数</li>
 *   <li>{@code campus_chunks_embedded_total} 已向量化块数（和块数的差就是"搜不到"的量）</li>
 * </ul>
 *
 * <p><b>命名坑（实测）</b>：Gauge 名字不要以 {@code _total} 结尾——
 * Prometheus 把 {@code _total} 视为 Counter 的保留后缀，导出时会把 Gauge 的
 * {@code campus_documents_total} 自动改写成 {@code campus_documents}，
 * 于是你按原名去 grep 会发现"指标没出来"，其实是换了名字。所以这里用 {@code campus_kb_*}。
 *
 * <p><b>为什么不直接在抓取时查库</b>：Prometheus 默认 15 秒抓一次，
 * 每次都跑 count(*) 在大表上会变成隐性压力。这里用 {@link CachedNumber} 做 30 秒缓存：
 * 抓取只读内存，过期后由当次抓取顺带刷新。
 *
 * <p>HTTP 的 QPS / P95 / 错误率由 Actuator 自动生成的
 * {@code http_server_requests_seconds} 提供，无需自己埋点。
 */
@Configuration
public class MetricsConfig {

    /**
     * <b>必须用字段持有这些数字来源</b>：Micrometer 的 Gauge 对被观测对象是
     * <i>弱引用</i>，如果只写成 {@code Gauge.builder(name, new CachedNumber(...))}，
     * 对象很快被 GC，Gauge 就"人间蒸发"——表现是 /actuator/prometheus 里少了几行，且不报错。
     * 这个坑很隐蔽（我第一次就是这么写的，指标一条都没出来）。
     */
    private final CachedNumber documents;
    private final CachedNumber chunks;
    private final CachedNumber embedded;

    public MetricsConfig(MeterRegistry registry, DocumentDao documentDao) {
        this.documents = new CachedNumber(documentDao::count);
        this.chunks = new CachedNumber(documentDao::countChunks);
        this.embedded = new CachedNumber(documentDao::countEmbeddedChunks);

        Gauge.builder("campus_kb_documents", documents).description("库内已入库文档数").register(registry);
        Gauge.builder("campus_kb_chunks", chunks).description("库内块数").register(registry);
        Gauge.builder("campus_kb_chunks_embedded", embedded).description("已向量化的块数").register(registry);
    }

    /** 带 30 秒缓存的数字来源：抓取只读内存，过期才查库。 */
    static final class CachedNumber implements Supplier<Number> {

        private static final long TTL_MS = 30_000L;

        private final Supplier<Long> loader;
        private volatile long value = 0L;
        private volatile long loadedAt = 0L;

        CachedNumber(Supplier<Long> loader) {
            this.loader = loader;
        }

        @Override
        public Number get() {
            long now = System.currentTimeMillis();
            if (now - loadedAt > TTL_MS) {
                synchronized (this) {
                    if (now - loadedAt > TTL_MS) {
                        try {
                            value = loader.get();
                            loadedAt = now;
                        } catch (RuntimeException e) {
                            // 指标不能影响业务，也不能因为查库失败把 /actuator 打挂：保留上一次的值
                            loadedAt = now;
                        }
                    }
                }
            }
            return value;
        }
    }
}

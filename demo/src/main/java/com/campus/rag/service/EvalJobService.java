package com.campus.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评测任务的后台执行器。
 *
 * <p><b>为什么要有这一层？</b>一轮评测是 118 道题 × 13 种配置，要几分钟。
 * 如果接口同步跑完再返回：
 * <ul>
 *   <li>浏览器 axios 60 秒就超时了（我们前端配的就是 60s）；</li>
 *   <li>中间没有任何反馈，用的人不知道是"在算"还是"卡死"；</li>
 *   <li>Tomcat 的工作线程被一个几分钟的请求占着，并发跑几轮就把线程池吃光。</li>
 * </ul>
 * 所以改成"提交任务 → 拿 jobId → 轮询进度"：接口立刻返回，实际计算在单独的线程里跑。
 *
 * <p>为什么不用数据库存任务？因为这是"当场跑一次看看"的临时任务，进程重启就没了也不可惜；
 * 真正要留档的是评测结果，那个已经落在 {@code docs/eval-runs/} 的 tsv 里。
 */
@Service
public class EvalJobService {

    private static final Logger log = LoggerFactory.getLogger(EvalJobService.class);

    /** 最多保留多少个历史任务（含正在跑的），超出就淘汰最老的 */
    private static final int KEEP = 5;

    private final EvalService evalService;
    /** 单线程：评测本身很吃 CPU 和外部接口，同一时刻只允许跑一轮，避免互相拖慢 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();

    public EvalJobService(EvalService evalService) {
        this.evalService = evalService;
    }

    /** 任务快照（返回给前端看的字段，故意不含 Report 以外的大对象） */
    public record Snapshot(String id, String status, int topK, int total, int done,
                           long elapsedMs, String error, EvalService.Report report) {
    }

    private static final class Job {
        final String id;
        final int topK;
        volatile String status = "RUNNING";
        final int total;
        final AtomicInteger done = new AtomicInteger();
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt = 0;
        volatile String error;
        volatile EvalService.Report report;

        Job(String id, int topK, int total) {
            this.id = id;
            this.topK = topK;
            this.total = total;
        }
    }

    /**
     * 提交一轮评测（立即返回 jobId）。
     *
     * @param totalQuestions 题量，用来算进度百分比（"无答案"题也计入，反正很快）
     */
    public String submit(int topK, int totalQuestions) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Job job = new Job(id, topK, totalQuestions);
        jobs.put(id, job);
        synchronized (order) {
            order.addLast(id);
            while (order.size() > KEEP) {
                String oldest = order.pollFirst();
                if (oldest != null) {
                    jobs.remove(oldest);
                }
            }
        }
        executor.submit(() -> {
            try {
                EvalService.Report report = evalService.run(topK, n -> job.done.set(n));
                job.report = report;
                job.status = "FINISHED";
                log.info("评测任务 {} 完成：{} 题，耗时 {}ms", id, report.questionCount(), report.elapsedMs());
            } catch (RuntimeException e) {
                job.error = e.getMessage();
                job.status = "FAILED";
                log.warn("评测任务 {} 失败：{}", id, e.getMessage());
            } finally {
                job.finishedAt = System.currentTimeMillis();
            }
        });
        return id;
    }

    /** 查询任务；不存在返回 null */
    public Snapshot get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return null;
        }
        long elapsed = (job.finishedAt > 0 ? job.finishedAt : System.currentTimeMillis()) - job.startedAt;
        return new Snapshot(job.id, job.status, job.topK, job.total, job.done.get(),
                elapsed, job.error, job.report);
    }

    /** 最近的任务列表，便于前端刷新后找回正在跑的那一轮 */
    public Map<String, Object> list() {
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (order) {
            m.put("jobs", order.stream().map(this::get).filter(s -> s != null).toList());
        }
        return m;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}

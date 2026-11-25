# -*- coding: utf-8 -*-
"""压测脚本：固定并发 + 固定时长，输出 QPS / 延迟分位 / 错误分布。

为什么自己写而不是用 ab/jmeter：
- 需要给每个请求轮换问题（否则全部命中缓存，测出来的是 Redis 不是系统）
- 需要区分"429 限流"和"真错误"
- 需要顺带把 /actuator/prometheus 的指标快照带回来

用法：
  python tools/loadtest.py --url http://localhost:8081 --path /api/search \
      --params "q={q}&topK=3&mode=hybrid" --concurrency 5 --duration 20 --token <ADMIN>
"""
import argparse
import io
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

QUESTIONS = [
    "旷课多少学时会被处分？",
    "宿舍楼晚上几点关门？",
    "转专业一年可以申请几次？",
    "国家助学金一年多少钱？",
    "考试作弊会怎么处理？",
    "请假三天以上找谁批？",
    "图书馆借的书丢了怎么赔？",
    "学业预警会通知家长吗？",
    "重修要交钱吗？",
    "学生证丢了怎么补办？",
    "奖学金评定看哪些条件？",
    "宿舍能用多大功率的电器？",
    "退伍复学手续怎么办？",
    "申诉要在几天内提出？",
    "考试没过能补考几次？",
]


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round(p / 100.0 * (len(ordered) - 1)))))
    return ordered[k]


def one_request(base, path, params_template, body_template, token, idx, timeout, results, lock,
                unique_suffix=None, persist=False):
    q = QUESTIONS[idx % len(QUESTIONS)]
    if unique_suffix is not None:
        # 压测要打穿缓存：每次加一个不可复用的后缀（保留原句主体，检索仍能命中）
        q = q + "（" + unique_suffix + "）"
    url = base + path
    data = None
    if params_template:
        url = url + "?" + params_template.replace("{q}", urllib.parse.quote(q))
    if body_template:
        body = body_template.replace("{q}", q)
        data = body.encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    # 默认带 X-No-Persist：压测不该往对话库里写数据；
    # 但注意——带这个头也会跳过限流计数（见 RateLimitInterceptor），
    # 想验证 429 就要用 --persist 关掉它。
    if not persist:
        req.add_header("X-No-Persist", "1")

    start = time.perf_counter()
    status, err = 0, ""
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.status
            resp.read()
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            err = e.read().decode("utf-8", "ignore")[:120]
        except Exception:
            err = ""
    except Exception as e:  # 超时/连接断
        status = -1
        err = type(e).__name__
    elapsed = (time.perf_counter() - start) * 1000.0
    with lock:
        results.append((status, elapsed, err))


def prometheus_snapshot(base, token):
    req = urllib.request.Request(base + "/actuator/prometheus")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", "ignore")
    except Exception as e:
        return f"(拉指标失败: {e})"
    keep = ("campus_qa_requests_total", "campus_qa_latency_seconds_count",
            "campus_cache_access_total", "http_server_requests_seconds_count",
            "campus_rerank_fallback_total", "campus_kb_documents")
    return "\n".join(ln for ln in text.splitlines()
                     if ln.startswith(keep) and not ln.startswith("#"))


def run(base, path, params, body, token, concurrency, duration, timeout, unique=False, persist=False):
    results, lock = [], threading.Lock()
    deadline = time.time() + duration
    idx_counter = [0]

    def worker():
        while time.time() < deadline:
            with lock:
                idx = idx_counter[0]
                idx_counter[0] += 1
            suffix = None
            if unique:
                suffix = f"q{idx}-{int(time.time()*1000) % 100000}"
            one_request(base, path, params, body, token, idx, timeout, results, lock, suffix, persist)

    start = time.time()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for _ in range(concurrency):
            pool.submit(worker)
    total = time.time() - start

    statuses = {}
    for s, _, _ in results:
        statuses[s] = statuses.get(s, 0) + 1
    latencies = [ms for s, ms, _ in results if s == 200]
    errors = [e for s, _, e in results if s != 200 and e][:3]

    print(f"\n=== {path} | 并发 {concurrency} | 时长 {total:.1f}s | 请求 {len(results)} ===")
    print(f"QPS(全部): {len(results) / total:.2f} | QPS(成功): {len(latencies) / total:.2f}")
    print(f"状态分布: {statuses}")
    if latencies:
        print(f"成功请求延迟 ms: P50={percentile(latencies,50):.0f} P95={percentile(latencies,95):.0f} "
              f"P99={percentile(latencies,99):.0f} 均值={statistics.mean(latencies):.0f} "
              f"最快={min(latencies):.0f} 最慢={max(latencies):.0f}")
    if errors:
        print(f"错误样例: {errors}")
    return {
        "path": path, "concurrency": concurrency, "total": len(results),
        "duration": round(total, 1), "statuses": statuses,
        "qps": round(len(results) / total, 2),
        "qps_ok": round(len(latencies) / total, 2),
        "p50": percentile(latencies, 50), "p95": percentile(latencies, 95),
        "p99": percentile(latencies, 99),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8081")
    ap.add_argument("--path", default="/api/search")
    ap.add_argument("--params", default="q={q}&topK=3&mode=hybrid")
    ap.add_argument("--body", default=None)
    ap.add_argument("--body-file", default=None, help="请求体模板文件（内含 {q} 占位符），避免命令行引号被 shell 吃掉")
    ap.add_argument("--token", default=None)
    ap.add_argument("--concurrency", type=int, default=5)
    ap.add_argument("--duration", type=int, default=20)
    ap.add_argument("--timeout", type=int, default=60)
    ap.add_argument("--label", default=None)
    ap.add_argument("--unique", action="store_true", help="每次请求带唯一后缀，打穿缓存，测真实模型路径")
    ap.add_argument("--persist", action="store_true", help="不送 X-No-Persist（会写库，也会被限流计数，用于验证 429）")
    args = ap.parse_args()

    body_template = args.body
    if args.body_file:
        with io.open(args.body_file, encoding="utf-8") as f:
            body_template = f.read().strip()

    if args.label:
        print(f"### {args.label}")
    print("压测前指标快照：\n" + prometheus_snapshot(args.url, args.token))
    summary = run(args.url, args.path, args.params, body_template, args.token,
                  args.concurrency, args.duration, args.timeout, args.unique, args.persist)
    print("\n压测后指标快照：\n" + prometheus_snapshot(args.url, args.token))
    print("\nSUMMARY " + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()

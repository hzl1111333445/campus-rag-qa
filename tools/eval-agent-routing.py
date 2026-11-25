# -*- coding: utf-8 -*-
"""
Agent 分流评测：量"该查没查"（漏检）与"不该查却查"（多查）。

判定"是否真的检索了"：steps > 1 或 allowedChunks 非空。
- 直接作答：1 步就 final，chunks=0
- 检索后作答：第 1 步 search_rules（chunks>0）→ 第 2 步 final，steps>=2

用法（服务需 --campus.agent.enabled=true）：
    python tools/eval-agent-routing.py [--base http://localhost:8081] [--out docs/eval-runs/...tsv]
"""
import argparse
import json
import io
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TESTSET = os.path.join(ROOT, "data", "testset", "agent_routing_questions.tsv")


def load_cases():
    cases = []
    with io.open(TESTSET, encoding="utf-8") as f:
        for line in f:
            line = line.strip("\n")
            if not line.strip() or line.strip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            cases.append((int(parts[0]), parts[1].strip(), int(parts[2]), parts[3].strip()))
    return cases


def ask(base, question, top_k=5, timeout=240):
    body = json.dumps({"question": question, "topK": top_k}).encode("utf-8")
    req = urllib.request.Request(base + "/api/agent/chat", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--out", default=os.path.join(ROOT, "docs", "eval-runs", "agent_routing_2026-11-25.tsv"))
    ap.add_argument("--topk", type=int, default=5)
    args = ap.parse_args()

    cases = load_cases()
    rows = []
    t_all = time.time()
    for cid, question, expect, kind in cases:
        t0 = time.time()
        try:
            b = ask(args.base, question, args.topk)
            ms = int((time.time() - t0) * 1000)
            steps = b.get("steps") or 0
            chunks = len(b.get("allowedChunks") or [])
            searched = 1 if (steps > 1 or chunks > 0) else 0
            rows.append({
                "id": cid, "question": question, "expect": expect, "kind": kind,
                "searched": searched, "steps": steps, "chunks": chunks,
                "status": b.get("status"), "ms": ms,
                "totalTokens": b.get("totalTokens"), "costYuan": b.get("costYuan"),
                "answer": (b.get("answer") or "").replace("\t", " ").replace("\n", " ")[:120],
                "error": b.get("error") or "",
            })
        except Exception as e:  # noqa: BLE001
            rows.append({"id": cid, "question": question, "expect": expect, "kind": kind,
                         "searched": -1, "steps": -1, "chunks": 0, "status": "EXCEPTION", "ms": 0,
                         "totalTokens": 0, "costYuan": 0, "answer": "", "error": str(e)})
        r = rows[-1]
        flag = ""
        if r["searched"] == 1 and r["expect"] == 0:
            flag = "  ← 多查"
        elif r["searched"] == 0 and r["expect"] == 1:
            flag = "  ← 漏检"
        print(f"[{r['id']:>2}] expect={r['expect']} searched={r['searched']} steps={r['steps']:>2} "
              f"chunks={r['chunks']:>2} {r['ms']:>5}ms  {r['question']}{flag}")

    should = [r for r in rows if r["expect"] == 1]
    should_not = [r for r in rows if r["expect"] == 0]
    miss = [r for r in should if r["searched"] == 0]
    extra = [r for r in should_not if r["searched"] == 1]
    ok = [r for r in rows if (r["expect"] == 1 and r["searched"] == 1) or (r["expect"] == 0 and r["searched"] == 0)]
    total_ms = sum(r["ms"] for r in rows)
    total_tokens = sum(r["totalTokens"] or 0 for r in rows)
    total_cost = sum(r["costYuan"] or 0 for r in rows)

    print("\n===== 汇总 =====")
    print(f"题数 {len(rows)} | 决策正确 {len(ok)}/{len(rows)} = {len(ok)/len(rows)*100:.1f}%")
    print(f"漏检率（该查没查）{len(miss)}/{len(should)} = {len(miss)/len(should)*100:.1f}%  {[r['id'] for r in miss]}")
    print(f"多查率（不该查却查）{len(extra)}/{len(should_not)} = {len(extra)/len(should_not)*100:.1f}%  {[r['id'] for r in extra]}")
    print(f"总耗时 {total_ms/1000:.1f}s | 平均 {total_ms/len(rows):.0f}ms | token {total_tokens} | 花费 ¥{total_cost:.4f}")
    print(f"整轮墙钟 {time.time()-t_all:.1f}s")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    header = ["id", "expect", "searched", "steps", "chunks", "status", "ms", "totalTokens", "costYuan", "kind", "question", "answer", "error"]
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(header) + "\n")
        for r in rows:
            f.write("\t".join(str(r[h]) for h in header) + "\n")
    print("原始结果已写:", args.out)


if __name__ == "__main__":
    sys.exit(main())

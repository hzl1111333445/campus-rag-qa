# -*- coding: utf-8 -*-
"""汇总对照组实验，输出一张可以贴进文档/README 的对照表。

口径与 docs/15 一致：
    应答题 = 118 道（120 题里剔除 2 道"无答案"题，那两道考的是拒答）
    严格正确率 = 正确 / 118
    宽松正确率 = (正确 + 部分正确) / 118
    幻觉率     = 裁判判 hallucination=true 的题数 / 118
    误拒率     = 拒答错误 / 118
"""
import io
from collections import Counter

RAW = r"E:\tmp\baseline_raw.tsv"

# RAG 线上（docs/15 第 10 节，第三次评测：提示词硬化 + 阈值 0.015）
RAG = {"name": "RAG（线上链路）", "strict": 51.7, "loose": 78.8, "hal": 34.7, "wrong_reject": 5.1}


def load():
    rows = []
    for i, line in enumerate(io.open(RAW, encoding="utf-8")):
        p = line.rstrip("\n").split("\t")
        if i == 0:
            cols = p
            continue
        if len(p) < len(cols):
            p += [""] * (len(cols) - len(p))
        rows.append(dict(zip(cols, p)))
    return rows


def is_noanswer(r):
    return "无答案" in r["typ"]


def stat(rows, vkey, hkey):
    ans = [r for r in rows if not is_noanswer(r)]
    noans = [r for r in rows if is_noanswer(r)]
    c = Counter(r[vkey] for r in ans)
    n = len(ans)
    hal = sum(1 for r in ans if r[hkey].strip().lower() == "true")
    strict = c.get("正确", 0) / n * 100
    loose = (c.get("正确", 0) + c.get("部分正确", 0)) / n * 100
    return {
        "n": n,
        "正确": c.get("正确", 0), "部分正确": c.get("部分正确", 0),
        "错误": c.get("错误", 0), "拒答错误": c.get("拒答错误", 0),
        "解析失败": c.get("解析失败", 0),
        "strict": strict, "loose": loose,
        "hal": hal / n * 100,
        "wrong_reject": c.get("拒答错误", 0) / n * 100,
        "noans": [(r["question"], r[vkey]) for r in noans],
    }


def by_type(rows, vkey):
    types = {}
    for r in rows:
        if is_noanswer(r):
            continue
        t = r["typ"]
        types.setdefault(t, Counter())[r[vkey]] += 1
    return types


def main():
    rows = load()
    a = stat(rows, "verdict_a", "hal_a")
    b = stat(rows, "verdict_b", "hal_b")
    print("题数:", len(rows))
    print()
    hdr = "| 条件 | 严格正确率 | 含部分正确 | 幻觉率 | 误拒率 | 正确/部分/错误/拒答错误 |"
    print(hdr)
    print("|---|---|---|---|---|---|")
    print("| %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | — |" % (
        RAG["name"], RAG["strict"], RAG["loose"], RAG["hal"], RAG["wrong_reject"]))
    for name, s in (("A 纯模型·不约束", a), ("B 纯模型·要求谨慎", b)):
        print("| %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %d / %d / %d / %d |" % (
            name, s["strict"], s["loose"], s["hal"], s["wrong_reject"],
            s["正确"], s["部分正确"], s["错误"], s["拒答错误"]))
    print()
    for name, s in (("A 纯模型·不约束", a), ("B 纯模型·要求谨慎", b)):
        print("%s：解析失败 %d 道；无答案题判定 %s" % (name, s["解析失败"], s["noans"]))
    print()
    for name, vkey in (("A", "verdict_a"), ("B", "verdict_b")):
        print("按题型（%s）：" % name)
        for t, c in by_type(rows, vkey).items():
            tot = sum(c.values())
            print("  %-8s 共 %2d 题  正确 %2d  部分 %2d  错误 %2d  拒答错误 %2d" % (
                t, tot, c.get("正确", 0), c.get("部分正确", 0), c.get("错误", 0), c.get("拒答错误", 0)))
        print()


if __name__ == "__main__":
    main()

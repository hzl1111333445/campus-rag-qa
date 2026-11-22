"""E 方案标定：把 36 道题的"最高检索分"全部取出来，看能不能用一条线分开"能答"和"答不了"。

思路：
  1. 读题集 eval_questions.tsv（跳注释行）
  2. 每题调 /api/search?mode=best&topK=3，取 topScore 与 gated
  3. 按 题型 分组打印，并给出"若阈值取 x，会误拒几道可答题 / 会放过几道无答案题"的扫描表

用法：
  python tools/calibrate_threshold.py [端口]
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

PORT = sys.argv[1] if len(sys.argv) > 1 else "8090"
BASE = f"http://127.0.0.1:{PORT}"
TSV = Path(__file__).resolve().parent.parent / "data" / "testset" / "eval_questions.tsv"


def load_questions():
    rows = []
    for line in TSV.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 5:
            print(f"[跳过] 列数不对：{line[:40]}")
            continue
        qid, question, keywords, qtype, difficulty = parts
        rows.append({
            "id": int(qid),
            "question": question,
            "type": qtype,
            "difficulty": int(difficulty),
        })
    return rows


def top_score(question):
    url = f"{BASE}/api/search?" + urllib.parse.urlencode(
        {"q": question, "topK": 3, "mode": "best"}
    )
    with urllib.request.urlopen(url, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("topScore", 0.0), data.get("count", 0), data.get("gated")


def main():
    questions = load_questions()
    print(f"题集：{len(questions)} 道，端口 {PORT}\n")

    answerable, unanswerable = [], []
    for q in questions:
        score, count, gated = top_score(q["question"])
        q["score"] = score
        q["count"] = count
        q["gated"] = gated
        (unanswerable if q["type"] == "无答案" else answerable).append(q)
        print(f"#{q['id']:>2} [{q['type']}] {score:.4f}  {q['question'][:34]}")

    print("\n" + "=" * 62)
    print("可答题（应该有资料可答）")
    print("=" * 62)
    for q in sorted(answerable, key=lambda x: x["score"]):
        print(f"  {q['score']:.4f}  #{q['id']:>2} [{q['type']}] {q['question'][:30]}")
    lo = min(q["score"] for q in answerable)
    print(f"\n  可答题最低分：{lo:.4f}")

    print("\n" + "=" * 62)
    print("无答案题（库里本来就没有）")
    print("=" * 62)
    for q in sorted(unanswerable, key=lambda x: -x["score"]):
        print(f"  {q['score']:.4f}  #{q['id']:>2} {q['question'][:30]}")
    hi = max(q["score"] for q in unanswerable) if unanswerable else 0
    print(f"\n  无答案题最高分：{hi:.4f}")

    print("\n" + "=" * 62)
    print("阈值扫描（误拒 = 可答题被判没资料；放过 = 无答案题混进来）")
    print("=" * 62)
    print(f"{'阈值':>6} {'误拒':>6} {'放过':>6}  说明")
    for t in [0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65]:
        wrong = sum(1 for q in answerable if q["score"] < t)
        leaked = sum(1 for q in unanswerable if q["score"] >= t)
        note = ""
        if wrong == 0 and leaked == 0:
            note = "★ 完美分开"
        elif wrong == 0:
            note = "不误拒，但放过" + str(leaked) + "道"
        print(f"{t:>6.2f} {wrong:>6} {leaked:>6}  {note}")

    print(f"\n分界区间：无答案题最高 {hi:.4f} → 可答题最低 {lo:.4f}")
    if hi < lo:
        print(f"→ 建议阈值取 {(hi + lo) / 2:.2f}（区间中点，离两边都最远）")


if __name__ == "__main__":
    main()

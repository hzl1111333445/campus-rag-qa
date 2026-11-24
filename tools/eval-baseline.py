# -*- coding: utf-8 -*-
"""对照组实验：不接知识库，直接问大模型，看它在同一批题上表现如何。

为什么要做这个实验？
    到现在为止所有数字都是"我这套 RAG 内部不同配置之间比"（纯向量 / BM25 / 混合 / 精排…）。
    评委或面试官一定会问一句：那不用 RAG、直接问模型呢？
    没有这一组，"90.68% 的召回、51.7% 的严格正确率"就没有参照物。

实验设计（三个条件，同一批 120 题、同一个裁判、同一套判定口径）：
    A 纯模型·不约束   ：只给问题，不给资料，让它自由回答（正常聊天式提问）
    B 纯模型·要求谨慎 ：只给问题，但明确要求"不确定就说不知道，不要猜"
    C RAG（已有）     ：双查询融合 + 精排 + 展开邻居 + 阈值拒答（docs/15 第 10 节，51.7% / 34.7% / 5.1%）

    判定用的资料原文与 C 条件完全相同（都取线上同一检索器的 Top3），
    这样"幻觉"的含义才一致：说了库里没有的具体事实。

输出：E:\tmp\baseline_raw.tsv（原始回答 + 裁判结论）
用法：python tools/eval-baseline.py [--limit N]
"""
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8090"
TESTSET = r"E:\tmp\campus-ai-rag\data\testset\eval_questions.tsv"
OUT = r"E:\tmp\baseline_raw.tsv"
LOCAL_YML = r"E:\tmp\campus-ai-rag\demo\src\main\resources\application-local.yml"
API = "https://api.deepseek.com/v1/chat/completions"
MODEL = "deepseek-chat"

# ---------------- 读 key（精确取 campus.llm.api-key，别拿成 embedding 的） ----------------
def load_key():
    cur1 = cur2 = ""
    for line in io.open(LOCAL_YML, encoding="utf-8"):
        raw = line.rstrip("\n")
        if not raw.strip() or raw.strip().startswith("#"):
            continue
        m1 = re.match(r"^(\S[^:]*):\s*$", raw)
        m2 = re.match(r"^  (\S[^:]*):\s*$", raw)
        m3 = re.match(r"^    (\S[^:]*):\s*(\S.*)$", raw)
        if m1:
            cur1, cur2 = m1.group(1), ""
        elif m2:
            cur2 = m2.group(1)
        elif m3 and cur1 == "campus" and cur2 == "llm" and m3.group(1) == "api-key":
            return m3.group(2).strip().strip('"').strip("'")
    return ""


KEY = load_key()
print("key:", (KEY[:8] + "...") if KEY else "(空)", flush=True)


def chat(prompt, max_tokens=260):
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "max_tokens": max_tokens,
    }).encode("utf-8")
    req = urllib.request.Request(API, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + KEY,
    })
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.loads(r.read().decode("utf-8"))
    return d["choices"][0]["message"]["content"].strip()


# ---------------- 题集 ----------------
def load_questions(limit=None):
    rows = []
    for line in io.open(TESTSET, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        p = line.split("\t")
        if len(p) < 6:
            continue
        rows.append({"id": p[0], "q": p[1], "kw": p[2], "typ": p[3], "diff": p[4], "split": p[5]})
    return rows[:limit] if limit else rows


def login_token():
    """/api/search 现在要求登录（管理员），裁判取证据前先换一个令牌。"""
    body = json.dumps({"username": "admin", "password": "admin123"}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8")).get("token", "")


TOKEN = login_token()
print("token:", (TOKEN[:16] + "...") if TOKEN else "(空)", flush=True)


def evidence(q):
    """与线上同源的检索证据，只给裁判看（模型看不到）。"""
    try:
        u = BASE + "/api/search?q=" + urllib.parse.quote(q) + "&topK=5&mode=best"
        req = urllib.request.Request(u, headers={"Authorization": "Bearer " + TOKEN})
        with urllib.request.urlopen(req, timeout=90) as r:
            d = json.loads(r.read().decode("utf-8"))
        hits = d.get("hits") or []
        return "\n".join("[%d] 《%s》\n%s" % (i + 1, h.get("documentTitle", ""), (h.get("content") or "")[:420])
                         for i, h in enumerate(hits))
    except Exception as e:
        return "(取证据失败: %s)" % e


# ---------------- 两个对照条件 ----------------
A_PROMPT = """你是一个校园规章问答助手，学生在问你问题。请直接回答他的问题。

学生的问题：%s"""

B_PROMPT = """你是一个校园规章问答助手，学生在问你问题。

回答规则：
1. 只回答你确实知道的内容；
2. 如果你不确定，直接说"我不确定"，不要猜测，也不要编造具体数字、金额、天数或条款号。

学生的问题：%s"""


# ---------------- 裁判（与线上评测同一套口径） ----------------
RUBRIC = """你是严格的阅卷老师，评估一个校园规章问答系统的回答质量。

【问题】%s
【标准要点】（答案必须说对这些）%s
【学校规章原文】（这是学校真实规定，用来核对回答对不对）
%s
【系统输出】%s

请判断：
- 如果系统输出明确表示"不确定/不知道/知识库未收录"：判断原文里是否真的有答案。原文里确实没有 → verdict="拒答正确"；原文里明显有答案却被拒 → verdict="拒答错误"
- 否则判断答案是否正确：
  - 全部要点说对且没有编造 → "正确"
  - 部分要点对、或有小瑕疵 → "部分正确"
  - 关键信息错、答非所问、或编造了原文里没有的内容 → "错误"
- hallucination：答案中是否出现了原文里没有的具体事实（数字、金额、天数、条号、处分名）。有 → true

只输出一行 JSON，不要任何解释：
{"verdict":"正确|部分正确|错误|拒答正确|拒答错误","hallucination":true或false,"note":"不超过30字"}"""


def judge(q, kw, ev, ans):
    prompt = RUBRIC % (q, kw.replace("|", "、"), ev, ans if ans.strip() else "（空回答）")
    txt = chat(prompt, max_tokens=140)
    m = re.search(r"\{.*\}", txt, re.S)
    if not m:
        return {"verdict": "解析失败", "hallucination": None, "note": txt[:40]}
    try:
        return json.loads(m.group(0))
    except Exception:
        return {"verdict": "解析失败", "hallucination": None, "note": txt[:40]}


def run_one(r):
    q = r["q"]
    try:
        ans_a = chat(A_PROMPT % q)
    except Exception as e:
        ans_a = "(生成失败: %s)" % e
    try:
        ans_b = chat(B_PROMPT % q)
    except Exception as e:
        ans_b = "(生成失败: %s)" % e
    ev = evidence(q)
    ja = judge(q, r["kw"], ev, ans_a)
    jb = judge(q, r["kw"], ev, ans_b)
    return dict(r, ans_a=ans_a, ans_b=ans_b,
                verdict_a=ja.get("verdict", ""), hal_a=str(ja.get("hallucination")),
                verdict_b=jb.get("verdict", ""), hal_b=str(jb.get("hallucination")),
                note_a=(ja.get("note") or ""), note_b=(jb.get("note") or ""))


def main():
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    rows = load_questions(limit)
    print("题目数:", len(rows), flush=True)

    t0 = time.time()
    out = io.open(OUT, "w", encoding="utf-8", newline="")
    cols = ["id", "split", "typ", "question", "answer_a", "verdict_a", "hal_a",
            "answer_b", "verdict_b", "hal_b", "note_a", "note_b"]
    out.write("\t".join(cols) + "\n")
    done = 0
    with ThreadPoolExecutor(max_workers=4) as pool:
        for res in pool.map(run_one, rows):
            done += 1
            out.write("\t".join([
                res["id"], res["split"], res["typ"], res["q"].replace("\t", " "),
                res["ans_a"].replace("\t", " ").replace("\n", " "), res["verdict_a"], res["hal_a"],
                res["ans_b"].replace("\t", " ").replace("\n", " "), res["verdict_b"], res["hal_b"],
                res["note_a"].replace("\t", " "), res["note_b"].replace("\t", " "),
            ]) + "\n")
            out.flush()
            if done % 10 == 0:
                print("  %d/%d  %.0fs" % (done, len(rows), time.time() - t0), flush=True)
    out.close()
    print("完成 -> %s  耗时 %.0fs" % (OUT, time.time() - t0), flush=True)


if __name__ == "__main__":
    main()

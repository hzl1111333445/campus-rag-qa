# -*- coding: utf-8 -*-
"""端到端回答评测第 1 步：逐题走完整 /api/qa 链路，落原始数据。"""
import io, json, time, urllib.request

TESTSET = r"E:\tmp\campus-ai-rag\data\testset\eval_questions.tsv"
OUT = r"E:\tmp\answer_raw.tsv"
BASE = "http://localhost:8090"

def load():
    rows = []
    for line in io.open(TESTSET, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        p = line.split("\t")
        if len(p) < 5:
            continue
        split = p[5] if len(p) > 5 else "dev"
        rows.append(dict(id=int(p[0]), q=p[1], kw=p[2], typ=p[3], diff=int(p[4]), split=split))
    return rows

def ask(q):
    body = json.dumps({"question": q, "topK": 3}).encode("utf-8")
    # X-No-Persist: 评测是机器调用，不该往 qa_session 里灌 118 条"匿名会话"
    # 把真人对话历史淹掉；后端看到这个头会跳过会话落库。
    req = urllib.request.Request(BASE + "/api/qa", data=body,
                                headers={"Content-Type": "application/json",
                                         "X-No-Persist": "1"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))

rows = load()
print("题数 =", len(rows))
out = io.open(OUT, "w", encoding="utf-8", newline="")
cols = ["id","split","typ","question","keywords","gated","topScore","citationOk","citationCount",
        "invalid","grounded","factCount","missing","latency","retrieved","answer"]
out.write("\t".join(cols) + "\n")
t0 = time.time()
for i, r in enumerate(rows, 1):
    try:
        d = ask(r["q"])
        ans = (d.get("answer") or "").replace("\t", " ").replace("\n", " ")
        rec = [str(r["id"]), r["split"], r["typ"], r["q"].replace("\t"," "), r["kw"],
               str(d.get("gated")), "%.4f" % (d.get("topScore") or 0),
               str(d.get("citationOk")), str(len(d.get("references") or [])),
               "|".join(str(x) for x in (d.get("invalidCitations") or [])),
               str(d.get("grounded")), str(d.get("factCount")),
               "|".join(d.get("missingFacts") or []), str(d.get("elapsedMs")),
               str(d.get("retrievedCount")), ans]
    except Exception as e:
        rec = [str(r["id"]), r["split"], r["typ"], r["q"].replace("\t"," "), r["kw"],
               "ERR","","","","","","","","","", "调用失败: %s" % e]
    out.write("\t".join(rec) + "\n")
    out.flush()
    if i % 10 == 0:
        print("  %d/%d  %.0fs" % (i, len(rows), time.time()-t0), flush=True)
    time.sleep(0.15)
out.close()
print("完成 %d 题，耗时 %.0fs -> %s" % (len(rows), time.time()-t0, OUT))
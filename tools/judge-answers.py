# -*- coding: utf-8 -*-
"""第 2 步：用 DeepSeek 当裁判，判定每道题的答案质量。"""
import io, json, re, time, urllib.request

BASE = "http://localhost:8090"
RAW = r"E:\tmp\answer_raw.tsv"
OUT = r"E:\tmp\answer_judge.tsv"

# 读 key
# 精确取 campus.llm.api-key（前面误取了 embedding 的 key，导致 401）
key = ""
cur1 = cur2 = ""
for line in io.open(r"E:\tmp\campus-ai-rag\demo\src\main\resources\application-local.yml", encoding="utf-8"):
    raw = line.rstrip("\n")
    if not raw.strip() or raw.strip().startswith("#"):
        continue
    m1 = re.match(r"^(\S[^:]*):\s*$", raw)
    m2 = re.match(r"^  (\S[^:]*):\s*$", raw)
    m3 = re.match(r"^    (\S[^:]*):\s*(\S.*)$", raw)
    if m1:
        cur1 = m1.group(1); cur2 = ""
    elif m2:
        cur2 = m2.group(1)
    elif m3 and cur1 == "campus" and cur2 == "llm" and m3.group(1) == "api-key":
        key = m3.group(2).strip().strip('"').strip("'")
print("key:", key[:8] + "..." if key else "(空)")

cols=None; rows=[]
for i,line in enumerate(io.open(RAW, encoding="utf-8")):
    p=line.rstrip("\n").split("\t")
    if i==0: cols=p; continue
    if len(p)<len(cols): p+=[""]*(len(cols)-len(p))
    rows.append(dict(zip(cols,p)))

def b(x): return str(x).strip().lower()=="true"

def get_evidence(q):
    try:
        u = BASE + "/api/search?q=" + urllib.parse.quote(q) + "&topK=3&mode=hybrid_rerank_expand"
        with urllib.request.urlopen(u, timeout=90) as r:
            d = json.loads(r.read().decode("utf-8"))
        hits = d.get("hits") or []
        return "\n".join("[%d] 《%s》\n%s" % (i+1, h.get("documentTitle",""), (h.get("content") or "")[:420])
                         for i,h in enumerate(hits))
    except Exception as e:
        return "(取证据失败: %s)" % e

RUBRIC = """你是严格的阅卷老师，评估一个校园规章问答系统的回答质量。

【问题】%s
【标准要点】（答案必须说对这些）%s
【检索到的资料原文】（这是系统拿到的全部依据）
%s
【系统输出】%s

请判断：
- 如果系统输出明确表示"知识库暂未收录/资料不足"：判断库里是否真的没有答案。资料里确实没有 → verdict="拒答正确"；资料里明显有答案却被拒 → verdict="拒答错误"
- 否则判断答案是否正确：
  - 全部要点说对且没有编造 → "正确"
  - 部分要点对、或有小瑕疵 → "部分正确"
  - 关键信息错、答非所问、或编造了资料里没有的内容 → "错误"
- hallucination：答案中是否出现了资料里没有的具体事实（数字、金额、天数、条号、处分名）。有 → true

只输出一行 JSON，不要任何解释：
{"verdict":"正确|部分正确|错误|拒答正确|拒答错误","hallucination":true或false,"note":"不超过30字"}"""

def judge(q, kw, ev, ans, gated):
    if gated or not ans.strip():
        ans_txt = "（系统直接拒答：本知识库暂未收录与该问题相关的规定）"
    else:
        ans_txt = ans
    prompt = RUBRIC % (q, kw.replace("|", "、"), ev, ans_txt)
    body = json.dumps({"model":"deepseek-chat","messages":[{"role":"user","content":prompt}],
                       "temperature":0,"max_tokens":120}).encode("utf-8")
    req = urllib.request.Request("https://api.deepseek.com/v1/chat/completions", data=body,
        headers={"Content-Type":"application/json","Authorization":"Bearer "+key}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.loads(r.read().decode("utf-8"))
    txt = d["choices"][0]["message"]["content"]
    m = re.search(r"\{.*\}", txt, re.S)
    return json.loads(m.group(0)) if m else {"verdict":"解析失败","hallucination":None,"note":txt[:40]}

out = io.open(OUT, "w", encoding="utf-8", newline="")
out.write("id\tsplit\ttyp\tquestion\tgated\tverdict\thallucination\tnote\n")
t0=time.time()
for i,r in enumerate(rows,1):
    ev = get_evidence(r["question"])
    try:
        j = judge(r["question"], r["keywords"], ev, r["answer"], b(r["gated"]))
    except Exception as e:
        j = {"verdict":"ERR","hallucination":None,"note":str(e)[:40]}
    out.write("\t".join([r["id"], r["split"], r["typ"], r["question"].replace("\t"," "),
                         r["gated"], j.get("verdict",""), str(j.get("hallucination")),
                         (j.get("note") or "").replace("\t"," ")]) + "\n")
    out.flush()
    if i%10==0: print("  %d/%d %.0fs"%(i,len(rows),time.time()-t0), flush=True)
    time.sleep(0.1)
out.close()
print("完成 -> %s  耗时 %.0fs" % (OUT, time.time()-t0))
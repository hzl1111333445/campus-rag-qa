# -*- coding: utf-8 -*-
"""校验评测题集：每题的关键词是否真的在库里的块中出现。
用法：python verify_testset.py
输入：E:\\tmp\\chunks.tsv（id \t doc_id \t 文档名 \t chunk_index \t 内容）
      E:\\tmp\\campus-ai-rag\\data\\testset\\eval_questions.tsv
输出：逐题匹配块数 + 命中文档名；最后一题一行的汇总
"""
import io
import sys

TSV_CHUNKS = r"E:\tmp\chunks.tsv"
TSV_Q = r"E:\tmp\campus-ai-rag\data\testset\eval_questions.tsv"

sys.stdout.reconfigure(encoding="utf-8")


def load_chunks(path):
    rows = []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            cid, doc_id, doc, idx, content = parts[0], parts[1], parts[2], parts[3], parts[4]
            rows.append({"id": cid, "doc": doc, "idx": idx, "content": content})
    return rows


def load_questions(path):
    qs = []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            qid, question, keywords, qtype, diff = parts[0], parts[1], parts[2], parts[3], parts[4]
            qs.append({
                "id": qid, "question": question, "type": qtype, "diff": diff,
                "keywords": [k for k in keywords.split("|") if k],
            })
    return qs


def main():
    chunks = load_chunks(TSV_CHUNKS)
    questions = load_questions(TSV_Q)
    print("块数：%d    题数：%d\n" % (len(chunks), len(questions)))

    bad = []
    print("%-4s %-6s %-8s %-6s %s" % ("id", "题型", "匹配块数", "难度", "命中的文档（最多列3个）"))
    print("-" * 96)
    for q in questions:
        kws = q["keywords"]
        matched = [c for c in chunks if all(k in c["content"] for k in kws)]
        docs = []
        for c in matched:
            short = c["doc"].replace(".pdf", "").replace(".txt", "")
            if short not in docs:
                docs.append(short)
        show = "、".join(docs[:3]) + ("…" if len(docs) > 3 else "")
        print("%-4s %-6s %-8d %-6s %s" % (q["id"], q["type"], len(matched), q["diff"], show if matched else "（无匹配！）"))
        if q["type"] == "无答案":
            if len(matched) > 3:
                bad.append((q["id"], "无答案题却有 %d 块命中，关键词选得不干净" % len(matched)))
        else:
            if len(matched) == 0:
                bad.append((q["id"], "有答案题但 0 块命中，关键词写错了"))
            elif len(matched) > 30:
                bad.append((q["id"], "命中 %d 块太多，关键词不够有区分度" % len(matched)))

    print("-" * 96)
    # 题型分布
    dist = {}
    for q in questions:
        dist[q["type"]] = dist.get(q["type"], 0) + 1
    print("题型分布：" + "，".join("%s %d 题" % (k, v) for k, v in dist.items()))
    # 文档覆盖
    covered = set()
    for q in questions:
        if q["type"] == "无答案":
            continue
        for c in chunks:
            if all(k in c["content"] for k in q["keywords"]):
                covered.add(c["doc"])
    print("有答案题覆盖到的文档数：%d" % len(covered))
    if bad:
        print("\n⚠ 需要修正的题：")
        for qid, why in bad:
            print("  #%s  %s" % (qid, why))
    else:
        print("\n✅ 全部题目通过校验")


if __name__ == "__main__":
    main()

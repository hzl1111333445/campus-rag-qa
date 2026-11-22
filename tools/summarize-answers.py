# -*- coding: utf-8 -*-
import io
cols=None; rows=[]
for i,line in enumerate(io.open(r"E:\tmp\answer_judge.tsv", encoding="utf-8")):
    p=line.rstrip("\n").split("\t")
    if i==0: cols=p; continue
    if len(p)<len(cols): p+=[""]*(len(cols)-len(p))
    rows.append(dict(zip(cols,p)))

from collections import Counter
print("总题数 =", len(rows))
print("判定分布 =", dict(Counter(r["verdict"] for r in rows)))
print()
ans_rows=[r for r in rows if r["typ"]!="无答案"]
ref_rows=[r for r in rows if r["typ"]=="无答案"]
def pct(a,b): return "%.1f%%"%(100.0*a/b) if b else "-"

right=[r for r in ans_rows if r["verdict"]=="正确"]
part=[r for r in ans_rows if r["verdict"]=="部分正确"]
wrong=[r for r in ans_rows if r["verdict"]=="错误"]
refused=[r for r in ans_rows if r["verdict"]=="拒答错误"]
print("应答题 %d 道："%len(ans_rows))
print("  正确      %3d  %s" % (len(right), pct(len(right),len(ans_rows))))
print("  部分正确  %3d  %s" % (len(part), pct(len(part),len(ans_rows))))
print("  错误      %3d  %s" % (len(wrong), pct(len(wrong),len(ans_rows))))
print("  应拒答误拒 %3d  %s" % (len(refused), pct(len(refused),len(ans_rows))))
print("  ▶ 严格正确率        %s" % pct(len(right),len(ans_rows)))
print("  ▶ 正确+部分 宽松   %s" % pct(len(right)+len(part),len(ans_rows)))
print("  ▶ 错误率(错+误拒)  %s" % pct(len(wrong)+len(refused),len(ans_rows)))
hal=[r for r in ans_rows if r["hallucination"]=="True"]
print("  ▶ 幻觉题数 %d  %s" % (len(hal), pct(len(hal),len(ans_rows))))
print()
print("无答案题 %d 道：正确拒答 %d 道" % (len(ref_rows), sum(1 for r in ref_rows if r["verdict"]=="拒答正确")))
print()
print("=== 按题型 ===")
types={}
for r in rows:
    types.setdefault(r["typ"],[]).append(r)
for t,v in sorted(types.items()):
    c=Counter(x["verdict"] for x in v)
    print("%-5s 题数 %3d | 正确 %2d 部分 %2d 错误 %2d 拒答对 %d 拒答错 %d" %
          (t,len(v),c["正确"],c["部分正确"],c["错误"],c["拒答正确"],c["拒答错误"]))
print()
print("=== 按 split ===")
for sp in ["dev","test"]:
    v=[r for r in ans_rows if r["split"]==sp]; c=Counter(x["verdict"] for x in v)
    print("%s 题数 %d | 正确 %d (%s) | 部分 %d | 错误 %d | 误拒 %d" %
          (sp,len(v),c["正确"],pct(c["正确"],len(v)),c["部分正确"],c["错误"],c["拒答错误"]))
print()
print("=== 错误 / 误拒 明细 ===")
for r in rows:
    if r["verdict"] in ("错误","拒答错误"):
        print("  #%-3s %-24s [%s] %s" % (r["id"], r["question"][:24], r["verdict"], r["note"][:36]))
print()
print("=== 部分正确 明细 ===")
for r in part:
    print("  #%-3s %-24s %s" % (r["id"], r["question"][:24], r["note"][:40]))
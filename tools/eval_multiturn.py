# -*- coding: utf-8 -*-
"""
多轮长会话评测：量"第 N 轮还记得第 1 轮说过的条件吗"

设计
----
每个用例 = 一个 20 轮的会话。前若干轮用户"交代情况"，中间夹无关问题，
在指定轮次（probe）问一个**不带条件**的问题。判定：
  retained  = 回答里出现了早期条件 token（说明历史被用上）
  lost      = 回答里反问"你的绩点/哪个专业"之类已给过的信息
  neutral   = 两者都没有

用法
----
  python tools/eval_multiturn.py                 # 跑全部用例
  python tools/eval_multiturn.py --limit 1       # 只跑第一个
  python tools/eval_multiturn.py --turns 8       # 只跑到第 8 轮（快速试）

依赖：仅标准库（urllib）。服务需在跑（默认 8081）。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE = "http://localhost:8081"
STUDENT = ("student", "student123")

# ---------------------------------------------------------------- 用例定义
# turns: 20 条用户问题（按顺序）
# probes: {轮次(1-based): {"expect": [...任一命中即算保留], "forbid": [...命中即算反问], "note": "..."}}
CASES = [
    {
        "id": "S1-zhuanzhuanye",
        "name": "转专业（条件：大一 / 绩点3.6 / 无挂科 / 目标软件工程）",
        "turns": [
            "你好",
            "我是大一的学生，现在在电子商务专业",
            "我上学期绩点是3.6，没有挂过科",
            "我想转到软件工程专业",
            "那我这样的情况能满足转专业的条件吗",
            "学校对转专业有名额限制吗",
            "如果我要准备材料，大概需要哪些",
            "转专业一般什么时候申请",
            "我数学成绩一般，会有影响吗",
            "还有别的需要注意的地方吗",
            "那我到底能不能转成功",
            "如果我这个学期再挂一科会怎么样",
            "转专业之后学分怎么认定",
            "我可以同时申请两个专业吗",
            "我这种情况成功的可能性大吗",
            "学校有没有转专业的咨询电话",
            "那我需要提前准备什么材料",
            "如果申请没通过还能再申请吗",
            "我现在的条件还差什么",
            "最后再帮我确认一下，我符合条件吗",
        ],
        "probes": {
            5: {"expect": ["大一", "3.6", "软件工程", "电子商务"], "forbid": ["你的绩点", "请问你目前", "你现在的专业", "你大几"], "note": "第2-4轮交代的条件"},
            11: {"expect": ["大一", "3.6", "软件工程", "电子商务"], "forbid": ["你的绩点", "你现在的专业"], "note": "7轮后仍应记得"},
            15: {"expect": ["大一", "3.6", "软件工程"], "forbid": ["你的绩点", "你现在的专业"], "note": "11轮后"},
            20: {"expect": ["大一", "3.6", "软件工程"], "forbid": ["你的绩点", "你现在的专业"], "note": "16轮后（全面失忆点）"},
        },
    },
    {
        "id": "S2-jiangxuejin",
        "name": "奖助学金（条件：大二 / 已通过困难认定 / 专业前10%）",
        "turns": [
            "在吗",
            "我今年大二，家里经济条件不太好",
            "我上学期已经通过了家庭经济困难认定",
            "我的专业排名在前10%",
            "我能申请国家励志奖学金吗",
            "国家奖学金和励志奖学金可以同时拿吗",
            "申请需要提交哪些材料",
            "评审大概什么时候开始",
            "如果我还有助学贷款，会影响评选吗",
            "奖学金金额大概是多少",
            "我这样的情况希望大吗",
            "如果这学期成绩下滑会取消吗",
            "国家助学金和励志奖学金冲突吗",
            "我需要找谁签字",
            "那我符合励志奖学金的条件吗",
            "学校对家庭困难学生还有别的补助吗",
            "勤工助学岗位怎么申请",
            "如果我申请了助学贷款还能申请助学金吗",
            "我还需要补什么材料",
            "再确认一次，我能不能拿励志奖学金",
        ],
        "probes": {
            5: {"expect": ["大二", "困难认定", "前10", "励志"], "forbid": ["你的年级", "你大几", "成绩排名是多少"], "note": "第2-4轮条件"},
            11: {"expect": ["大二", "困难认定", "前10", "励志"], "forbid": ["你的年级", "你大几"], "note": "6轮后"},
            15: {"expect": ["大二", "困难认定", "前10"], "forbid": ["你的年级", "你的排名"], "note": "10轮后"},
            20: {"expect": ["大二", "困难认定", "前10"], "forbid": ["你的年级", "你的排名"], "note": "15轮后"},
        },
    },
    {
        "id": "S3-sushe",
        "name": "宿舍晚归（条件：住3栋 / 本月晚归三次 / 已有一条记过）",
        "turns": [
            "你好",
            "我住在3栋宿舍",
            "这个月我已经晚归三次了",
            "我之前因为别的事被记过一次过",
            "我这样会被怎么处理",
            "宿舍门禁是几点",
            "晚归和夜不归宿有什么区别",
            "如果我周末回家需要请假吗",
            "宿舍能用大功率电器吗",
            "违规电器被查到会怎么处理",
            "我这次会被处分吗",
            "如果我对处分有异议，可以申诉吗",
            "申诉的时限是多久",
            "处分会记入档案吗",
            "我这情况严重吗",
            "晚归记录会消除吗",
            "评优评先会受影响吗",
            "如果我确实有特殊情况怎么办",
            "我需要找辅导员说明吗",
            "最后确认一下，我会被给什么处分",
        ],
        "probes": {
            5: {"expect": ["3栋", "晚归", "记过", "三次"], "forbid": ["你住哪", "几次晚归"], "note": "第2-4轮条件"},
            11: {"expect": ["3栋", "晚归", "记过", "三次"], "forbid": ["你住哪", "几次晚归"], "note": "6轮后"},
            15: {"expect": ["晚归", "记过", "三次"], "forbid": ["几次晚归"], "note": "10轮后"},
            20: {"expect": ["晚归", "记过", "三次", "3栋"], "forbid": ["几次晚归", "你住哪"], "note": "15轮后"},
        },
    },
    {
        "id": "S4-guake",
        "name": "挂科重修（条件：数据结构58分 / 大一 / 想保研）",
        "turns": [
            "在吗",
            "我是大一的",
            "我上学期数据结构考了58分，挂科了",
            "我以后想保研",
            "我这种情况该怎么办",
            "补考和重修有什么区别",
            "重修需要交钱吗",
            "补考一般什么时候",
            "如果补考又没过怎么办",
            "挂科会影响绩点吗",
            "挂科了还能保研吗",
            "保研对绩点有什么要求",
            "重修成绩怎么计算",
            "我可以跨学期重修吗",
            "我这样还有机会保研吗",
            "如果我想申请转专业，挂科有影响吗",
            "需要找谁办理重修手续",
            "重修一年可以修几门",
            "我现在最该做什么",
            "再帮我看看，我这情况还能保研吗",
        ],
        "probes": {
            5: {"expect": ["数据结构", "58", "重修", "补考", "保研"], "forbid": ["哪门课", "什么课", "你大几"], "note": "第2-4轮条件"},
            11: {"expect": ["数据结构", "58", "保研"], "forbid": ["哪门课", "你大几"], "note": "6轮后"},
            15: {"expect": ["数据结构", "58", "保研"], "forbid": ["哪门课"], "note": "10轮后"},
            20: {"expect": ["数据结构", "58", "保研"], "forbid": ["哪门课", "你大几"], "note": "15轮后"},
        },
    },
    {
        "id": "S5-kuangke",
        "name": "旷课（条件：每周三节课 / 已累计18学时 / 无请假）",
        "turns": [
            "你好",
            "我这学期每周有三节课没去上",
            "到现在大概累计旷了18个学时",
            "我没有请过假",
            "我会被处分吗",
            "旷课多少学时会被处分",
            "迟到早退算旷课吗",
            "请假需要什么手续",
            "病假需要医院证明吗",
            "旷课会影响考试成绩吗",
            "我现在的学时会被记成什么处分",
            "如果我现在开始正常上课，之前的不算了吗",
            "处分会通知家长吗",
            "我能不能补请假",
            "我这情况严重吗",
            "期末考试会被取消资格吗",
            "辅导员会找我谈话吗",
            "旷课记录怎么查",
            "申诉有时间限制吗",
            "最后再确认一下，我这样的情况会怎么处理",
        ],
        "probes": {
            5: {"expect": ["18", "学时", "警告", "处分"], "forbid": ["多少学时", "几节课"], "note": "第2-4轮条件"},
            11: {"expect": ["18", "学时", "警告"], "forbid": ["多少学时"], "note": "6轮后"},
            15: {"expect": ["18", "学时", "警告", "处分"], "forbid": ["多少学时"], "note": "10轮后"},
            20: {"expect": ["18", "学时", "警告", "处分"], "forbid": ["多少学时"], "note": "15轮后"},
        },
    },
]


# ---------------------------------------------------------------- HTTP
def http_json(method, path, body=None, token=None, headers=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            hdrs = dict(resp.headers)
            return json.loads(raw) if raw.strip().startswith(("{", "[")) else raw, hdrs
    except urllib.error.HTTPError as e:
        return {"__error__": e.code, "__body__": e.read().decode("utf-8", "replace")}, {}


def login():
    data, _ = http_json("POST", "/api/auth/login", {"username": STUDENT[0], "password": STUDENT[1]})
    token = data.get("token") if isinstance(data, dict) else None
    if not token:
        print("[FATAL] 登录失败:", data)
        sys.exit(1)
    return token


def ask(token, question, session_id=None):
    body = {"question": question, "topK": 3}
    if session_id:
        body["sessionId"] = session_id
    data, hdrs = http_json("POST", "/api/qa", body, token=token)
    if not isinstance(data, dict) or "__error__" in data:
        return None, session_id, str(data)[:200]
    sid = int(hdrs.get("X-Session-Id") or session_id or 0)
    return data, sid, None


def ask_paced(token, question, session_id=None, sleep_sec=2.2, retries=3):
    """限流 30 次/分钟 → 每次间隔 ≥2 秒；撞 429 就按 retryAfterSeconds 等待重试。"""
    for attempt in range(retries):
        if sleep_sec:
            time.sleep(sleep_sec)
        data, hdrs = http_json("POST", "/api/qa",
                               {"question": question, "topK": 3,
                                **({"sessionId": session_id} if session_id else {})},
                               token=token)
        if isinstance(data, dict) and data.get("__error__") == 429:
            try:
                wait = json.loads(data.get("__body__", "{}")).get("retryAfterSeconds", 5)
            except Exception:
                wait = 5
            print(f"      (429 限流，等 {wait}s 重试 {attempt + 1}/{retries})")
            time.sleep(wait + 1)
            continue
        if not isinstance(data, dict) or "__error__" in data:
            return None, session_id, str(data)[:200]
        sid = int(hdrs.get("X-Session-Id") or session_id or 0)
        return data, sid, None
    return None, session_id, "429 重试耗尽"



# ---------------------------------------------------------------- 判定
def judge(answer, spec):
    ans = (answer or "").strip()
    if not ans:
        return "empty", []
    hits = [t for t in spec["expect"] if t in ans]
    back = [t for t in spec.get("forbid", []) if t in ans]
    if back and not hits:
        return "lost", back
    if hits:
        return "retained", hits
    return "neutral", []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--turns", type=int, default=0, help="只跑到第 N 轮")
    ap.add_argument("--out", default="tools/eval_out/multiturn_baseline.json")
    ap.add_argument("--sleep", type=float, default=2.2, help="每次请求间隔秒（限流 30/分钟）")
    args = ap.parse_args()

    cases = CASES[: args.limit] if args.limit else CASES
    token = login()
    print("登录成功，开始多轮评测：", len(cases), "个会话\n")

    report = {"startedAt": datetime.now().isoformat(timespec="seconds"), "cases": []}
    for case in cases:
        turns = case["turns"][: args.turns] if args.turns else case["turns"]
        sid = None
        print("=" * 72)
        print(f"{case['id']}  {case['name']}")
        print("=" * 72)
        rows = []
        for i, q in enumerate(turns, start=1):
            t0 = time.time()
            data, sid, err = ask_paced(token, q, sid, sleep_sec=args.sleep)
            cost = time.time() - t0
            if err:
                print(f"  [{i:02d}] ERROR {err}")
                rows.append({"turn": i, "q": q, "error": err})
                continue
            ans = (data.get("answer") or "").replace("\n", " ")
            verdict = ""
            if i in case["probes"]:
                v, detail = judge(ans, case["probes"][i])
                verdict = f"  <<<< {v.upper()} {detail}"
                rows.append({"turn": i, "q": q, "answer": ans, "verdict": v, "detail": detail,
                             "elapsedMs": data.get("elapsedMs"), "costSec": round(cost, 2)})
                print(f"  [{i:02d}] {q[:28]:30s} -> {v.upper():9s} {detail}  ({cost:.1f}s)")
                print(f"       答: {ans[:110]}")
            else:
                print(f"  [{i:02d}] {q[:28]:30s} ok ({cost:.1f}s)")
        probes = [r for r in rows if r.get("verdict")]
        kept = sum(1 for r in probes if r["verdict"] == "retained")
        report["cases"].append({
            "id": case["id"], "name": case["name"], "sessionId": sid,
            "probes": probes, "retained": kept, "totalProbes": len(probes),
        })
        print(f"  -- 条件保持: {kept}/{len(probes)}")

    print("\n" + "=" * 72)
    print("汇总（第 N 轮是否还记得早期条件）")
    print("=" * 72)
    tot = totp = 0
    for c in report["cases"]:
        tot += c["retained"]
        totp += c["totalProbes"]
        print(f"  {c['id']:22s} {c['retained']}/{c['totalProbes']}")
    rate = (tot / totp * 100) if totp else 0.0
    print(f"  {'合计':22s} {tot}/{totp}  =  {rate:.1f}%")
    report["summary"] = {"retained": tot, "probes": totp, "ratePercent": round(rate, 1)}

    import os
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n明细已写入:", args.out)


if __name__ == "__main__":
    main()

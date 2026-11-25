# -*- coding: utf-8 -*-
"""验证长会话摘要：跑一段多轮对话，然后看 qa_summary 是否生成、折了多少条。

用法：python tools/check_summary.py [轮数]
默认 6 轮（12 条消息），刚好越过 minMessagesToFold=10 的阈值。
"""
import json
import subprocess
import sys
import time
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"E:\cal\MySQL\MySQL Server 8.0\bin\mysql.exe"

QUESTIONS = [
    "旷课多少学时会被处分？",
    "我大二，上学期旷了 18 学时，会怎样？",
    "那奖学金还能申请吗？",
    "国家助学金一年多少钱？",
    "宿舍晚上几点关门？",
    "转专业一年可以申请几次？",
    "考试作弊怎么处理？",
    "请假三天要谁批？",
]


def post(path, payload, token=None, session_id=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if session_id:
        req.add_header("X-Session-Id", str(session_id))
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8")), dict(resp.headers)


def sql(query):
    out = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "campus_rag", "-N", "-B", "--default-character-set=utf8mb4", "-e", query],
        capture_output=True, text=True, encoding="utf-8",
    )
    if out.stdout is None:
        return f"(mysql 无输出, rc={out.returncode}) {out.stderr or ''}"
    return out.stdout.strip()


def main():
    turns = int(sys.argv[1]) if len(sys.argv) > 1 else 6

    data, _ = post("/api/auth/login", {"username": "student", "password": "student123"})
    # 登录接口是"裸返回"（没有 code/data 信封），兼容两种形态
    token = (data.get("data") or data).get("token")
    print("登录成功")

    session_id = None
    for i, q in enumerate(QUESTIONS[:turns], 1):
        payload = {"question": q, "topK": 3}
        if session_id:
            payload["sessionId"] = int(session_id)   # 会话 id 走请求体，响应头只是回显
        data, headers = post("/api/qa", payload, token)
        session_id = headers.get("X-Session-Id") or headers.get("x-session-id") or session_id
        ans = (data.get("data") or data).get("answer", "")
        print(f"[{i}] {q} -> {len(ans)} 字 (session={session_id})")

    print(f"\n会话 {session_id} 已产生 {turns * 2} 条消息，等 8 秒让异步折叠跑完…")
    time.sleep(8)

    print("\n=== qa_summary ===")
    print(sql(
        "SELECT session_id, upto_message_id, tokens, CHAR_LENGTH(content), "
        "REPLACE(content, CHAR(10), ' ') FROM qa_summary WHERE session_id=%s" % session_id
    ) or "(没有摘要行)")
    print("\n=== 消息与折叠状态 ===")
    print(sql(
        "SELECT id, role, folded, CHAR_LENGTH(content) FROM qa_message "
        "WHERE session_id=%s ORDER BY id" % session_id
    ))


if __name__ == "__main__":
    main()

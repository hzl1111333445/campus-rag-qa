# -*- coding: utf-8 -*-
"""检查 docs-src 下 PDF 是文本型还是扫描件型。"""
import sys, pathlib

try:
    import fitz  # PyMuPDF
except ImportError:
    print("缺少 PyMuPDF，请先 pip install pymupdf")
    sys.exit(1)

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r"E:\tmp\campus-ai-rag\docs-src")
rows = []
for pdf in sorted(root.rglob("*.pdf")):
    try:
        doc = fitz.open(pdf)
        pages = doc.page_count
        text = ""
        for page in doc:
            text += page.get_text()
        doc.close()
        chars = len(text.strip())
        kind = "文本型" if chars > pages * 50 else "扫描件(需OCR)"
        rows.append((pdf.parent.name, pdf.name, pages, chars, kind))
    except Exception as e:  # noqa
        rows.append((pdf.parent.name, pdf.name, 0, 0, f"打开失败:{e}"))

print(f"{'目录':<10}{'页数':>5}{'字符数':>9}  类型    文件名")
for cat, name, pages, chars, kind in rows:
    print(f"{cat:<10}{pages:>5}{chars:>9}  {kind:<12}{name}")

text_ok = sum(1 for r in rows if r[4] == "文本型")
print(f"\n合计 {len(rows)} 个 PDF：文本型 {text_ok} 个，扫描件 {len(rows) - text_ok} 个")

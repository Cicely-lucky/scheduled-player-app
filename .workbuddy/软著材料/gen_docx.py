"""
小云闹钟 - 软著材料 Word(.docx) 生成器

把两份 Markdown 稿件转成可直接提交的 Word 文档：
  1. 小云闹钟_源代码.docx      —— 等宽字体，每页严格 50 行，页脚自动页码
  2. 小云闹钟_产品说明书.docx   —— 宋体小四偏小，硬分页与 md 页码一一对应，页脚自动页码

要点：
  · 分页不依赖 Word 自动排版，而是在 md 的页码标记处插入硬分页符，
    这样每页行数完全可控，不会因段落折行导致页数漂移。
  · 页脚使用 PAGE / NUMPAGES 域，Word 打开后自动显示"第 X 页 / 共 Y 页"。
"""
import io
import os
import re
import sys

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt

sys.stdout.reconfigure(encoding="utf-8")

BASE = r"C:\Users\jiayanli\WorkBuddy\2026-08-27-09-36-38\scheduled-player-app\.workbuddy\软著材料"

CN_FONT = "宋体"
MONO_FONT = "Consolas"


# ------------------------------------------------------------
# 通用工具
# ------------------------------------------------------------
def set_run_font(run, ascii_font, east_font, size_pt, bold=False):
    run.font.name = ascii_font
    run.font.size = Pt(size_pt)
    run.font.bold = bold
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:ascii"), ascii_font)
    rfonts.set(qn("w:hAnsi"), ascii_font)
    rfonts.set(qn("w:eastAsia"), east_font)


def strip_inline(text):
    """去掉 Markdown 行内标记，保留纯文本"""
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"`(.+?)`", r"\1", text)
    text = text.replace("\\|", "|").replace("\\_", "_")
    return text


def add_para(doc, text, ascii_font=CN_FONT, east_font=CN_FONT, size=10.5,
             bold=False, space_before=0, space_after=0, line_spacing=1.0,
             align=None, indent_left=None):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(space_before)
    pf.space_after = Pt(space_after)
    pf.line_spacing = line_spacing
    if align is not None:
        p.alignment = align
    if indent_left is not None:
        pf.left_indent = Cm(indent_left)
    if text:
        run = p.add_run(text)
        set_run_font(run, ascii_font, east_font, size, bold)
    return p


def add_field(paragraph, instr):
    """插入 Word 域（如 PAGE / NUMPAGES）"""
    run = paragraph.add_run()
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instr_el = OxmlElement("w:instrText")
    instr_el.set(qn("xml:space"), "preserve")
    instr_el.text = instr
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.append(begin)
    run._r.append(instr_el)
    run._r.append(end)
    return run


def setup_doc(font_size_body, left_right_margin=1.5, header_text=None):
    doc = Document()
    sec = doc.sections[0]
    sec.page_width = Cm(21.0)
    sec.page_height = Cm(29.7)
    sec.top_margin = Cm(1.5)
    sec.bottom_margin = Cm(1.5)
    sec.left_margin = Cm(left_right_margin)
    sec.right_margin = Cm(left_right_margin)

    # 页眉：软件名称+版本号（软著审查惯例，与申请表一致）
    if header_text:
        hp = sec.header.paragraphs[0]
        hp.text = ""
        hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
        hr = hp.add_run(header_text)
        set_run_font(hr, CN_FONT, CN_FONT, 9)

    # 正文默认样式
    normal = doc.styles["Normal"]
    normal.font.name = CN_FONT
    normal.font.size = Pt(font_size_body)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), CN_FONT)

    # 页脚：第 X 页 / 共 Y 页
    footer_p = sec.footer.paragraphs[0]
    footer_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r1 = footer_p.add_run("第 ")
    set_run_font(r1, CN_FONT, CN_FONT, 9)
    add_field(footer_p, "PAGE")
    for r in footer_p.runs:
        set_run_font(r, CN_FONT, CN_FONT, 9)
    r2 = footer_p.add_run(" 页 / 共 ")
    set_run_font(r2, CN_FONT, CN_FONT, 9)
    add_field(footer_p, "NUMPAGES")
    for r in footer_p.runs:
        set_run_font(r, CN_FONT, CN_FONT, 9)
    r3 = footer_p.add_run(" 页")
    set_run_font(r3, CN_FONT, CN_FONT, 9)

    return doc


PAGE_MARKERS = (
    re.compile(r"^──── 第 \d+ 页 / 共 \d+ 页 ────\s*$"),   # 说明书
    re.compile(r"^> \*\*第 \d+ 页 / 共 \d+ 页\*\*\s*$"),   # 源代码
)


def is_page_marker(line):
    return any(p.match(line.strip()) for p in PAGE_MARKERS)


def is_table_sep(line):
    s = line.strip()
    return bool(re.match(r"^\|[\s:\-|]+\|$", s))


def split_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


# ------------------------------------------------------------
# Markdown -> docx
# ------------------------------------------------------------
def md_to_docx(md_path, out_path, body_font_size, code_font_size, title,
               left_right_margin=1.5, header_text=None):
    with io.open(md_path, "r", encoding="utf-8") as fp:
        raw_lines = fp.read().split("\n")

    doc = setup_doc(body_font_size, left_right_margin=left_right_margin,
                    header_text=header_text)

    pending_break = False     # 下一个内容段落前插入硬分页符
    in_code = False
    table_buf = []
    page_breaks = 0

    def flush_table():
        nonlocal table_buf
        if not table_buf:
            return
        rows = [r for r in table_buf if not is_table_sep(r)]
        table_buf = []
        if not rows:
            return
        cols = max(len(split_row(r)) for r in rows)
        t = doc.add_table(rows=0, cols=cols)
        t.style = "Table Grid"
        t.alignment = WD_TABLE_ALIGNMENT.CENTER
        for ri, r in enumerate(rows):
            cells = split_row(r)
            row = t.add_row()
            for ci in range(cols):
                cell = row.cells[ci]
                cell.text = ""
                para = cell.paragraphs[0]
                para.paragraph_format.space_before = Pt(1)
                para.paragraph_format.space_after = Pt(1)
                txt = strip_inline(cells[ci]) if ci < len(cells) else ""
                if txt:
                    run = para.add_run(txt)
                    set_run_font(run, CN_FONT, CN_FONT, body_font_size - 1.5,
                                 bold=(ri == 0))
        add_para(doc, "", size=6)

    first_content_done = False

    for line in raw_lines:
        # ---- 页码标记：触发下一页 ----
        if is_page_marker(line):
            flush_table()
            in_code = False
            pending_break = True
            continue

        # ---- 代码围栏 ----
        if line.strip().startswith("```"):
            flush_table()
            in_code = not in_code
            continue

        if in_code:
            if pending_break:
                doc.add_page_break()
                page_breaks += 1
                pending_break = False
            p = add_para(doc, line if line.strip() else "",
                         ascii_font=MONO_FONT, east_font=CN_FONT,
                         size=code_font_size, line_spacing=1.0)
            continue

        # ---- 表格 ----
        if line.strip().startswith("|"):
            table_buf.append(line)
            continue
        else:
            if table_buf:
                if pending_break:
                    doc.add_page_break()
                    page_breaks += 1
                    pending_break = False
                flush_table()

        s = line.rstrip()
        st = s.strip()

        # ---- 空行 ----
        if not st:
            continue

        # ---- 水平线：跳过（由标题层级自然分隔）----
        if st in ("---", "***", "___") or re.match(r"^-{5,}$", st):
            continue

        if pending_break:
            doc.add_page_break()
            page_breaks += 1
            pending_break = False

        # ---- 标题 ----
        m = re.match(r"^(#{1,4})\s+(.*)$", st)
        if m:
            level = len(m.group(1))
            text = strip_inline(m.group(2))
            sizes = {1: body_font_size + 6, 2: body_font_size + 3,
                     3: body_font_size + 1, 4: body_font_size}
            align = WD_ALIGN_PARAGRAPH.CENTER if level == 1 else None
            add_para(doc, text, size=sizes[level], bold=True,
                     space_before=8 if level > 1 else 12,
                     space_after=6, align=align)
            continue

        # ---- 引用块 ----
        if st.startswith(">"):
            txt = strip_inline(st.lstrip("> ").strip())
            if txt:
                add_para(doc, txt, size=body_font_size - 1,
                         indent_left=0.8, space_after=2)
            continue

        # ---- 列表 ----
        if st.startswith(("- ", "* ", "· ")):
            txt = strip_inline(st[2:].strip())
            add_para(doc, "· " + txt, size=body_font_size,
                     indent_left=0.5, space_after=2)
            continue

        # ---- 普通段落 ----
        add_para(doc, strip_inline(st), size=body_font_size, space_after=3)

    flush_table()
    doc.save(out_path)
    print(f"[OK] {title}")
    print(f"     输出：{out_path}")
    print(f"     硬分页符：{page_breaks} 个 → 共 {page_breaks + 1} 页")
    return page_breaks + 1


if __name__ == "__main__":
    p1 = md_to_docx(
        os.path.join(BASE, "小云闹钟_源代码.md"),
        os.path.join(BASE, "小云闹钟_源代码.docx"),
        body_font_size=9, code_font_size=7.5, title="源代码文档",
        left_right_margin=1.0, header_text="小云闹钟定时播放软件V1.0")
    print()
    p2 = md_to_docx(
        os.path.join(BASE, "小云闹钟_产品说明书.md"),
        os.path.join(BASE, "小云闹钟_产品说明书.docx"),
        body_font_size=10.5, code_font_size=9, title="产品说明书")

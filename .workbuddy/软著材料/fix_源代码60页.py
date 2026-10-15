# -*- coding: utf-8 -*-
r"""
小云闹钟_源代码V1.0.docx 整理脚本：
1. 去掉每页开头的空分页符段落（页面上显示的第51行空行），
   改用段落属性 pageBreakBefore 实现翻页；
2. 全文 75 页 → 保留前 30 页 + 后 30 页 = 60 页（软著提交要求），
   删除中间第 31~45 页。
直接原地保存到 D:\WORK\APP\定时播放\软著\小云闹钟_源代码V1.0.docx
"""
import sys
from docx import Document
from docx.oxml.ns import qn

sys.stdout.reconfigure(encoding="utf-8")

PATH = r"D:\WORK\APP\定时播放\软著\小云闹钟_源代码V1.0.docx"
OUT = sys.argv[1] if len(sys.argv) > 1 else PATH   # 原文件被占用时可另存

doc = Document(PATH)
paras = list(doc.paragraphs)

# ---- 1. 找出所有分页符段落（段落中含 <w:br w:type="page"/>）----
def is_break_para(p):
    return any(br.get(qn("w:type")) == "page" for br in p._element.iter(qn("w:br")))

break_idx = [i for i, p in enumerate(paras) if is_break_para(p)]
n_pages = len(break_idx) + 1
print(f"分页符段落：{len(break_idx)} 个 → 共 {n_pages} 页")

# 页边界：page k(1起) 内容 = (break_idx[k-2], break_idx[k-1]) 区间
# page 1 = [0, break_idx[0])；page k(2..n) = [break_idx[k-2], break_idx[k-1])，首段是分页符段落
def page_range(k):
    """返回第 k 页的段落下标区间 [start, end)（含分页符段落）"""
    if k == 1:
        return 0, break_idx[0]
    if k == n_pages:
        return break_idx[-1], len(paras)
    return break_idx[k - 2], break_idx[k - 1]

# ---- 2. 删除中间页（第31页 ~ 第 n-30 页）----
keep_lo, keep_hi = 30, n_pages - 30   # 保留 1..30 与 n-29..n
del_paras = []
for k in range(keep_lo + 1, keep_hi + 1):
    s, e = page_range(k)
    del_paras.extend(paras[s:e])
print(f"删除中间页：第 {keep_lo+1}~{keep_hi} 页（共 {keep_hi-keep_lo} 页，{len(del_paras)} 个段落）")

# ---- 3. 分页符段落 → 删除该段落，并在后一页首段设置"段前分页" ----
converted = 0
for bi in break_idx:
    bp = paras[bi]
    nxt = bp._element.getnext()          # 分页符段落的下一个段落 = 新页首段
    if nxt is not None and nxt.tag == qn("w:p"):
        # 设置段前分页
        pPr = nxt.find(qn("w:pPr"))
        if pPr is None:
            pPr = nxt.makeelement(qn("w:pPr"), {})
            nxt.insert(0, pPr)
        if pPr.find(qn("w:pageBreakBefore")) is None:
            pbb = pPr.makeelement(qn("w:pageBreakBefore"), {})
            pPr.insert(0, pbb)
        converted += 1
    del_paras.append(bp)
print(f"分页符段落转段前分页：{converted} 个")

# ---- 4. 统一删除 ----
for p in del_paras:
    el = p._element
    if el.getparent() is not None:
        el.getparent().remove(el)
print(f"共删除段落：{len(del_paras)} 个")

doc.save(OUT)
print(f"[OK] 已保存：{OUT}")

# ---- 5. 复核 ----
doc2 = Document(OUT)
ps2 = doc2.paragraphs
brk2 = sum(1 for p in ps2 if is_break_para(p))
pbb2 = sum(1 for p in ps2 if p.paragraph_format.page_break_before)
nonempty = [p for p in ps2 if p.text.strip()]
print(f"复核：总段落 {len(ps2)}，分页符段落 {brk2}，段前分页 {pbb2}（应为 {keep_lo + (n_pages-keep_hi)} 页起始）")
print(f"复核：非空代码段落 {len(nonempty)}，折算页数 {len(nonempty)/50:.1f}（约60页）")

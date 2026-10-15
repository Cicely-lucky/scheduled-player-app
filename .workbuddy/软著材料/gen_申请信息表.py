# -*- coding: utf-8 -*-
"""生成《小云闹钟_软著申请信息表.docx》：软著在线申请表全部字段的填报参考"""
import os
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "小云闹钟_软著申请信息表.docx")

doc = Document()

# 全局字体
style = doc.styles["Normal"]
style.font.name = "Calibri"
style.font.size = Pt(10.5)
style.element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")

def set_font(run, size=10.5, bold=False, color=None, name="微软雅黑"):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = "Calibri"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), name)
    if color:
        run.font.color.rgb = RGBColor(*color)

def h1(text):
    p = doc.add_paragraph()
    set_font(p.add_run(text), size=14, bold=True)
    p.paragraph_format.space_before = Pt(14)
    p.paragraph_format.space_after = Pt(6)

def para(text, size=10.5, bold=False, color=None, indent=False):
    p = doc.add_paragraph()
    if indent:
        p.paragraph_format.first_line_indent = Pt(21)
    set_font(p.add_run(text), size=size, bold=bold, color=color)
    return p

def make_table(rows_data, widths=None, header=True):
    t = doc.add_table(rows=len(rows_data), cols=len(rows_data[0]))
    t.style = "Table Grid"
    for i, row in enumerate(rows_data):
        for j, cell_text in enumerate(row):
            cell = t.cell(i, j)
            cell.text = ""
            p = cell.paragraphs[0]
            r = p.add_run(cell_text)
            set_font(r, size=10, bold=(header and i == 0))
            if j == 0 and not header:
                set_font(r, size=10, bold=True)
    if widths:
        for j, w in enumerate(widths):
            for i in range(len(rows_data)):
                t.cell(i, j).width = Cm(w)
    doc.add_paragraph()
    return t

# ===== 封面标题 =====
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_font(p.add_run("小云闹钟软件著作权登记"), size=18, bold=True)
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_font(p.add_run("申 请 信 息 表"), size=18, bold=True)
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_font(p.add_run("（中国版权保护中心 在线填报参考 · 2026-09-14 整理）"), size=10, color=(0x66, 0x66, 0x66))
doc.add_paragraph()

para("说明：本表按中国版权保护中心「计算机软件著作权登记申请」在线填报页面的字段顺序整理，"
     "蓝字【 】内为需本人补充的隐私信息。已备好的两份核心文档（源代码、说明书）在 "
     ".workbuddy\\软著材料\\ 目录下。", color=(0x66, 0x66, 0x66))

# ===== 一、软件基本信息 =====
h1("一、软件基本信息")
make_table([
    ["字段", "填写内容", "说明"],
    ["软件全称", "小云闹钟定时播放软件", "命名规范：有含义的名称 + 软件/App；须与两份文档页眉一致"],
    ["版本号", "V1.0", "对应工程 pubspec.yaml 的 1.0.0+2"],
    ["软件简称", "小云闹钟", "可不填"],
    ["开发完成日期", "2026年9月12日", "最后一次功能提交日期（早于申请日即可）"],
    ["首次发表日期", "未发表", "选「未发表」最简；GitHub 公开发布 APK 亦可记为已发表"],
    ["开发方式", "独立开发", "无合作方、无委托方"],
    ["权利取得方式", "原始取得", "本人独立开发"],
    ["权利范围", "全部权利", "默认项"],
    ["源程序量", "3640 行", "与《源代码》文档的原始代码行数一致（15个源文件）"],
    ["编程语言", "Dart、Kotlin", "界面逻辑 Dart，原生桥 Kotlin"],
], widths=[3.5, 5.5, 7.5])

# ===== 二、著作权人信息 =====
h1("二、著作权人信息")
para("本 App 包名为 com.cici.scheduledplayer（个人作品），建议按「个人」类型申请；"
     "若要以公司名义申请，需提供公司营业执照信息并加盖公章。")
make_table([
    ["字段", "填写内容"],
    ["著作权人类型", "自然人（个人）"],
    ["姓名", "【身份证姓名】"],
    ["证件号码", "【身份证号】"],
    ["住所地址", "【身份证地址或常住地址】"],
    ["联系电话", "【手机号】"],
    ["电子邮箱", "【邮箱】（接收受理通知）"],
    ["邮寄地址", "【收证书的地址】"],
], widths=[4, 8])

# ===== 三、软件功能与技术特点 =====
h1("三、软件功能与技术特点（申请表填写栏，可直接粘贴）")
para("开发目的：", bold=True)
para("为满足个人用户定时收听、收看内容的需求而开发，典型场景包括定时自动播放英语听力视频、"
     "晨间课程音频等，解决用户到点手动操作、忘记播放的问题。", indent=True)
para("主要功能：", bold=True)
for f in [
    "1. 定时任务管理：用户可在软件内创建、编辑、删除定时任务，为每个任务设定触发时间与重复频率，支持单次执行、每日重复、每周重复三种模式；全部任务以列表形式在首页集中展示，随时查看与调整。",
    "2. 定时打开网址：支持将任意网址设为任务目标；针对B站视频场景深度优化，用户可直接粘贴BV号、b23.tv短链接、客户端深链接或带分集参数的链接，软件自动识别并规范化为标准完整链接，无需手动整理格式。",
    "3. 定时播放音视频：支持选择本地音频、视频文件作为任务内容，到点自动调起播放；支持循环播放模式与按时长锁定播放（播满设定时长后自动停止），适配晨间听力训练、课程伴学等使用场景。",
    "4. 到点通知提醒：任务触发时以全屏通知醒目提醒用户，点击通知即可直接执行对应任务，无需进入软件手动查找；熄屏与后台状态下提醒同样有效。",
    "5. 任务持久化：任务数据采用本地持久化存储，软件重启、手机重启或版本升级后任务均自动恢复；系统开机后自动重新注册全部闹钟，确保任务不丢失、不错漏。",
    "6. 系统适配：针对小米等国产手机系统的后台进程管控与权限管理机制做了专项适配，引导用户开启悬浮窗、后台弹出、锁屏显示等必要权限，保障定时任务在各类机型上可靠执行。",
]:
    para(f, indent=True)
para("技术特点：", bold=True)
para("本软件基于 Flutter 跨平台框架开发，界面逻辑采用 Dart 语言；通过 Kotlin 原生桥接层调用系统 "
     "AlarmManager 实现精确闹钟调度，熄屏状态下也能准点触发；任务执行通过 Intent 路由并按目标应用包名 "
     "处理任务栈，解决了第三方应用抢占链接导致的播放异常；配合开机广播与启动重调度机制，实现任务的全场景恢复。", indent=True)

# ===== 四、开发与运行环境 =====
h1("四、开发与运行环境（申请表环境栏）")
make_table([
    ["字段", "填写内容"],
    ["开发的硬件环境", "Intel Core i5 及以上 PC，16GB 内存"],
    ["运行该软件的硬件环境", "Android 8.0 及以上智能手机（ARM64）"],
    ["开发该软件的操作系统", "Microsoft Windows 11"],
    ["软件开发环境 / 开发工具", "Flutter SDK 3.x、Dart SDK、Android Studio、Gradle、GitHub Actions CI"],
    ["该软件的运行平台 / 操作系统", "Android 8.0 及以上（兼容 MIUI / HyperOS）"],
    ["软件运行支撑环境 / 支持软件", "Android Runtime，无其他特殊依赖"],
], widths=[5, 8])

# ===== 五、随申请提交的材料清单 =====
h1("五、随申请提交的材料清单")
make_table([
    ["#", "材料", "状态 / 要求"],
    ["1", "软件著作权登记申请表", "在线填报后系统生成，打印并签字"],
    ["2", "源代码文档（前、后各连续30页，共60页；不足60页全交）", "✓ 已备：小云闹钟_源代码.docx（76页，取前30+后30）"],
    ["3", "产品文档（说明书等，不足60页全交）", "✓ 已备：小云闹钟_产品使用说明书_简化版.docx（19页，全部提交）"],
    ["4", "身份证明", "个人：身份证正反面复印件；公司：营业执照副本复印件加盖公章"],
], widths=[1, 6, 9])

# ===== 六、办理流程与注意事项 =====
h1("六、办理流程与注意事项")
para("办理流程：", bold=True)
para("中国版权保护中心官网（ccopyright.com.cn）注册账号 → 选择「计算机软件著作权登记申请」在线填报 → "
     "上传 / 邮寄材料 → 等待受理通知 → 审查（约 30-40 个工作日）→ 领取登记证书。登记免费，无需缴费。", indent=True)
para("注意事项：", bold=True)
for n in [
    "1. 名称一致性：软件全称 + 版本号必须与源代码、说明书两份文档的页眉完全一致，提交前逐字核对；",
    "2. 日期一致性：开发完成日期须早于申请日，两份文档内容中不能出现晚于该日期的描述；",
    "3. 签字一致：个人申请时，申请表签字须与身份证姓名一致，不要签昵称；",
    "4. 发表状态：GitHub 仓库虽公开，但 APK 分发范围小，按「未发表」填报最简、审查更快；",
    "5. 版本对应：本次申请 V1.0 后，将来功能升级可再申请 V2.0，提供新版文档与说明即可；",
    "6. 行数一致：申请表「源程序量」填 3640 行，与《源代码》文档一致，避免审查疑问。",
]:
    para(n, indent=True)

doc.save(OUT)
print("saved:", OUT)

# 顺带核对两份已备文档的页眉是否含软件名+版本号
from docx import Document as D
for name in ["小云闹钟_源代码.docx", "小云闹钟_产品说明书.docx"]:
    path = os.path.join(os.path.dirname(OUT), name)
    if os.path.exists(path):
        d = D(path)
        texts = []
        for sec in d.sections:
            for p in sec.header.paragraphs:
                if p.text.strip():
                    texts.append(p.text.strip())
        print(name, "页眉:", texts[:2] if texts else "(无页眉文字!)")

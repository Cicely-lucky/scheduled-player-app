"""
小云闹钟 - 软件源代码文档生成器
按软著申请规范生成：每页 50 行，连续编号，文件边界严格分页。
"""
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = r"C:\Users\jiayanli\WorkBuddy\2026-08-27-09-36-38\scheduled-player-app"
OUT = os.path.join(ROOT, ".workbuddy", "软著材料", "小云闹钟_源代码.md")

# 文件顺序（按业务层级：Dart 模型/服务/页面 → Kotlin 原生层）
FILES = [
    ("lib/main.dart",                                            "Dart", "应用入口与全局配置"),
    ("lib/models/task.dart",                                     "Dart", "定时任务数据模型"),
    ("lib/storage/task_store.dart",                              "Dart", "任务本地持久化"),
    ("lib/services/perm.dart",                                   "Dart", "Android 权限服务桥"),
    ("lib/scheduler/scheduler.dart",                             "Dart", "双链调度引擎"),
    ("lib/pages/home_page.dart",                                 "Dart", "首页（任务总览）"),
    ("lib/pages/create_page.dart",                               "Dart", "任务创建/编辑页"),
    ("lib/pages/detail_page.dart",                               "Dart", "任务详情页"),
    ("lib/widgets/player_page.dart",                             "Dart", "音视频播放组件"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/MainActivity.kt",         "Kotlin", "Flutter 主 Activity"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/UrlAlarmScheduler.kt",    "Kotlin", "原生闹钟管理器"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/PlaybackReceiver.kt",     "Kotlin", "广播接收器"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/PlaybackService.kt",      "Kotlin", "前台播放服务"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/UrlBridgeActivity.kt",    "Kotlin", "URL 桥接 Activity"),
    ("android/app/src/main/kotlin/com/cici/scheduledplayer/UrlBridgeService.kt",     "Kotlin", "URL 桥接前台服务"),
]

LINES_PER_PAGE = 50
SOFTWARE_NAME = "小云闹钟"
SOFTWARE_VER  = "V1.0.0"
AUTHOR       = "Cicely"
DEVELOPER    = "Cicely-lucky"
DATE_STR     = "2026-09-12"


def fmt_line(line: str) -> str:
    """保留原始行内容；仅去掉行尾换行符（缩进与空行原样保留）"""
    return line.rstrip("\r\n")


def build():
    # ---------- 第一步：把所有正文行收集到一个扁平列表 ----------
    # 文件分隔标记行也计入行号，保证"每页 50 行连续编号"真实成立
    body = []          # 正文全部行
    file_stats = []    # (相对路径, 行数)
    code_lines = 0     # 原始代码行数（不含分隔标记）

    for idx, (rel, lang, desc) in enumerate(FILES, 1):
        path = os.path.join(ROOT, rel)
        with open(path, "r", encoding="utf-8") as fp:
            lines = [fmt_line(x) for x in fp.readlines()]
        file_stats.append((rel, len(lines)))
        code_lines += len(lines)

        # 文件分隔标记（注释风格，两种语言通用）
        marker = ("// " + "=" * 78)
        body.append(marker)
        body.append(f"// 文件 {idx:02d}/{len(FILES)}：{rel}")
        body.append(f"// 语言：{lang}　　说明：{desc}　　行数：{len(lines)}")
        body.append(marker)
        body.extend(lines)
        body.append("")          # 文件末尾留一空行，避免与下一文件粘连

    total_lines = len(body)
    total_pages = (total_lines + LINES_PER_PAGE - 1) // LINES_PER_PAGE

    # ---------- 第二步：生成文档头 ----------
    md = []
    md.append(f"# {SOFTWARE_NAME}定时播放软件 源代码\n")
    md.append(f"\n- **软件全称**：{SOFTWARE_NAME}定时播放软件\n")
    md.append(f"\n- **软件简称**：{SOFTWARE_NAME}\n")
    md.append(f"\n- **软件版本**：{SOFTWARE_VER}\n")
    md.append(f"\n- **开发语言**：Dart / Kotlin\n")
    md.append(f"\n- **运行平台**：Android 8.0 及以上（API 26+，已验证 Android 16）\n")
    md.append(f"\n- **开发者**：{AUTHOR}\n")
    md.append(f"\n- **代码仓库**：https://github.com/{DEVELOPER}/scheduled-player-app\n")
    md.append(f"\n- **文档日期**：{DATE_STR}\n")

    md.append("\n---\n")
    md.append("\n## 文档说明\n")
    md.append("\n本文档按《计算机软件著作权登记办法》要求整理：\n")
    md.append("\n1. 每页 **50 行**，页内连续编号，不跳行、不抽页；\n")
    md.append("\n2. 原始源代码共 **%d 行**（%d 个源文件），排版时按文件加入分隔标记行，"
              "正文合计 **%d 行**、共 **%d 页**；\n"
              % (code_lines, len(FILES), total_lines, total_pages))
    md.append("\n3. 涵盖应用入口、数据模型、调度引擎、UI 页面与 Android 原生服务全部源码；\n")
    md.append("\n4. 文件按业务层级排序：Flutter 端（9 个 Dart 文件）→ Android 原生层（6 个 Kotlin 文件）；\n")
    md.append("\n5. 提交登记时可直接使用同目录下的 `小云闹钟_源代码.docx`："
              "第 1 页为封面与文件清单，源代码正文自第 2 页起、每页 50 行，"
              "页脚由 Word 自动生成连续页码。\n")

    md.append("\n---\n")
    md.append("\n## 文件清单\n")
    md.append("\n| 序号 | 文件路径 | 语言 | 行数 |")
    md.append("\n|------|----------|------|------|")
    for i, (rel, n) in enumerate(file_stats, 1):
        lang = next(t for r, t, _ in FILES if r == rel)
        md.append(f"\n| {i:02d} | `{rel}` | {lang} | {n} |")
    md.append(f"\n| **合计** | — | — | **{sum(n for _, n in file_stats)}** |")

    md.append("\n\n---\n\n")
    md.append("## 源代码正文\n")

    # ---------- 第三步：按 50 行严格分页输出 ----------
    for pg in range(total_pages):
        chunk = body[pg * LINES_PER_PAGE:(pg + 1) * LINES_PER_PAGE]
        md.append(f"\n> **第 {pg + 1} 页 / 共 {total_pages} 页**\n\n")
        md.append("```\n")
        md.append("\n".join(chunk))
        md.append("\n```\n")

    with open(OUT, "w", encoding="utf-8") as fp:
        fp.write("".join(md))

    print(f"[OK] 源代码文档已生成：{OUT}")
    print(f"     文件数：{len(FILES)}")
    print(f"     正文总行数：{total_lines}")
    print(f"     总页数：{total_pages}（每页 {LINES_PER_PAGE} 行）")
    print(f"     软著要求：前 30 页 + 后 30 页 = 60 页 / 3000 行")
    ok = total_pages >= 60
    print(f"     {'✓ 满足' if ok else '✗ 不足'}")


if __name__ == "__main__":
    build()

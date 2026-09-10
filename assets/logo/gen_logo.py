"""生成小云闹钟应用图标（1024x1024 PNG）"""
from PIL import Image, ImageDraw
import math

SIZE = 1024
PAD = 80
img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# 主色板
SKY_TOP = (125, 211, 252)      # #7DD3FC 天蓝
SKY_BOTTOM = (165, 228, 255)   # #A5E4FF 浅天蓝
CLOUD = (255, 255, 255)        # 纯白
CLOUD_SHADOW = (240, 248, 255) # 云朵阴影
ACCENT = (56, 189, 248)        # #38BDF8 深蓝
DARK = (15, 23, 42)            # #0F172A 深色文字/指针

cx, cy = SIZE // 2, SIZE // 2
r = (SIZE - PAD * 2) // 2

# 1. 画圆形背景（渐变通过多圈模拟）
for i in range(r, 0, -1):
    ratio = i / r
    rr = int(SKY_TOP[0] + (SKY_BOTTOM[0] - SKY_TOP[0]) * ratio)
    gg = int(SKY_TOP[1] + (SKY_BOTTOM[1] - SKY_TOP[1]) * ratio)
    bb = int(SKY_TOP[2] + (SKY_BOTTOM[2] - SKY_TOP[2]) * ratio)
    draw.ellipse([cx - i, cy - i, cx + i, cy + i], fill=(rr, gg, bb, 255))

# 2. 画云朵（三个圆叠加）
cloud_y = cy - 40
cloud_r_base = 180
draw.ellipse([cx - cloud_r_base, cloud_y - cloud_r_base * 0.6,
              cx + cloud_r_base, cloud_y + cloud_r_base * 0.6], fill=CLOUD)
draw.ellipse([cx - cloud_r_base * 1.25, cloud_y - cloud_r_base * 0.15,
              cx - cloud_r_base * 0.35, cloud_y + cloud_r_base * 0.75], fill=CLOUD)
draw.ellipse([cx + cloud_r_base * 0.35, cloud_y - cloud_r_base * 0.15,
              cx + cloud_r_base * 1.25, cloud_y + cloud_r_base * 0.75], fill=CLOUD)

# 云朵底部略平
bottom_y = cloud_y + cloud_r_base * 0.55
draw.rectangle([cx - cloud_r_base * 1.15, bottom_y - 20,
                cx + cloud_r_base * 1.15, bottom_y + 30], fill=CLOUD)

# 3. 画小闹钟（放在云朵右下方，部分覆盖）
clock_cx = cx + 130
clock_cy = cloud_y + 70
clock_r = 85
# 表盘
draw.ellipse([clock_cx - clock_r, clock_cy - clock_r,
              clock_cx + clock_r, clock_cy + clock_r], fill=(255, 255, 255, 255))
# 表圈
draw.ellipse([clock_cx - clock_r, clock_cy - clock_r,
              clock_cx + clock_r, clock_cy + clock_r], outline=ACCENT, width=14)
# 两个铃铛
bell_r = 22
bell_y = clock_cy - clock_r - 5
for dx in [-35, 35]:
    bx = clock_cx + dx
    draw.ellipse([bx - bell_r, bell_y - bell_r - 10, bx + bell_r, bell_y + bell_r - 10], fill=ACCENT)
# 指针（8:00 左右）
hand_w = 12
# 时针
angle_h = 240 * math.pi / 180
hx = clock_cx + math.cos(angle_h) * 38
hy = clock_cy + math.sin(angle_h) * 38
draw.line([clock_cx, clock_cy, hx, hy], fill=DARK, width=hand_w, joint='curve')
# 分针
angle_m = 270 * math.pi / 180
mx = clock_cx + math.cos(angle_m) * 52
my = clock_cy + math.sin(angle_m) * 52
draw.line([clock_cx, clock_cy, mx, my], fill=DARK, width=hand_w - 3, joint='curve')
# 中心点
draw.ellipse([clock_cx - 10, clock_cy - 10, clock_cx + 10, clock_cy + 10], fill=ACCENT)

# 4. 画闭眼的可爱表情（可选：让云朵更生动）
eye_y = cloud_y - 20
for ex in [cx - 70, cx + 70]:
    # 弯弯的笑眼
    draw.arc([ex - 20, eye_y - 10, ex + 20, eye_y + 10], start=200, end=340, fill=DARK, width=6)
# 小嘴巴
mouth_y = cloud_y + 30
draw.arc([cx - 18, mouth_y - 12, cx + 18, mouth_y + 12], start=20, end=160, fill=DARK, width=6)

img.save('C:/Users/jiayanli/WorkBuddy/2026-08-27-09-36-38/scheduled-player-app/assets/logo/logo_1024.png')
print('saved logo_1024.png')

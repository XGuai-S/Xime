"""用 Android 版 Xime logo 生成 Windows 语言栏图标。

- xime.ico      ：原样 logo（多尺寸），DLL 主图标 / 安装包用
- xime_zh.ico   ：logo + 右下角「中」角标（中文状态）
- xime_en.ico   ：logo + 右下角「英」角标（英文状态）

语言栏实际只用 16~20px。此尺寸下汉字字体渲染会糊成一团，因此小尺寸改用
几何笔画手绘；大尺寸仍用字体（更美观）。
"""
import os
from PIL import Image, ImageDraw, ImageFont

SRC = r'D:\AIWork\Xime-3.4.09-source\app\src\main\res\mipmap-xxxhdpi\ic_launcher.webp'
OUT = r'D:\AIWork\Xime-3.4.09-source\desktop\res'
os.makedirs(OUT, exist_ok=True)

SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]

# 小于此尺寸用几何笔画，否则用字体。
VECTOR_MAX = 24

FONT_CANDIDATES = [
    r'C:\Windows\Fonts\msyhbd.ttc',
    r'C:\Windows\Fonts\msyh.ttc',
    r'C:\Windows\Fonts\simhei.ttf',
]

BLUE = (0x1A, 0x6F, 0xD9, 255)
GREY = (0x5F, 0x66, 0x70, 255)
WHITE = (255, 255, 255, 255)


def load_font(px):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, px)
            except Exception:
                continue
    return ImageFont.load_default()


base = Image.open(SRC).convert('RGBA')


def scale(s):
    return base.resize((s, s), Image.LANCZOS)


def save_ico(images, path):
    images[0].save(path, format='ICO', sizes=[(s, s) for s in SIZES],
                   append_images=images[1:])
    print('WROTE %s (%dB)' % (path, os.path.getsize(path)))


# 1) 原始 logo
save_ico([scale(s) for s in SIZES], os.path.join(OUT, 'xime.ico'))


def draw_zh_vector(d, x0, y0, b):
    """在 b×b 方块内用矩形笔画画「中」：竖中线 + 扁口。

    小尺寸（16px）下 b 只有 7~8 像素，必须用粗笔画、大开口，否则糊成一团。
    """
    t = 2 if b >= 10 else 1      # 笔画粗细（16px 时 b≈7，用 1px 但配合大留白）
    m = max(1, b // 7)           # 内边距尽量小，把空间留给笔画
    cx = (x0 + x0 + b - 1) // 2
    # 竖中线（贯穿，这是「中」最关键的识别特征）
    d.rectangle([cx, y0 + m, cx + t - 1, y0 + b - m - 1], fill=WHITE)
    # 扁口：上下两条长横
    left = x0 + m
    right = x0 + b - m - 1
    top = y0 + b // 3
    bot = y0 + b - b // 3
    d.rectangle([left, top, right, top + t - 1], fill=WHITE)
    d.rectangle([left, bot - t + 1, right, bot], fill=WHITE)
    # 口的左右两条短竖（小尺寸省略，避免糊）
    if b >= 12:
        d.rectangle([left, top, left + t - 1, bot], fill=WHITE)
        d.rectangle([right - t + 1, top, right, bot], fill=WHITE)


def draw_en_vector(d, x0, y0, b):
    """小尺寸下的「英」不可辨，改用字母 E（English 的首字母），一眼可读。"""
    t = 2 if b >= 10 else 1
    m = max(1, b // 6)
    left = x0 + m
    right = x0 + b - m - 1
    top = y0 + m
    bot = y0 + b - m - 1
    # 竖
    d.rectangle([left, top, left + t - 1, bot], fill=WHITE)
    # 三横（上、中、下）
    d.rectangle([left, top, right, top + t - 1], fill=WHITE)
    mid = (top + bot) // 2
    d.rectangle([left, mid, right - (right - left) // 5, mid + t - 1], fill=WHITE)
    d.rectangle([left, bot - t + 1, right, bot], fill=WHITE)


def with_badge(glyph, bg, vector_fn):
    """小尺寸（语言栏 16~20px）只用状态色块，最大化可读性；
    大尺寸才叠加在 logo 上，保留品牌识别。"""
    out = []
    for s in SIZES:
        if s <= 20:
            # 小尺寸：整块底色 + 大字形，没有 logo 干扰。
            img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
            d = ImageDraw.Draw(img)
            r = max(1, s // 5)
            d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=bg)
            vector_fn(d, 0, 0, s)
        else:
            img = scale(s).copy()
            d = ImageDraw.Draw(img)
            b = max(10, int(s * 0.52))
            x0 = s - b
            y0 = s - b
            r = max(1, b // 4)
            d.rounded_rectangle([x0, y0, s - 1, s - 1], radius=r, fill=bg,
                                outline=(255, 255, 255, 245), width=1)
            font = load_font(max(7, int(b * 0.8)))
            try:
                box = d.textbbox((0, 0), glyph, font=font)
                w = box[2] - box[0]
                h = box[3] - box[1]
                d.text((x0 + (b - w) / 2 - box[0], y0 + (b - h) / 2 - box[1]),
                       glyph, font=font, fill=WHITE)
            except Exception:
                vector_fn(d, x0, y0, b)
        out.append(img)
    return out


zh = with_badge('中', BLUE, draw_zh_vector)
en = with_badge('英', GREY, draw_en_vector)
save_ico(zh, os.path.join(OUT, 'xime_zh.ico'))
save_ico(en, os.path.join(OUT, 'xime_en.ico'))

# 预览
zh[-1].save(os.path.join(OUT, 'preview_zh.png'))
en[-1].save(os.path.join(OUT, 'preview_en.png'))
scale(256).save(os.path.join(OUT, 'preview_plain.png'))
# 16px 放大预览（NEAREST，便于看像素是否清晰）
for name, imgs in (('zh', zh), ('en', en)):
    small = imgs[SIZES.index(16)].resize((96, 96), Image.NEAREST)
    small.save(os.path.join(OUT, 'small_%s.png' % name))
print('DONE')

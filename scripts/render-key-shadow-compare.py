"""键盘按键立体感方案对比图（B 方案：预渲染 9-patch 位图）。

三种画法并排：
  A 无立体感（当前线上现状：阴影被关闭，只剩按下缩放）
  B 预渲染位图（本方案：底部软阴影 + 顶部高光 + 底部厚度，每帧一次 blit）
  C 每帧 path 阴影（已实测掉帧，仅作效果参照）

只生成示意 PNG，不读取也不修改任何应用资源。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

SCALE = 3
KEY_W, KEY_H = 64, 48
GAP = 10
RADIUS = 9
PANEL = (18, 18, 21)
KEY_BG = (62, 62, 70)
KEY_BG_PRESSED = (46, 46, 53)
LABEL = (200, 200, 212)
KEY_TEXT = (240, 240, 245)


def cjk_font(size):
    for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "simsun.ttc"):
        path = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def rounded(size, radius, fill):
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=fill
    )
    return img


def make_key(kind, pressed=False, letter=""):
    W, H = KEY_W * SCALE, KEY_H * SCALE
    r = RADIUS * SCALE
    face = KEY_BG_PRESSED if pressed else KEY_BG

    # B 用柔和软阴影；C 用更小半径、更硬的阴影（path 描边风格）
    if kind == "B":
        blur, offset, alpha = 6 * SCALE, 3 * SCALE, 190
    else:
        blur, offset, alpha = 3 * SCALE, 2 * SCALE, 150
    if pressed:
        blur = max(SCALE, blur - 2 * SCALE)
        offset = max(SCALE, offset - SCALE)
        alpha = int(alpha * 0.75)

    pad = blur * 3
    canvas = Image.new("RGBA", (W + pad * 2, H + pad * 2), (0, 0, 0, 0))

    # 1) 底部软阴影（B/C 都有；A 没有）
    if kind != "A":
        sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle(
            [pad, pad + offset, pad + W - 1, pad + H - 1],
            radius=r,
            fill=(0, 0, 0, alpha),
        )
        canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))

    # 2) 键面
    canvas.alpha_composite(rounded((W, H), r, face + (255,)), (pad, pad))

    if kind != "A":
        # 3) 顶部高光：竖向渐隐，用圆角遮罩裁进键面
        grad_h = max(3, H // 2)
        grad = Image.new("RGBA", (W, grad_h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(grad)
        peak = 90 if kind == "B" else 55
        for y in range(grad_h):
            t = y / grad_h
            gd.line([(0, y), (W, y)], fill=(255, 255, 255, int(peak * (1 - t) ** 2.2)))
        clip = rounded((W, H), r, (255, 255, 255, 255)).crop((0, 0, W, grad_h))
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        layer.paste(grad, (pad, pad), clip)
        canvas.alpha_composite(layer)

        # 4) 底部内侧压暗：形成「厚度」
        inner_h = max(4, H // 3)
        inner = Image.new("RGBA", (W, inner_h), (0, 0, 0, 0))
        idr = ImageDraw.Draw(inner)
        depth = 70 if kind == "B" else 45
        for y in range(inner_h):
            t = y / inner_h
            idr.line([(0, y), (W, y)], fill=(0, 0, 0, int(depth * t ** 1.4)))
        clip2 = rounded((W, H), r, (255, 255, 255, 255)).crop((0, H - inner_h, W, H))
        layer2 = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        layer2.paste(inner, (pad, pad + H - inner_h), clip2)
        canvas.alpha_composite(layer2)

    # 5) 字母
    if letter:
        font = cjk_font(int(17 * SCALE))
        d = ImageDraw.Draw(canvas)
        box = d.textbbox((0, 0), letter, font=font)
        tw, th = box[2] - box[0], box[3] - box[1]
        d.text(
            (pad + (W - tw) / 2 - box[0], pad + (H - th) / 2 - box[1]),
            letter,
            font=font,
            fill=KEY_TEXT + (255,),
        )
    return canvas


def row_image(keys):
    w = sum(k.width for k in keys) + GAP * SCALE * (len(keys) - 1)
    h = max(k.height for k in keys)
    row = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = 0
    for k in keys:
        row.alpha_composite(k, (x, 0))
        x += k.width + GAP * SCALE
    return row


def main():
    letters = ["Q", "W", "E", "R", "T", "Y"]
    sections = [
        ("A", "A · 当前现状：无立体感（阴影已关闭，只剩按下缩放）"),
        ("B", "B · 推荐：预渲染位图，每帧一次 blit（软阴影 + 顶部高光 + 底部厚度）"),
        ("C", "C · 已否决：每帧 path 阴影（实测 30 键掉帧 8% → 1% 的代价）"),
    ]

    margin = 14 * SCALE
    label_h = 22 * SCALE
    gap = 12 * SCALE

    built = []
    for kind, label in sections:
        keys = [make_key(kind, pressed=(i == 2), letter=letters[i]) for i in range(len(letters))]
        built.append((label, row_image(keys)))

    width = max(r.width for _, r in built) + margin * 2
    height = margin + sum(r.height + label_h for _, r in built) + gap * (len(built) - 1) + margin

    out = Image.new("RGBA", (width, height), PANEL + (255,))
    d = ImageDraw.Draw(out)
    font = cjk_font(int(13 * SCALE))
    y = margin
    for label, row in built:
        d.text((margin, y), label, fill=LABEL + (255,), font=font)
        y += label_h
        out.alpha_composite(row, (margin, y))
        y += row.height + gap

    out = out.resize((width // SCALE, height // SCALE), Image.LANCZOS)
    path = os.path.abspath("key-shadow-compare.png")
    out.convert("RGB").save(path)
    print(path, out.size)


if __name__ == "__main__":
    main()
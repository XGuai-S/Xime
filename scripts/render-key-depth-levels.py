"""键盘按键立体感多档位对比图。

档位与 KeyDepth.kt 的 drawDepth 参数一一对应，选中的档位可以直接抄进代码：
  L0  当前现状（阴影关闭，只有按下缩放）
  L1  轻微（阴影 alpha 90 / 高光 60 / 底部 50）
  L2  适中（阴影 alpha 130 / 高光 80 / 底部 65）  ← 当前提交版本
  L3  明显（阴影 alpha 170 / 高光 100 / 底部 80）
  L4  强烈（阴影 alpha 210 / 高光 120 / 底部 95）

每档都画出「未按下 / 按下」两种状态。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

SCALE = 3
KEY_W, KEY_H = 56, 44
RADIUS = 8
GAP = 8
PANEL = (18, 18, 21)
KEY_BG = (62, 62, 70)
LABEL = (190, 190, 205)
SUB = (140, 140, 152)
KEY_TEXT = (238, 238, 244)

LEVELS = [
    ("L0", "当前现状 · 无立体感（阴影已关闭）", None),
    ("L1", "轻微 · shadow 90 / highlight 60 / depth 50", (90, 60, 50)),
    ("L2", "适中 · shadow 130 / highlight 80 / depth 65（当前已提交）", (130, 80, 65)),
    ("L3", "明显 · shadow 170 / highlight 100 / depth 80", (170, 100, 80)),
    ("L4", "强烈 · shadow 210 / highlight 120 / depth 95", (210, 120, 95)),
]


def cjk_font(size):
    for name in ("msyh.ttc", "simhei.ttf", "simsun.ttc"):
        p = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.isfile(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def make_key(params, pressed, letter=""):
    W, H = KEY_W * SCALE, KEY_H * SCALE
    r = RADIUS * SCALE
    face = KEY_BG if not pressed else tuple(int(c * 0.8) for c in KEY_BG)

    if params is None:
        # L0：无阴影、无高光，纯圆角色块
        canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(canvas).rounded_rectangle(
            [0, 0, W - 1, H - 1], radius=r, fill=face + (255,)
        )
    else:
        shadow_alpha, highlight_alpha, depth_alpha = params
        if pressed:
            shadow_alpha = int(shadow_alpha * 0.55)
            highlight_alpha = int(highlight_alpha * 0.7)
            depth_alpha = int(depth_alpha * 0.8)

        pad = int(H * 0.085 + 3 * SCALE) if not pressed else int(H * 0.055 + 2 * SCALE)
        pad = max(pad, 1)
        blur = max(H * 0.09, 1) if not pressed else max(H * 0.05, 1)
        offset = H * 0.06 if not pressed else H * 0.035

        canvas = Image.new("RGBA", (W + pad * 2, H + pad * 2), (0, 0, 0, 0))

        # 软阴影
        sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle(
            [pad, pad + offset, pad + W - 1, pad + H - 1],
            radius=r, fill=(0, 0, 0, shadow_alpha),
        )
        canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))

        # 键面
        face_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(face_layer).rounded_rectangle(
            [0, 0, W - 1, H - 1], radius=r, fill=face + (255,)
        )
        canvas.alpha_composite(face_layer, (pad, pad))

        # 顶部高光
        hl_h = max(2, int(H * 0.5))
        grad = Image.new("RGBA", (W, hl_h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(grad)
        for y in range(hl_h):
            t = y / hl_h
            gd.line([(0, y), (W, y)],
                    fill=(255, 255, 255, int(highlight_alpha * (1 - t) ** 2.2)))
        clip = Image.new("L", (W, hl_h), 0)
        ImageDraw.Draw(clip).rounded_rectangle(
            [0, 0, W - 1, H - 1], radius=r, fill=255
        )
        clip = clip.crop((0, 0, W, hl_h))
        layer = Image.new("RGBA", (W, hl_h), (0, 0, 0, 0))
        layer.paste(grad, (0, 0), clip)
        canvas.alpha_composite(layer, (pad, pad))

        # 底部压暗（厚度）
        dp_h = max(2, int(H * 0.34))
        grad2 = Image.new("RGBA", (W, dp_h), (0, 0, 0, 0))
        gd2 = ImageDraw.Draw(grad2)
        for y in range(dp_h):
            t = y / dp_h
            gd2.line([(0, y), (W, y)], fill=(0, 0, 0, int(depth_alpha * t ** 1.4)))
        clip2 = Image.new("L", (W, dp_h), 0)
        ImageDraw.Draw(clip2).rounded_rectangle(
            [0, 0, W - 1, H - 1], radius=r, fill=255
        )
        clip2 = clip2.crop((0, H - dp_h, W, H))
        layer2 = Image.new("RGBA", (W, dp_h), (0, 0, 0, 0))
        layer2.paste(grad2, (0, 0), clip2)
        canvas.alpha_composite(layer2, (pad, pad + H - dp_h))

    if letter:
        font = cjk_font(int(15 * SCALE))
        d = ImageDraw.Draw(canvas)
        bbox = d.textbbox((0, 0), letter, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        base_x = (canvas.width - tw) / 2 - bbox[0]
        base_y = (canvas.height - th) / 2 - bbox[1]
        d.text((base_x, base_y), letter, font=font, fill=KEY_TEXT + (255,))
    return canvas


def row(params, letters, pressed_index):
    imgs = [make_key(params, i == pressed_index, ch) for i, ch in enumerate(letters)]
    h = max(i.height for i in imgs)
    w = sum(i.width for i in imgs) + GAP * SCALE * (len(imgs) - 1)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = 0
    for img in imgs:
        out.alpha_composite(img, (x, 0))
        x += img.width + GAP * SCALE
    return out


def main():
    letters = ["Q", "W", "E", "R", "T"]
    margin = 12 * SCALE
    label_h = 15 * SCALE
    gap = 7 * SCALE

    rows = []
    for code, label, params in LEVELS:
        rows.append((f"{code}  {label}", row(params, letters, pressed_index=2)))

    width = max(r.width for _, r in rows) + margin * 2
    height = margin + sum(r.height + label_h for _, r in rows) + gap * (len(rows) - 1) + margin

    out = Image.new("RGBA", (width, height), PANEL + (255,))
    d = ImageDraw.Draw(out)
    font = cjk_font(int(11.5 * SCALE))
    y = margin
    for label, img in rows:
        d.text((margin, y), label, fill=LABEL + (255,), font=font)
        y += label_h
        out.alpha_composite(img, (margin, y))
        y += img.height + gap

    out = out.resize((width // SCALE, height // SCALE), Image.LANCZOS)
    path = os.path.abspath("key-depth-levels.png")
    out.convert("RGB").save(path)
    print(path, out.size)


if __name__ == "__main__":
    main()
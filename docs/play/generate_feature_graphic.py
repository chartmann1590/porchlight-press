"""Feature graphic generator (Phase 9A): docs/play/feature-graphic.png.

1024x500 PNG in the app's newspaper style (newsprint cream, black masthead
rules, serif-style masthead, column rules, clearly-marked ad slot). No
external fonts or images: uses Pillow's built-in bitmap font only, so the
output is reproducible on any machine with Pillow installed.

Usage:
    python docs/play/generate_feature_graphic.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 500
OUT = Path(__file__).resolve().parent / "feature-graphic.png"

PAPER = (247, 243, 232)  # newsprint cream
INK = (17, 17, 17)
MUTED = (85, 85, 85)
RULE = (17, 17, 17)
ACCENT = (140, 30, 30)  # deep newspaper red for the kicker only


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    try:
        # Pillow >= 10: load_default supports size.
        return ImageFont.load_default(size=size)
    except TypeError:  # very old Pillow: fall back to the bitmap font
        return ImageFont.load_default()


def centered(draw: ImageDraw.ImageDraw, y: int, text: str, fnt, fill=INK) -> int:
    box = draw.textbbox((0, 0), text, font=fnt)
    w = box[2] - box[0]
    draw.text(((W - w) / 2, y), text, font=fnt, fill=fill)
    return box[3] - box[1]


def main() -> None:
    img = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(img)

    # Border + masthead block.
    d.rectangle([0, 0, W - 1, H - 1], outline=INK, width=4)
    d.rectangle([24, 24, W - 24, H - 24], outline=INK, width=2)

    y = 44
    y += centered(d, y, "PORCHLIGHT PRESS", font(64)) + 14
    y += centered(d, y, "Your hometown newspaper - Free - Private - Sourced", font(22), MUTED) + 12

    # Double rule under the masthead.
    d.line([48, y, W - 48, y], fill=RULE, width=3)
    y += 6
    d.line([48, y, W - 48, y], fill=RULE, width=1)
    y += 14

    # Kicker + headline.
    y += centered(d, y, "MORNING EDITION", font(20), ACCENT) + 4
    y += centered(d, y, "Local news, weather and alerts - every story sourced", font(30)) + 16

    # Three text columns (rules + headline bars + body lines).
    cols = 3
    gutter = 32
    margin = 72
    col_w = (W - 2 * margin - (cols - 1) * gutter) // cols
    top = y
    col_h = 148
    for i in range(cols):
        x0 = margin + i * (col_w + gutter)
        x1 = x0 + col_w
        d.line([x0, top, x0, top + col_h], fill=MUTED, width=1)
        # Headline bars.
        d.rectangle([x0 + 12, top + 6, x1 - 12, top + 16], fill=INK)
        d.rectangle([x0 + 12, top + 22, x1 - 40, top + 30], fill=INK)
        # Body lines.
        yy = top + 44
        while yy < top + col_h - 8:
            xe = x1 - 12 if ((yy // 12) % 3 != 0) else x1 - 48
            d.line([x0 + 12, yy, xe, yy], fill=MUTED, width=2)
            yy += 12
        # Section tag.
        tag = ["LOCAL", "WEATHER", "WORLD"][i]
        d.rectangle([x0 + 12, top + col_h - 22, x0 + 92, top + col_h - 4], outline=INK, width=2)
        tb = d.textbbox((0, 0), tag, font=font(16))
        d.text((x0 + 12 + (80 - (tb[2] - tb[0])) / 2, top + col_h - 22), tag, font=font(16), fill=INK)

    y = top + col_h + 18
    # Ad slot marker (ads sit *between* sections, never inside stories).
    d.rectangle([margin, y, W - margin, y + 30], outline=MUTED, width=2)
    centered(d, y + 4, "Advertisement - clearly labelled - never inside a story", font(18), MUTED)

    img.save(OUT, "PNG")
    print(f"wrote {OUT} ({W}x{H})")


if __name__ == "__main__":
    main()

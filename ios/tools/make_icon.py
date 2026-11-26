"""Renders the iOS app icon (1024x1024) from the same design as the Android adaptive icon."""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 1024
k = S / 108.0  # Android viewport units -> pixels (icon drawn full-bleed; iOS masks the corners)
# Android safe zone is 66/108; iOS shows the full square, so scale the foreground up a little.
FG = 1.35

def hexa(c):
    c = c.lstrip('#'); a = int(c[0:2], 16) / 255; return a, tuple(int(c[i:i + 2], 16) for i in (2, 4, 6))

y, x = np.mgrid[0:S, 0:S].astype(np.float32)
img = np.zeros((S, S, 3), np.float32); img[:] = (0x0D, 0x0B, 0x12)
for cx, cy, r, col in [(10, 18, 62, '#B3F97316'), (100, 12, 64, '#B3C026D3'), (80, 70, 70, '#A68B5CF6'), (12, 98, 64, '#993B5BDB')]:
    a, rgb = hexa(col)
    d = np.sqrt((x - cx * k) ** 2 + (y - cy * k) ** 2) / (r * k)
    t = np.clip(1 - d, 0, 1) * a
    img = img * (1 - t[..., None]) + np.array(rgb, np.float32) * t[..., None]
base = Image.fromarray(img.clip(0, 255).astype(np.uint8)).convert('RGBA')

def u(v):  # foreground coordinate (Android units around centre 54) -> px
    return S / 2 + (v - 54) * k * FG

layer = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
# Frosted glass ring
d.ellipse([u(24), u(24), u(84), u(84)], fill=(255, 255, 255, 0x2E), outline=(255, 255, 255, 0x66), width=int(1.2 * k * FG))
base = Image.alpha_composite(base, layer)
# Gradient play disc (violet -> magenta, top-left to bottom-right)
t = np.clip(((x - u(36)) + (y - u(36))) / (2 * (u(72) - u(36))), 0, 1)
g = np.zeros((S, S, 4), np.float32)
g[..., 0] = 0x7C + (0xD9 - 0x7C) * t; g[..., 1] = 0x3A + (0x46 - 0x3A) * t; g[..., 2] = 0xED + (0xEF - 0xED) * t; g[..., 3] = 255
grad = Image.fromarray(g.astype(np.uint8), 'RGBA')
mask = Image.new('L', (S, S), 0); ImageDraw.Draw(mask).ellipse([u(32), u(32), u(76), u(76)], fill=255)
base.paste(grad, (0, 0), mask)
# Rounded play symbol (triangle with soft corners)
tri = Image.new('L', (S, S), 0)
ImageDraw.Draw(tri).polygon([(u(48.0), u(42.4)), (u(48.0), u(65.6)), (u(66.4), u(54))], fill=255)
tri = tri.filter(ImageFilter.GaussianBlur(k * FG * 0.9)).point(lambda v: 255 if v > 110 else 0).filter(ImageFilter.GaussianBlur(1.2))
white = Image.new('RGBA', (S, S), (255, 255, 255, 255))
base.paste(white, (0, 0), tri)
base.convert('RGB').save(sys.argv[1])

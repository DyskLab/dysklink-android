# Renders the DyskLink logo (ic_logo_glyph.xml geometry on a teal rounded square) at every size.
# Usage: python3 make_icons.py <out-dir>, then copy icon-512.png to icon.png, icon.ico here, icon-256.png and icon-32.png (as tray.png) to src/main/resources.
import sys, math
from PIL import Image, ImageDraw
out = sys.argv[1]
def render(size):
    S = 2048
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.225), fill=(0x14, 0xB8, 0xA6, 255))
    k = S / 24.0
    cx, cy, r, w = 12 * k, 12.49 * k, 7.2 * k, 1.69 * k
    d.arc([cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2], -40.22, 258.58, fill="white", width=int(w))
    def dot(x, y, rad):
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill="white")
    for a in (-40.22, 258.58):
        dot(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)), w / 2)
    d.line([9.4 * k, 14.49 * k, 14.6 * k, 10.49 * k], fill="white", width=int(w))
    dot(9.4 * k, 14.49 * k, 1.65 * k); dot(14.6 * k, 10.49 * k, 1.65 * k)
    return img.resize((size, size), Image.LANCZOS)
sizes = [16, 24, 32, 48, 64, 128, 256, 512]
imgs = {s: render(s) for s in sizes}
for s, im in imgs.items(): im.save(f"{out}/icon-{s}.png")
ico = [16, 24, 32, 48, 64, 128, 256]
imgs[256].save(f"{out}/icon.ico", sizes=[(s, s) for s in ico], append_images=[imgs[s] for s in ico if s != 256])

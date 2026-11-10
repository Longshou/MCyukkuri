"""Animated WebP and 8-beat strips from render_clips.py output.

  <py> clip_sheets.py <out> [--beats 8]
Writes <out>/<clip>.webp and <out>/<clip>_strip.png (beat time and face name on each cell).
"""
import json, os, sys
from PIL import Image, ImageDraw

OUT = sys.argv[1]
BEATS = int(sys.argv[sys.argv.index('--beats') + 1]) if '--beats' in sys.argv else 8
FPS = 24
root = os.path.join(OUT, 'clips')
for name in sorted(os.listdir(root)):
    d = os.path.join(root, name)
    files = sorted(f for f in os.listdir(d) if f.endswith('.png'))
    if not files:
        continue
    clip = json.load(open(os.path.join(d, 'clip.json'), encoding='utf-8'))
    frames = [Image.open(os.path.join(d, f)).convert('RGB') for f in files]
    frames[0].save(os.path.join(OUT, name + '.webp'), save_all=True, append_images=frames[1:],
                   duration=round(1000 / FPS), loop=0, quality=86)
    n = len(frames)
    pick = [round(i * (n - 1) / (BEATS - 1)) for i in range(BEATS)]
    w, h = frames[0].width // 2, frames[0].height // 2
    strip = Image.new('RGB', (w * 4, (h + 22) * ((BEATS + 3) // 4)), 'white')
    dr = ImageDraw.Draw(strip)
    for k, i in enumerate(pick):
        x, y = (k % 4) * w, (k // 4) * (h + 22)
        strip.paste(frames[i].resize((w, h), Image.LANCZOS), (x, y + 22))
        face = 'neutral'
        for f, e in clip['faces']:
            if f - clip['start'] <= i:
                face = e
        dr.text((x + 6, y + 5), '%.2fs  %s' % (i / FPS, face), fill='black')
    strip.save(os.path.join(OUT, name + '_strip.png'))
    print('sheet', name, n)

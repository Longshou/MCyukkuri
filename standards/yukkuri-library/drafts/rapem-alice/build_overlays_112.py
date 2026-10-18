"""Convert the 1.12 sukkiri act overlays into game textures.

Reads only the user-provided local package. The LR sheets are green-screen
(0,255,0) 120px BMPs; frame order follows start.utf8.hsp buffer 9531..9537
(1_l..7_l) and 9541..9547 (1_r..7_r). rot.bmp is the 発情 rotating mark.
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
ROOT = HERE.parents[3]
AKA = ROOT / '油库里八景ver1.12/油库里八景ver1.12/pict/system/option_aka'
OUT = HERE / 'overlays'
KEY = (0, 255, 0)


def unkeyed(rel):
    im = Image.open(AKA / rel).convert('RGBA')
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, _ = px[x, y]
            if g > 200 and r < 90 and b < 90:
                px[x, y] = (0, 0, 0, 0)
    return im


def main():
    OUT.mkdir(exist_ok=True)
    frames = []
    for side in ('l', 'r'):
        for i in range(1, 8):
            frames.append(unkeyed(f'sukkiri/LR/{i}_{side}.bmp'))
    strip = Image.new('RGBA', (120 * len(frames), 120), (0, 0, 0, 0))
    for i, frame in enumerate(frames):
        strip.alpha_composite(frame, (i * 120, 0))
    strip.save(OUT / 'rapem_act_strip.png')
    unkeyed('hatujyou/rot.bmp').save(OUT / 'hatujyou_rot.png')
    print('wrote', OUT / 'hatujyou_rot.png')


if __name__ == '__main__':
    main()

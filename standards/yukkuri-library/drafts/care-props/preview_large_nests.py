"""Offline current preview of the 2x2 family nests with two adults and four newborns; no game or browser required.
Output: <MCYUKKURI_PREVIEWS or .local/previews>/birth-large-nests/{current.png, index.html}."""
import os, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
os.environ.setdefault('MCYUKKURI_OUT', str(Path(os.environ.get('MCYUKKURI_PREVIEWS', Path(__file__).resolve().parents[4] / '.local/previews')) / 'birth-large-nests'))
from preview_nests_lib import AFTER, OUT, tile, sheet
from nests import LARGE_FLOOR

SZ = (560, 430)
ADULT, NEWBORN = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
# One settled moment, not assigned places: the parents lie apart and turned a little toward each other on a diagonal, and the
# newborns have wandered into the gaps at differing distances and headings. (x, z, yaw)
ADULTS = ((-5.8, -6.0, 155), (5.6, .4, 222))
NEWBORNS = ((-1.6, 4.6, 205), (-7.4, 4.6, 140), (1.8, 8.6, 172), (8.8, -7.2, 248))


def family(floor):
    return ([(ADULT, 8, x, floor, z, 1.0, yaw) for x, z, yaw in ADULTS] +
            [(NEWBORN, 8, x, floor, z, .32, yaw) for x, z, yaw in NEWBORNS])


if __name__ == '__main__':
    ground = ('ground:24', 0, 0, 0, 0, 1)
    rows = []
    for name, floor, label in (('large_yukkuri_nest', LARGE_FLOOR, '大编织软窝'), ('large_wild_nest', .2, '野生大草窝')):
        rows.append([tile(AFTER, [ground, (name, 8, 0, 0, 0, 1)], f'{label} 空窝（入口朝 +z）', size=SZ, ppu=12),
                     tile(AFTER, [ground, (name, 8, 0, 0, 0, 1)] + family(floor), f'{label} 成体2 + 新生4', size=SZ, ppu=12),
                     tile(AFTER, [ground, (name, 8, 0, 0, 0, 1)] + family(floor), f'{label} 俯视', size=SZ, ppu=12, az=0, el=80),
                     tile(AFTER, [ground, (name, 8, 0, 0, 0, 1)], f'{label} 入口低矮侧视', size=SZ, ppu=12, az=0, el=8)])
    sheet(rows, 'current.png', '2x2 家庭窝：内部约 1.78 格、薄外沿 ≤0.2 格，窝面不标位置，亲油错开、子油随意散落；正面(+z)地面高度开口', 4, size=SZ)
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>2x2 家庭窝</title><h1>2x2 家庭窝</h1>'
                                    '<p>大编织软窝与野生大草窝：占地 2×2，内部约 28.5 单位（1.78 格），薄外沿约 1.3 深、不高于 0.2 格，'
                                    '正面开口与地面齐平；窝面是连续的布面或落叶，'
                                    '不标睡位。示例中两名成体错开斜对，四只新生散在空隙里。</p>'
                                    '<img src="current.png" alt="当前 2x2 家庭窝">', encoding='utf-8')
    print(OUT / 'current.png')

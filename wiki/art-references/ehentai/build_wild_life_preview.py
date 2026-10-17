"""Build current research excerpts offline from registered images."""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / '.local/previews/wild-life-research'
# Rectangles select only the discussed scenes, not unrelated source-page actors.
SELECTED = (
    ('EH35-005', None, 'Rain / shelter hygiene'),
    ('EH36-003', None, 'Playing with a friend'),
    ('EH35-009', (0, 1030, 1200, 1697), 'Sharing gathered food'),
    ('EH37-006', (20, 695, 555, 1120), 'Leaf swaddle / lower-left panel'),
)


def main():
    data = json.loads((Path(__file__).parent / 'manifest.json').read_text(encoding='utf-8'))
    rows = {r['id']: r for r in data['images']}
    OUT.mkdir(parents=True, exist_ok=True)
    sheet = Image.new('RGB', (1400, 1600), '#e7e5df')
    draw = ImageDraw.Draw(sheet)
    for index, (ident, box, label) in enumerate(SELECTED):
        with Image.open(ROOT / rows[ident]['path']) as im:
            excerpt = im.convert('RGB')
            if box:
                excerpt = excerpt.crop(box)
        if ident == 'EH37-006':
            excerpt.save(OUT / 'leaf-swaddle.jpg', quality=94)
        excerpt.thumbnail((675, 740))
        x, y = (index % 2) * 700, (index // 2) * 800
        draw.text((x + 12, y + 12), ident + ' | ' + label, fill='#243c35')
        sheet.paste(excerpt, (x + (700 - excerpt.width) // 2, y + 40))
    sheet.save(OUT / 'current.jpg', quality=92)
    print(OUT / 'current.jpg')


if __name__ == '__main__':
    main()

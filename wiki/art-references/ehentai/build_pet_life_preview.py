"""Build the current pet clothing reference sheet offline; no browser or game."""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / '.local/previews/pet-life-research'
SELECTED = ('EH31-002', 'EH32-006', 'EH33-011', 'EH34-007')


def main():
    manifest = json.loads((Path(__file__).parent / 'manifest.json').read_text(encoding='utf-8'))
    rows = {row['id']: row for row in manifest['images']}
    OUT.mkdir(parents=True, exist_ok=True)
    sheet = Image.new('RGB', (1600, 2200), '#e7e5df')
    draw = ImageDraw.Draw(sheet)
    for index, ident in enumerate(SELECTED):
        with Image.open(ROOT / rows[ident]['path']) as original:
            thumb = original.convert('RGB')
            thumb.thumbnail((780, 1050))
        x, y = (index % 2) * 800, (index // 2) * 1100
        sheet.paste(thumb, (x + (800 - thumb.width) // 2, y + 35))
        draw.text((x + 12, y + 10), ident, fill='#243c35')
    sheet.save(OUT / 'current.jpg', quality=92)
    print(OUT / 'current.jpg')


if __name__ == '__main__':
    main()

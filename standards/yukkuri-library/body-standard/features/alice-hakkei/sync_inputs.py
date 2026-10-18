"""Re-assemble inputs/alice from the authored cube components and repaint its palette cells.

Assembly reuses marisa-single-braid/sync_inputs.py (same cube-era procedure: bbmodel,
geo.json, animation-rig.json, selected-rig.json, animation.json, the model inside
index.html). Then the six 8 px palette cells at row 64 (hair, light, dark, red, dark red,
lace) are repainted with author.PALETTE in textures/alice/base.png, every inputs/alice
expression atlas, alice.png and the PNGs embedded in alice.bbmodel and index.html.
Face UV areas are never touched, so all expression pixels stay identical.
Finally inputs/manifest.json is re-hashed for the changed alice files only.
"""
from pathlib import Path
import base64, hashlib, io, json, runpy, sys
from PIL import Image

HERE = Path(__file__).resolve().parent
R = HERE.parents[2]  # standards/yukkuri-library
INPUT = R/'body-standard/inputs/alice'
author = runpy.run_path(str(HERE/'author.py'))
CELLS, PALETTE = author['CELLS'], author['PALETTE']


def repaint(image):
    """Fill each cell with its colour plus the original +9 streaks (author_standard_species)."""
    image = image.convert('RGBA'); px = image.load()
    for name, (x, y) in CELLS.items():
        base = PALETTE[name]
        streak = tuple(min(255, c+9) for c in base) if name.startswith('hair') else base
        for dx in range(8):
            for dy in range(8):
                px[x+dx, y+dy] = base+(255,)
        if name.startswith('hair'):
            for dx, dy in [(1, 1), (4, 3), (6, 0)]:
                for yy in range(dy, min(7, dy+3)+1):
                    px[x+dx, y+yy] = streak+(255,)
    return image


def png_bytes(image):
    buffer = io.BytesIO(); image.save(buffer, format='PNG'); return buffer.getvalue()


def repaint_file(path):
    before = path.read_bytes(); image = repaint(Image.open(io.BytesIO(before)))
    if image.tobytes() != Image.open(io.BytesIO(before)).convert('RGBA').tobytes():
        path.write_bytes(png_bytes(image))


def repaint_url(url):
    head, data = url.split(',', 1)
    image = Image.open(io.BytesIO(base64.b64decode(data)))
    repainted = repaint(image)
    if repainted.tobytes() == image.convert('RGBA').tobytes():
        return url
    return head+','+base64.b64encode(png_bytes(repainted)).decode()


def main():
    marisa = runpy.run_path(str(HERE.parent/'marisa-single-braid/sync_inputs.py'))
    marisa['sync']('alice')
    for path in [R/'textures/alice/base.png', INPUT/'alice.png', *sorted((INPUT/'textures').glob('*.png'))]:
        repaint_file(path)
    bb_path = INPUT/'alice.bbmodel'; bb = json.loads(bb_path.read_text(encoding='utf-8-sig'))
    for texture in bb['textures']:
        texture['source'] = repaint_url(texture['source'])
    bb_path.write_text(json.dumps(bb, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    html_path = INPUT/'index.html'; html = html_path.read_text(encoding='utf-8-sig')
    start = html.index('const DATA=')+len('const DATA='); data, length = json.JSONDecoder().raw_decode(html[start:])
    data['textures'] = {k: repaint_url(v) for k, v in data['textures'].items()}
    html_path.write_text(html[:start]+json.dumps(data, ensure_ascii=False, separators=(',', ':'))+html[start+length:], encoding='utf-8')
    # Same hashing convention as the Marisa revision: CRLF for Windows text types.
    manifest_path = R/'body-standard/inputs/manifest.json'; manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    sha = lambda b: hashlib.sha256(b).hexdigest(); crlf = lambda b: b.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
    for key, stored in manifest['files'].items():
        if not key.startswith('inputs/alice/'):
            continue
        data = (R/'body-standard'/key).read_bytes()
        if stored in (sha(data), sha(crlf(data))):
            continue
        windows = Path(key).suffix in ('.html', '.js', '.css', '.py', '.txt')
        manifest['files'][key] = sha(crlf(data) if windows else data)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print('inputs/alice re-assembled; palette cells repainted; manifest updated')


if __name__ == '__main__':
    sys.exit(main())

"""Static contact sheets for the pet-shop clips (lore recipe v12): six evenly spaced frames per clip.

Renders the actual runtime meshes and clips with the repository's offline rasterizer; no browser or game.
Output: .local/previews/pet-motions/<model>.png (not committed). Needs numpy, Pillow and node.
"""
from pathlib import Path
import argparse, json, subprocess
import numpy as np
from PIL import Image, ImageDraw
import preview as P

CLIPS = ('think', 'nervous', 'proud', 'joy_jump', 'sulk', 'hide_face', 'beg', 'bow', 'shy', 'doze_off', 'struggle', 'basket_in', 'basket_out', 'ignore', 'restrain')
MODELS = ('reimu', 'marisa', 'alice', 'chen', 'patchouli', 'house_reimu')
FRAMES, CELL = 6, 300
OUT = P.ROOT/'.local/previews/pet-motions'


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node'); ap.add_argument('--models', default=','.join(MODELS))
    ap.add_argument('--corrections', type=Path, default=P.ASSETS/'yukkuri_presentation/motion_corrections.json')
    ap.add_argument('--out', type=Path, default=OUT); ap.add_argument('--azimuth', type=float, default=.55); ap.add_argument('--elevation', type=float, default=.3)
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    resource = json.loads(args.corrections.read_text(encoding='utf-8'))
    font = P.preview_font(18)
    for ident in args.models.split(','):
        model = P.Posed(ident, resource); work = out/f'_{ident}'; work.mkdir(exist_ok=True)
        rest = model.frame('idle', 0)[0].reshape(-1, 3); span = max(rest[:, 1].max()*1.6, 1.)
        cy = span*.42; jobs = []
        for row, clip in enumerate(CLIPS):
            d = model.clips[clip]['duration']
            for col in range(FRAMES):
                t = d*col/(FRAMES-1); tri, uv, n = model.frame(clip, t)
                v = np.c_[tri.reshape(-1, 3), uv.reshape(-1, 2), np.repeat(n, 3, axis=0)]
                tex = Image.fromarray(model.texture(model.face(clip, t))).resize((512, 512), Image.Resampling.NEAREST)
                k = f'{row}_{col}'; (work/f'{k}.json').write_text(json.dumps(v.flatten().tolist()))
                (work/f'{k}.rgba').write_bytes(tex.convert('RGBA').tobytes()); jobs.append((row, col, k, clip, t, model.face(clip, t)))
        js = "const fs=require('fs');\n"+(P.ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')
        js += f"\nconst dir={json.dumps(str(work))};\nfor(const k of {json.dumps([j[2] for j in jobs])}){{"
        js += f"const v=JSON.parse(fs.readFileSync(dir+'/'+k+'.json'));const t=fs.readFileSync(dir+'/'+k+'.rgba');"
        js += f"fs.writeFileSync(dir+'/'+k+'-r.rgba',renderModel(v,t,{CELL},{CELL},{args.azimuth},{args.elevation},{span},{{centerY:{cy},textureSize:512}}));}}"
        (work/'r.cjs').write_text(js); subprocess.run([args.node, str(work/'r.cjs')], check=True)
        sheet = Image.new('RGB', (CELL*FRAMES, (CELL+26)*len(CLIPS)), (233, 241, 243)); dr = ImageDraw.Draw(sheet)
        for row, col, k, clip, t, face in jobs:
            img = Image.frombytes('RGBA', (CELL, CELL), (work/f'{k}-r.rgba').read_bytes())
            bg = Image.new('RGBA', (CELL, CELL), (233, 241, 243, 255)); bg.alpha_composite(img)
            sheet.paste(bg.convert('RGB'), (col*CELL, row*(CELL+26)+26))
            dr.text((col*CELL+6, row*(CELL+26)+4), f'{ident} {clip} {t:.2f}s {face}', font=font, fill=(32, 57, 64))
        sheet.save(out/f'{ident}.png')
        for f in work.iterdir(): f.unlink()
        work.rmdir()
        print(out/f'{ident}.png')


if __name__ == '__main__':
    main()

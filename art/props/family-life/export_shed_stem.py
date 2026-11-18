"""Export the shed stem item as an extruded icon recoloured to the living head stem palette; optional offline preview."""
from pathlib import Path
import argparse, hashlib, importlib.util, io, json, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT / 'scripts'))
from asset_export_checks import check_output, preserve_equivalent_png

KIT_PATH = ROOT / 'standards/yukkuri-library/drafts/block-props/kit.py'
_spec = importlib.util.spec_from_file_location('block_props_kit', KIT_PATH)
block_kit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(block_kit)


def recolour_stem_icon(icon_bytes, stem_rgb, young_rgb):
    """Map the icon's LEAF ramp steps onto a five-step ramp anchored on the living stem and young-stem colours."""
    leaf_ramp = block_kit.ramp('LEAF')
    stem_ramp = [
        tuple(round(c * 0.38) for c in stem_rgb),
        tuple(round(c * 0.68) for c in stem_rgb),
        tuple(stem_rgb),
        tuple(young_rgb),
        tuple(min(255, round(c + (255 - c) * 0.35)) for c in young_rgb),
    ]
    mapping = dict(zip(leaf_ramp, stem_ramp))
    with Image.open(io.BytesIO(icon_bytes)) as opened:
        image = opened.convert('RGBA')
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            if a == 0:
                continue
            if (r, g, b) not in mapping:
                raise ValueError(f'Unexpected stem icon colour {(r, g, b)} at ({x}, {y}); expected LEAF ramp')
            image.putpixel((x, y), mapping[(r, g, b)] + (a,))
    return block_kit.png_bytes(image), image


def build():
    recipe = json.loads((HERE / 'shed-stem.json').read_text(encoding='utf-8'))
    registry_path = ROOT / recipe['family_stem_registry']
    family_stem = json.loads(registry_path.read_text(encoding='utf-8'))
    stem_rel = family_stem['stem_source']
    stem_path = ROOT / stem_rel
    stem = json.loads(stem_path.read_text(encoding='utf-8'))
    icon_rel = recipe['icon_source']
    icon_path = ROOT / icon_rel
    png_bytes, texture = recolour_stem_icon(icon_path.read_bytes(), stem['material']['stem'], stem['material']['young'])
    png_bytes = preserve_equivalent_png(ASSETS / 'textures/props/yukkuri_stem.png', png_bytes)
    mesh, _bb, _ = block_kit.extrude('yukkuri_stem', png_bytes, recipe['height'])
    item = block_kit.prop_item_model('yukkuri_stem', extruded=True)
    compact = lambda d: (json.dumps(d, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    pretty = lambda d: (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    outputs = {
        ASSETS / 'yukkuri_props/yukkuri_stem.json': compact(mesh),
        ASSETS / 'models/item/yukkuri_stem.json': pretty(item),
        ASSETS / 'textures/props/yukkuri_stem.png': png_bytes,
        ASSETS / 'yukkuri_props/family-index.json': pretty({'yukkuri_stem': {'source': 'art/props/family-life/shed-stem.json'}}),
    }
    digest = lambda p: hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    sources = [
        'art/props/family-life/shed-stem.json',
        recipe['family_stem_registry'],
        stem_rel,
        icon_rel,
        'standards/yukkuri-library/drafts/block-props/kit.py',
        'standards/yukkuri-library/drafts/item-icons/draw.py',
    ]
    provenance = {
        'status': recipe['status'],
        'source': {rel: digest(ROOT / rel) for rel in sources},
        'outputs': {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(raw).hexdigest() for p, raw in outputs.items()},
    }
    outputs[HERE / 'shed-stem-provenance.json'] = pretty(provenance)
    return outputs, mesh, texture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    outputs, mesh, texture = build()
    for path, raw in outputs.items():
        if args.check:
            assert check_output(path, raw), str(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
    print('Empty stem source and five exported files ' + ('match.' if args.check else 'exported.'))
    if args.preview:
        out = ROOT / '.local/previews/shed-stem'
        out.mkdir(parents=True, exist_ok=True)
        (out / 'texture.rgba').write_bytes(texture.convert('RGBA').tobytes())
        v = np.array(mesh['vertices']).reshape(-1, 8)
        center = (v[:, :3].max(axis=0) + v[:, :3].min(axis=0)) / 2
        v[:, :3] -= center
        (out / 'model.json').write_text(json.dumps(v.flatten().tolist()), encoding='utf-8')
        code = "const fs=require('fs');\n" + (ROOT / 'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8') + '\nconst dir=' + json.dumps(str(out)) + ';\n'
        code += "const v=JSON.parse(fs.readFileSync(dir+'/model.json')),t=fs.readFileSync(dir+'/texture.rgba');for(let i=0;i<2;i++)fs.writeFileSync(dir+'/'+i+'.rgba',renderModel(v,t,500,540,[0,.65][i],.18,6,{centerY:0,textureSize:16}));"
        runner = out / 'render.cjs'
        runner.write_text(code, encoding='utf-8')
        subprocess.run([args.node, str(runner)], check=True)
        sheet = Image.new('RGB', (1000, 610), (239, 240, 227))
        d = ImageDraw.Draw(sheet)
        font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 24)
        d.text((24, 15), '油库里的茎 · 当前导出物品（离线预览 / 待用户确认）', font=font, fill='#30472f')
        for i in range(2):
            im = Image.frombytes('RGBA', (500, 540), (out / f'{i}.rgba').read_bytes())
            sheet.paste(im, (i * 500, 60), im)
        path = out / 'yukkuri-stem.png'
        sheet.save(path)
        print(path)


if __name__ == '__main__':
    main()

"""Care-prop family restyle: authors the peripheral snapshot meshes from parametric designs and the shared palette.

Writes art/props/runtime-peripheral/selected/<id>/{<id>.bbmodel, <id>.mesh.json, texture.png}. Run
scripts/export_peripheral_assets.py afterwards to produce the runtime resources. --check is read-only.

Props authored here: food bowl and its grain / wheat / forage fills, cushion pet nest, the three oil-grain bags, food
scrap, waste, leaked filling, wash cloth, yarn ball and carrier. The four pet-care goods (sweet candy, custard pudding, pet harness,
swaddle cloth) are 16x16 item sprites from goods.py, written to art/props/pet-goods/ and shipped by scripts/export_pet_goods_icons.py. The sand toilet (clean and dirty) keeps its geometry and
only moves onto the shared palette (retint); its dirty state swaps the old block for the anko swirl. The ceramic and covered toilets, mats, and spicy grain / food mash are
authored by their own package builders, which import this kit. The self-built nests of wild yukkuri (forest_burrow, personal_leaf_nest, bedding_bundle, predator_nest) are authored by wild.py with vanilla texture references and exported by
ecology/wild-shelters/build.py and art/props/predators/build.py.
The hat-boat provisions hat_cargo_* (apple, berries, carrot, bread, cookie, melon, grass, tall grass, wheat, candy, pudding; hat units) come from cargo.py.
Shared palette: art/props/palette.json. Every shape is new and awaiting user confirmation.
"""
import argparse, io, json, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts'))
import kit, designs, bags, nests, small, goods, cargo
from PIL import Image
from asset_export_checks import check_output, preserve_equivalent_png

SNAP = ROOT / 'art/props/runtime-peripheral/selected'
ICONS = ROOT / 'art/props/pet-goods'     # 16x16 item icons for the four pet-care goods; scripts/export_pet_goods_icons.py ships them
GENERATOR = 'standards/yukkuri-library/drafts/care-props/build.py'

BUILD = {
    'food_bowl': designs.food_bowl,
    'food_bowl_regular': designs.food_bowl_regular,
    'food_bowl_wheat': designs.food_bowl_wheat,
    'food_bowl_forage': designs.food_bowl_forage,
    'forage_pile': designs.forage_pile,
    'yukkuri_nest': nests.yukkuri_nest,
    'oil_grain_budget': bags.oil_grain_budget,
    'oil_grain_regular': bags.oil_grain_regular,
    'oil_grain_premium': bags.oil_grain_premium,
    'food_scrap': small.food_scrap,
    'yukkuri_waste': small.yukkuri_waste,
    'leaked_filling': small.leaked_filling,
    'wash_cloth': small.wash_cloth,
    'yarn_ball': small.yarn_ball,
    'yukkuri_carrier': small.yukkuri_carrier,
    **cargo.CARGO,
}
RETINT = ('yukkuri_toilet', 'yukkuri_toilet_dirty')
REMOVED = ('food_bowl_garbage',)          # no code or recipe references it; mesh, texture and registry rows are dropped


def retint(name):
    """Palette-only touch-up: same triangles, each flat-colour face re-pointed at the nearest shared swatch."""
    folder = SNAP / name
    mesh = json.loads((folder / f'{name}.mesh.json').read_text(encoding='utf-8'))
    old = Image.open(folder / 'texture.png').convert('RGBA')
    w, h = old.size

    def swatch(part, uvs):
        u, v = uvs[0]
        px = old.getpixel((min(w - 1, int(u * w)), min(h - 1, int(v * h))))
        return kit.nearest_swatch(px[:3])
    prop = kit.Prop.from_mesh(name, mesh, swatch)
    if name.endswith('_dirty'):
        # the dirty state shows the shared anko swirl (scaled to the tray) instead of the old purple block
        prop.parts = [part for part in prop.parts if part['name'] not in ('waste', 'swirl_mound')]
        prop.graft(small.yukkuri_waste(), .5, (1.0, .7, .2))
    return prop


def outputs():
    result, props = {}, {}
    for name, factory in BUILD.items():
        props[name] = factory()
    for name in RETINT:
        props[name] = retint(name)
    for name, factory in goods.GOODS.items():
        result[ICONS / f'{name}.png'] = preserve_equivalent_png(ICONS / f'{name}.png', factory().png())
    for name, prop in props.items():
        folder = SNAP / name
        texture = preserve_equivalent_png(folder / 'texture.png', prop.png())
        result[folder / f'{name}.mesh.json'] = kit.dumps(prop.mesh())
        result[folder / f'{name}.bbmodel'] = kit.dumps(prop.bbmodel())
        result[folder / 'texture.png'] = texture
    return result, props


def update_selection(result):
    """selection.json records snapshot fingerprints; regenerated props point at this generator instead of the deleted drafts."""
    import hashlib
    path = ROOT / 'art/props/runtime-peripheral/selection.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    names = set(BUILD) | set(RETINT) | set(REMOVED)
    files = {}
    for key, entry in data['files'].items():
        folder = Path(entry['snapshot']).parent.name
        if folder in names and not key.startswith('generated:'):
            continue
        files[key] = entry
    for file, content in result.items():
        rel = file.relative_to(ROOT).as_posix()
        files[f'generated:{rel}'] = {'snapshot': rel, 'sha256': hashlib.sha256(content).hexdigest(), 'generator': GENERATOR}
    data['files'] = files
    return (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result, props = outputs()
    selection = ROOT / 'art/props/runtime-peripheral/selection.json'
    result[selection] = update_selection({k: v for k, v in result.items()})
    changed = []
    for path, content in result.items():
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    for name in REMOVED:
        folder = SNAP / name
        if folder.exists():
            changed.append(folder.relative_to(ROOT).as_posix() + ' (removed)')
            if not args.check:
                for file in folder.iterdir():
                    file.unlink()
                folder.rmdir()
    if args.check and changed:
        raise SystemExit('Care-prop snapshot differs: ' + ', '.join(changed))
    print(f'Care props: {len(props)} meshes; ' + ('match.' if args.check else f'{len(changed)} files written.'))
    for name, prop in props.items():
        print(f'  {name}: {prop.tri_count()} triangles')


if __name__ == '__main__':
    main()

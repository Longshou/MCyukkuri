"""Care-prop family restyle: authors the peripheral snapshot meshes from parametric designs and the shared palette.

Writes art/props/runtime-peripheral/selected/<id>/{<id>.bbmodel, <id>.mesh.json, texture.png}. Run
scripts/export_peripheral_assets.py afterwards to produce the runtime resources. --check is read-only.

Props authored here: the three oil-grain bags, forage pile, food scrap, waste, leaked filling, wash cloth, yarn ball and
carrier on the low-poly kit; the placed food bowl, its heap-only grain / wheat / forage fills, the cushion pet nest, the sand
toilet (clean and dirty), the droppings and every non-default dye style of the placed goods (including the 2x2 woven nest
and the ceramic / covered toilets) come from placed.py through vanilla_props.py. The four pet-care goods (sweet candy, custard pudding, pet harness,
swaddle cloth) are 16x16 item sprites from goods.py, written to art/props/pet-goods/ and shipped by scripts/export_pet_goods_icons.py.
The default ceramic and covered toilets (placed.py), mats, and spicy grain / food mash are exported by their own package
builders, which import this folder. The self-built nests of wild yukkuri (forest_burrow, personal_leaf_nest, bedding_bundle, predator_nest) are authored by wild.py with vanilla texture references and exported by
ecology/wild-shelters/build.py and art/props/predators/build.py.
The hat-boat provisions heaps and shared occupancy layout (three quantity tiers plus temporary side stow) come from cargo.py.
Shared palette: art/props/palette.json. Every shape is new and awaiting user confirmation.
"""
import argparse, io, json, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts'))
import kit, designs, bags, small, goods, cargo, vanilla_props, placed
from PIL import Image
from asset_export_checks import check_output, preserve_equivalent_png

SNAP = ROOT / 'art/props/runtime-peripheral/selected'
ICONS = ROOT / 'art/props/pet-goods'     # 16x16 item icons for the four pet-care goods; scripts/export_pet_goods_icons.py ships them
GENERATOR = 'standards/yukkuri-library/drafts/care-props/build.py'
COLLISION = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/care_collision.json'

BUILD = {
    **vanilla_props.PROPS,
    **vanilla_props.VARIANTS,
    'forage_pile': designs.forage_pile,
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
REMOVED = ('food_bowl_garbage',
           'hat_cargo_apple', 'hat_cargo_berries', 'hat_cargo_carrot', 'hat_cargo_bread', 'hat_cargo_cookie', 'hat_cargo_melon',
           'hat_cargo_grass', 'hat_cargo_tall_grass', 'hat_cargo_wheat', 'hat_cargo_candy', 'hat_cargo_pudding', 'hat_cargo_mat')          # no code or recipe references it; mesh, texture and registry rows are dropped


def outputs(only_cargo=False):
    result, props = {}, {}
    for name, factory in (cargo.CARGO if only_cargo else BUILD).items():
        props[name] = factory()
    for name, factory in ({} if only_cargo else goods.GOODS).items():
        result[ICONS / f'{name}.png'] = preserve_equivalent_png(ICONS / f'{name}.png', factory().png())
    for name, prop in props.items():
        folder = SNAP / name
        texture = preserve_equivalent_png(folder / 'texture.png', prop.png())
        result[folder / f'{name}.mesh.json'] = kit.dumps(prop.mesh())
        result[folder / f'{name}.bbmodel'] = kit.dumps(prop.bbmodel())
        result[folder / 'texture.png'] = texture
    result[cargo.LAYOUT_PATH] = kit.dumps(cargo.layout())
    if not only_cargo:
        result[COLLISION] = kit.dumps(placed.collision())      # nest collision shells for life/CareBlock
    return result, props


def update_selection(result, only_cargo=False):
    """selection.json records snapshot fingerprints; regenerated props point at this generator instead of the deleted drafts."""
    import hashlib
    path = ROOT / 'art/props/runtime-peripheral/selection.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    names = set(cargo.CARGO) if only_cargo else set(BUILD) | set(REMOVED)
    files = {}
    for key, entry in data['files'].items():
        folder = Path(entry['snapshot']).parent.name
        if (not only_cargo and folder in REMOVED) or folder in names and not key.startswith('generated:'):
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
    parser.add_argument('--hat-cargo', action='store_true', help='Only rebuild hat cargo snapshots and shared occupancy')
    args = parser.parse_args()
    result, props = outputs(args.hat_cargo)
    selection = ROOT / 'art/props/runtime-peripheral/selection.json'
    result[selection] = update_selection({k: v for k, v in result.items()}, args.hat_cargo)
    changed = []
    for path, content in result.items():
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    for name in (() if args.hat_cargo else REMOVED):
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

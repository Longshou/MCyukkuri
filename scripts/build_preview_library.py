"""Index gameplay previews, named item resources and real museum scenarios.

Reads categories.json, packaged resources and museum_scenarios.S. Writes only
.local/previews/library (cached software thumbnails) and library.json. No game,
browser, resource export or Java compilation; Pillow and numpy are required.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
from preview_inventory import current_items

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def resource_url(path):
    return '/' + path.relative_to(ROOT).as_posix()


def classify_item(ident, categories):
    matches = [c['id'] for c in categories if re.search(c['items'], ident)]
    if len(matches) > 1:
        raise ValueError(f'Ambiguous item category: {ident}: {matches}')
    return matches[0] if matches else 'other'


def item_visual(ident, model, out, warnings):
    """Follow the actual item dispatch: a sprite takes precedence over old meshes."""
    # These render paths require per-stack state and cannot be reproduced by
    # feeding a same-named mesh to the generic prop renderer.
    if ident in ('yukkuri_remains', 'accessory_shard'):
        return None, '实例外观待补'
    textures = model.get('textures', {})
    seen, elements = set(), model.get('elements')
    while (parent := model.get('parent', '')).startswith('mcyukkuri:'):
        if parent in seen: raise ValueError(f'Cyclic item model parent: {ident}: {parent}')
        seen.add(parent)
        model = read(ASSETS/'models'/(parent.split(':', 1)[1]+'.json'))
        if elements is None: elements = model.get('elements')
        textures = {**model.get('textures', {}), **textures}
    if elements: model = dict(model, elements=elements)
    sprite = textures.get('layer0')
    sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
    from render_preview import VANILLA
    if sprite:
        ns, name = sprite.split(':', 1)
        path = (ASSETS if ns=='mcyukkuri' else VANILLA) / 'textures' / (name + '.png')
        if path.is_file():
            if ns=='mcyukkuri': return resource_url(path), '物品图标'
            target = out/'library/items'/f'{ident}.png'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            return target.relative_to(out).as_posix(), '物品图标'
    # PropItemRenderer uses this shared shape for the aquatic accessory item.
    mesh_ident = 'accessory_marisa' if ident=='marisa_aquatic_hat' else ident
    mesh = ASSETS / 'yukkuri_props' / f'{mesh_ident}.json'
    if model.get('parent') in ('minecraft:builtin/entity', 'builtin/entity') and mesh.is_file():
        sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
        from render_preview import Source, render
        from PIL import ImageOps
        source = Source(ROOT)
        data = read(mesh)
        inputs = {mesh, Path(__file__), ROOT/'standards/yukkuri-library/drafts/care-props/render_preview.py'}
        inputs.update(Path(source.tex_of(mesh_ident, part)) for part in data['parts'])
        target = out / 'library/items' / f'{ident}.png'
        stamp = target.with_suffix('.sha256')
        try:
            digest = hashlib.sha256()
            for path in sorted(inputs):
                digest.update(str(path).encode())
                digest.update(path.read_bytes())
            fingerprint = digest.hexdigest()
            if not target.is_file() or not stamp.is_file() or stamp.read_text() != fingerprint:
                # Fit from the actual mesh extent; never crop large facilities to a fixed camera.
                image, _ = render(source, [(mesh_ident, 8, 0, 0, 0, 1)], ppu=18, az=220, el=28,
                                  bg=(243, 240, 232, 255))
                image = ImageOps.contain(image, (480, 340))
                target.parent.mkdir(parents=True, exist_ok=True)
                image.save(target)
                stamp.write_text(fingerprint)
            return target.relative_to(out).as_posix(), '基础网格预览'
        except FileNotFoundError as error:
            warnings.append(f'{ident}: missing render input: {error.filename or error}')
    if elements:
        from PIL import ImageOps
        from preview_block_model import render_native
        try:
            image = ImageOps.contain(render_native(model, textures, ROOT), (480,340))
            target = out/'library/items'/f'{ident}.png'
            target.parent.mkdir(parents=True, exist_ok=True)
            image.save(target)
            return target.relative_to(out).as_posix(), '方块模型预览'
        except FileNotFoundError as error:
            warnings.append(f'{ident}: missing native render input: {error.filename or error}')
    # A particle/atlas texture is not an item image. Missing render support is
    # visible as a gap, never disguised by an unrelated texture or old sheet.
    return None, '外观待补'


def build(manifest, catalog, out):
    config = read(ROOT/'preview/hub/categories.json')
    categories = config['categories']
    category_ids = {c['id'] for c in categories}
    if len(category_ids) != len(categories):
        raise ValueError('Duplicate gameplay category')
    for category in categories:
        if not (ROOT/category['source']).is_file():
            raise FileNotFoundError(category['source'])
    entries, warnings = [], []

    def add(entry):
        if entry['category'] not in category_ids:
            raise ValueError(f'Unknown category: {entry}')
        entries.append(entry)

    def add_chapter(chapter, category):
        first = next((s.get('image') for s in chapter['steps'] if s.get('image')), None)
        first = first or next((g['src'] for g in chapter.get('gallery', [])), None)
        add(dict(id='scene:'+chapter['id'], category=category, kind='scene',
                 title=chapter.get('label', chapter['title']), summary=chapter['summary'],
                 chapter=chapter['id'], image=first, source=chapter.get('source'),
                 visual='基础外观对照'))

    for chapter in manifest['chapters']:
        add_chapter(chapter, config['chapters'].get(chapter['id'], 'other'))
    for gap in config['previewGaps']:
        add(dict(id='reference:'+gap['id'], category=gap['category'], kind='reference',
                 title=gap['title'], summary=gap['reason'], source=gap['source'], visual='预览缺口'))

    for model in catalog['models']:
        category = 'house' if model['id'].startswith('house_') else 'species'
        add(dict(id='model:'+model['id'], category=category, kind='model', title=model['name'],
                 summary=f"{model['group']} · {len(model['actions'])} 个有运行引用的片段 · {len(model['textures'])} 套贴图；仅基础模型，不含状态附加渲染",
                 model=model['id'], image=f"models/thumbs/{model['id']}.png",
                 source='docs/SPECIES.md', visual='可旋转模型'))

    import museum_actions
    action_categories = {action:config['actionGroups'][group]
                         for group, actions in museum_actions.CATEGORIES for action, _ in actions}
    action_names = {**museum_actions.NAMES, **config['actionLabels']}
    for action in sorted({a for model in catalog['models'] for a in model['actions']}):
        models = [m for m in catalog['models'] if action in m['actions']]
        category = next((category for pattern, category in config['actionRules'] if re.search(pattern, action)),
                        action_categories.get(action, 'other'))
        preferred = 'marisa' if category=='water' else 'house_reimu' if category=='house' else 'reimu'
        for prefix, ident in [('predator_', 'remilia'), ('shell_', 'marisa_tsumuri'), ('house_', 'house_reimu')]:
            if action.startswith(prefix): preferred = ident
        model = next((m for m in models if m['id']==preferred), models[0])
        add(dict(id='motion:'+action, category=category, kind='motion', title=action_names.get(action, action),
                 summary=f"{model['name']}演示 · 仅播放模型片段，不含目标、道具、环境和身体状态，不代表该形态具备对应玩法能力。",
                 model=model['id'], action=action, image=f"models/thumbs/{model['id']}.png",
                 source='docs/MOTION_MUSEUM_ACTIONS.md', visual='可播放动作'))

    lang = read(ASSETS/'lang/zh_cn.json')
    registered = current_items()
    model_paths = {p.stem:p for p in (ASSETS/'models/item').glob('*.json')}
    unconfirmed = [ident for ident in model_paths if ident not in registered
                   and not ident.endswith('_spawn_egg')
                   and ('item.mcyukkuri.'+ident in lang or 'block.mcyukkuri.'+ident in lang)]
    for ident, registration in sorted(registered.items()):
        if ident in config['excludeItems']:
            continue
        path = model_paths.get(ident)
        title = lang.get('item.mcyukkuri.'+ident) or lang.get('block.mcyukkuri.'+ident) or ident
        category = classify_item(ident, categories)
        image, visual = item_visual(ident, read(path), out, warnings) if path else (None, '外观待补')
        summary = '当前注册物品的基础外形；不含佩戴／放置位置、内容物变化和实例状态。'
        if visual == '物品图标': summary = '当前物品图标；身上或场地中的效果不由此图表示。'
        if ident == 'house_egg': summary = '未开裂的基础卵形；进度超过开裂阶段后由另一网格显示。'
        if not image:
            summary = ('遗体依赖死者形态、死因与水中阶段；离线实例渲染待补。' if ident=='yukkuri_remains' else
                       '碎片依赖来源头饰的颜色与体型；离线实例渲染待补。' if ident=='accessory_shard' else
                       '当前注册物品；暂无可可靠展示的离线外观，请使用对应试玩入口。')
        add(dict(id='item:'+ident, category=category, kind='item', title=title, image=image,
                 visual=visual, summary=summary, registration=registration,
                 source=next(c['source'] for c in categories if c['id']==category),
                 resource=resource_url(path) if path else None))

    # Import the authored scenario objects: no copied table of buttons, initial
    # states, instructions or progress. Importing this module does not run a game.
    import museum_scenarios
    from museum_fields import BY_ID
    topic_category = {topic:c['id'] for c in categories for topic in c['topics']}
    for scenario in museum_scenarios.S:
        category = config['scenarioOverrides'].get(scenario.id, topic_category.get(scenario.asks, 'other'))
        add(dict(id='scenario:'+scenario.id, category=category, kind='scenario', title=scenario.title,
                 summary=scenario.watch, field=BY_ID[scenario.field].title, wait=scenario.wait,
                 note=scenario.note, retry=scenario.retry, sceneId=scenario.id,
                 source='docs/DEVELOPMENT.md', visual='游戏试玩入口'))

    for species in dict.fromkeys(a['species'] for a in catalog['audio']):
        name = lang.get('entity.mcyukkuri.'+species, species)
        count = sum(a['species']==species for a in catalog['audio'])
        add(dict(id='audio:'+species, category='social', kind='audio', title=name+' · 声音试听',
                 summary=f'{count} 条已有打包音频，可按场景和台词筛选。', species=species,
                 source='docs/DIALOGUE_IMPLEMENTATION.md', visual='声音'))
    ids = [e['id'] for e in entries]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate preview entry id')
    result = dict(categories=[{k:c[k] for k in ('id','label','description','source')} for c in categories],
                  entries=entries, generatedAt=manifest['generatedAt'])
    (out/'library.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    report = ROOT/'.local/reports/preview-library.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(dict(unclassified=[e['id'] for e in entries if e['category']=='other'],
                                      missingRenderInputs=warnings,
                                      unconfirmedItemResources=unconfirmed,
                                      unconfirmedActions=sorted({a for m in catalog['models'] for a in m['unconfirmedActions']}),
                                      previewGaps=config['previewGaps'],
                                      itemAppearanceGaps=[e['id'] for e in entries if e['kind']=='item' and not e['image']]), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Indexed {len(entries)} gameplay entries; {sum(e["category"]=="other" for e in entries)} unclassified; {len(warnings)} missing render inputs.')
    return result

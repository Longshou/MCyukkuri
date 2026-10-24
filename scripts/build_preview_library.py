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
            return target.relative_to(out).as_posix(), '模型预览'
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
    for ref in textures.values():
        if ref.startswith('mcyukkuri:'):
            path = ASSETS/'textures'/(ref.split(':', 1)[1]+'.png')
            if path.is_file():
                return resource_url(path), '材质预览'
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
                 visual='场景分镜'))

    # Split mixed art batches by gameplay subject without changing their shared source.
    chapters = []
    available = {chapter['id'] for chapter in manifest['chapters']}
    for chapter in read(ROOT/'preview/hub/recent.json')['chapters']:
        if chapter['id'] not in available:
            add(dict(id='reference:'+chapter['id'], category=config['chapters'].get(chapter['id'], 'other'),
                     kind='reference', title=chapter.get('label', chapter['title']),
                     summary='专项图片尚未生成，内容与制作入口仍保留。',
                     source=chapter.get('source'), visual='预览待生成'))
    for chapter in manifest['chapters']:
        splits = config['splitChapters'].get(chapter['id'])
        if not splits:
            chapters.append(chapter)
            add_chapter(chapter, config['chapters'].get(chapter['id'], 'other'))
            continue
        remaining = list(chapter['steps'])
        for split in splits:
            steps = [s for s in remaining if any(s.get('image', '').startswith(p) for p in split['prefixes'])]
            if not steps:
                continue
            remaining = [s for s in remaining if s not in steps]
            topic = dict(chapter, id=split['id'], title=split['title'], label=split['title'],
                         summary='当前选用素材的离线外观分镜；游戏内表现仍需试玩确认。',
                         points=[], steps=steps, gallery=[])
            chapters.append(topic)
            add_chapter(topic, split['category'])
        if remaining:
            topic = dict(chapter, steps=remaining)
            chapters.append(topic)
            add_chapter(topic, 'other')
    for supplement in config['supplements']:
        steps = [dict(title=title, body='当前专项预览；离线分镜不代表游戏内验收。', image=path)
                 for path, title in supplement['media'] if (out/path).is_file()]
        if not steps:
            add(dict(id='reference:'+supplement['id'], category=supplement['category'], kind='reference',
                     title=supplement['title'], summary='已登记专项内容，预览图片尚未生成。',
                     source=supplement['source'], visual='预览待生成'))
            continue
        topic = dict(id=supplement['id'], label=supplement['title'], title=supplement['title'],
                     summary='按实际游戏内容归档的专项素材。', tag='专项预览', source=supplement['source'],
                     points=[], steps=steps, gallery=[])
        chapters.append(topic)
        add_chapter(topic, supplement['category'])
    manifest['chapters'] = chapters

    for model in catalog['models']:
        category = 'house' if model['id'].startswith('house_') else 'species'
        add(dict(id='model:'+model['id'], category=category, kind='model', title=model['name'],
                 summary=f"{model['group']} · {len(model['actions'])} 个动作 · {len(model['textures'])} 套表情贴图",
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
        model = next((m for m in models if m['id']==preferred), models[0])
        add(dict(id='motion:'+action, category=category, kind='motion', title=action_names.get(action, action),
                 summary=f"{model['name']}演示 · {len(models)} 种形态有此动作。仅播放动作片段，不触发玩法。",
                 model=model['id'], action=action, image=f"models/thumbs/{model['id']}.png",
                 source='docs/MOTION_MUSEUM_ACTIONS.md', visual='可播放动作'))

    lang = read(ASSETS/'lang/zh_cn.json')
    for path in sorted((ASSETS/'models/item').glob('*.json')):
        ident = path.stem
        if ident.endswith('_spawn_egg') or ident in config['excludeItems']:
            continue
        title = lang.get('item.mcyukkuri.'+ident) or lang.get('block.mcyukkuri.'+ident)
        # Per-body dispatch meshes are not separate player items. Only catalogued,
        # localized item/block models are exposed here; models retain all body forms.
        if not title:
            continue
        category = classify_item(ident, categories)
        image, visual = item_visual(ident, read(path), out, warnings)
        if not image and ident=='yukkuri_remains' and (out/'remains/remains-forms.png').is_file():
            image, visual = 'remains/remains-forms.png', '遗体外观分镜'
        add(dict(id='item:'+ident, category=category, kind='item', title=title, image=image,
                 visual=visual, summary=('当前物品／设施素材。' if visual != '材质预览' else
                                         '展示当前材质；完整方块外观请在试玩场查看。'),
                 source=next(c['source'] for c in categories if c['id']==category), resource=resource_url(path)))

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
                                      missingRenderInputs=warnings), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Indexed {len(entries)} gameplay entries; {sum(e["category"]=="other" for e in entries)} unclassified; {len(warnings)} missing render inputs.')
    return result

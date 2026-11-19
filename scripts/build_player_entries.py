"""Build the live player's list from packaged models, moments, props and sound assets."""
import json

from build_review_catalog import ASSETS, ROOT, read
from face_usage import resolve
from museum_actions import NAMES


# Captions describe the moments; packaged tracks supply the body and face cues.
MOMENTS = {
    'leak': ('受惊漏尿', None, ('猛然受惊', '开始漏尿', '事后含泪')),
    'wet_relief': ('湿透后缓过来', 'wet_relief', ('湿透后撑地', '甩掉身上的水', '终于放松下来')),
    'rain_huddle': ('雨中蜷缩', 'behavior_rain_huddle_left', ('缩起身体避雨',)),
    'thunder_cower': ('雷声中畏缩', 'behavior_thunder_cower', ('雷声响起', '缩着身子发抖')),
    'wake_stretch': ('醒来伸懒腰', 'behavior_wake', ('从睡梦中醒来，打哈欠并伸展',)),
}


def leak():
    # A representative adult scare from ExcitedLeak: 8 ticks of startle, 30 of flow,
    # 80 of aftermath. The body clips stage these three face beats.
    return dict(loop=False, steps=[
        dict(clip=clip, duration=ticks / 20, tempo=1, intensity=1, faces=[[0, face]])
        for clip, ticks, face in [('startle', 8, 'leak_startle'), ('brace', 30, 'leak_flow'),
                                  ('idle', 80, 'side_teary')]])


def build_entries(catalog, items):
    authored = read(ROOT/'standards/yukkuri-library/presentation/moments.json')['moments']
    action_names = {**NAMES, **read(ROOT/'preview/hub/categories.json')['actionLabels']}
    faces = read(ASSETS/'yukkuri_presentation/face_library.json')
    models = catalog['models']
    entries = []

    for ident, (title, source, captions) in MOMENTS.items():
        row = authored[source] if source else leak()
        steps = [dict(step, caption=caption) for step, caption in zip(row['steps'], captions, strict=True)]
        variants = [model['id'] for model in models if all(
            step['clip'] in model['clips'] and
            all(resolve(faces, model['id'], face) in model['textures'] for _, face in step['faces'])
            for step in steps)]
        assert variants, ident
        entries.append(dict(id=ident, category='performances', tag='look-only', title=title,
                            variants=variants, steps=steps, loop=row['loop']))

    for model in models:
        ident = model['id']
        entries.append(dict(id='model-'+ident, category='models', tag='look-only', title=model['name'],
                            model=ident, clip='idle', detail=model['group']))
        for clip in model['clips']:
            entries.append(dict(id=f'motion-{clip}-{ident}', category='performances', tag='look-only',
                                title=action_names.get(clip, clip), model=ident, clip=clip))

    for item in items:
        entries.append(dict(id=item['id'].replace(':', '-', 1), category='props', tag='look-only',
                            title=item['title'], image=item['image'], detail=item['visual'], summary=item['summary']))

    names = {model['id']: model['name'] for model in models}
    ages = {'adult': '成体', 'young': '幼语', 'juvenile': '子体', 'baby': '赤体', 'song': '哼唱'}
    for sound in catalog['audio']:
        entries.append(dict(id='sound-'+sound['id'], category='sounds', tag='look-only',
                            title=sound['zh'] or sound['ja'] or sound['id'],
                            speaker=names[sound['species']], detail=ages[sound['age']],
                            japanese=sound['ja'], audio=sound['url']))

    assert len({entry['id'] for entry in entries}) == len(entries)
    return dict(entries=entries, models={model['id']: dict(
        name=model['name'], scale=model['formScale'], textures=model['textures'],
        faceAliases=faces['models'][model['id']].get('face_aliases', {}),
        expressions=faces['models'][model['id']]['selectable']) for model in models},
        faceAliases=faces['aliases'], faceAnimations=faces['animations'],
        faceLabels={ident: ' · '.join(label) for ident, label in faces['labels'].items()})


def write_entries(catalog, items, out):
    data = build_entries(catalog, items)
    temporary = out/'entries.tmp'
    temporary.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')
    temporary.replace(out/'entries.json')
    print(f"Indexed {len(data['entries'])} live player entries.")
    return data

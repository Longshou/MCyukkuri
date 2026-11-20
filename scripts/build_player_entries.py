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
    'fright_vomit': ('受惊吐馅', 'reaction_afraid', ('受惊后吐出馅料',)),
}


def moment_clip_durations():
    # ClipMarkers takes the first authored correction for each name, then Reimu's base clips.
    revisions = read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models']
    sources = [model['clips'] for model in revisions.values()]
    sources.append(read(ASSETS/'yukkuri/reimu.json')['clips'])
    durations = {}
    for clips in sources:
        for name, clip in clips.items():
            durations.setdefault(name, clip['duration'])
    return durations


LEAK = dict(stage='adult', selfControl=50, stimulus='drop', seed=1, pressure=65)
SPEECH = dict(leak='afraid', wet_relief='self_rescue_safe', rain_huddle='cold',
              thunder_cower='afraid', wake_stretch='awake', fright_vomit='afraid')

ATTACHMENT_MOMENTS = {
    'held_kanroken': dict(title='叼着棍剑走动', model='myon', loop=True,
                         steps=[dict(clip='waddle', faces=[], tempo=1)],
                         held=dict(mesh='kanroken', joint='mouth')),
    'worn_damaged_hat': dict(title='戴着破帽摇头', model='marisa', moment='reaction_hat_jostle',
                             hat=dict(mesh='aquatic_basin_hat', form='aquatic_marisa', damage=2)),
    # Face the juvenile's mouth toward the caregiver within the care overlay's grounded reach.
    'parent_child_contact': dict(title='亲子贴嘴', model='reimu', moment='behavior_feed_child',
                                 partner=dict(model='reimu', clip='idle', scale=.73,
                                              offset=[0, 0, -9.4], yaw=180, blend=1)),
}


def performance_lines(audio, event):
    lines = {}
    candidates = sorted((line for line in audio if line['age'] == 'adult' and event in line['events']),
                        key=lambda line: (line['shared'], line['events'][0] != event))
    for line in candidates:
        lines.setdefault(line['species'], dict(text=line['zh'], duration=line['ticks'] / 20, audio=line['url']))
    return lines


def build_entries(catalog, items):
    authored = read(ASSETS/'yukkuri_presentation/moments.json')['moments']
    action_names = {**NAMES, **read(ROOT/'preview/hub/categories.json')['actionLabels']}
    faces = read(ASSETS/'yukkuri_presentation/face_library.json')
    models = catalog['models']
    entries = []

    for ident, (title, source, captions) in MOMENTS.items():
        row = authored[source] if source else dict(loop=False, steps=[
            dict(clip=clip, tempo=1, intensity=1, faces=[])
            for clip in ('startle', 'brace', 'idle')])
        steps = [dict(step, caption=caption) for step, caption in zip(row['steps'], captions, strict=True)]
        leak_faces = ('leak_startle', 'leak_flow', 'side_teary') if not source else ()
        variants = [model['id'] for model in models if all(
            step['clip'] in model['clips'] and
            all(resolve(faces, model['id'], face) in model['textures'] for _, face in step['faces'])
            for step in steps) and all(face in model['textures'] for face in leak_faces)]
        assert variants, ident
        entry = dict(id=ident, category='performances', tag='look-only', title=title,
                     variants=variants, steps=steps, loop=row['loop'], moment=source,
                     speech=performance_lines(catalog['audio'], SPEECH[ident]))
        if not source:
            entry['leak'] = LEAK
        if ident == 'fright_vomit':
            entry.update(loop=False, filling=True)
        entries.append(entry)

    for ident, row in ATTACHMENT_MOMENTS.items():
        moment = authored[row['moment']] if 'moment' in row else {}
        entries.append(dict(id=ident, category='performances', tag='look-only', **{**moment, **row}))

    for model in models:
        ident = model['id']
        entries.append(dict(id='model-'+ident, category='models', tag='look-only', title=model['name'],
                            model=ident, clip='idle', detail=model['group']))
        for clip in model['clips']:
            entries.append(dict(id=f'motion-{clip}-{ident}', category='performances', tag='look-only',
                                title=action_names.get(clip, clip), model=ident, clip=clip))

    for item in items:
        entries.append(dict(id=item['id'].replace(':', '-', 1), category='props',
                            tag='look-only' if item['image'] else 'behaviour',
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
        momentClips=moment_clip_durations(), faceAliases=faces['aliases'], faceAnimations=faces['animations'],
        faceLabels={ident: ' · '.join(label) for ident, label in faces['labels'].items()})


def write_entries(catalog, items, out):
    data = build_entries(catalog, items)
    temporary = out/'entries.tmp'
    temporary.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')
    temporary.replace(out/'entries.json')
    print(f"Indexed {len(data['entries'])} live player entries.")
    return data

"""Forward-export the edited side-hair components without building or running the project."""
from pathlib import Path
import copy
import json
import sys
import uuid

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
from geometry import vertices

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')

out = root / 'assembled/patchouli'
bb = read(out / 'patchouli.bbmodel')
rig = read(out / 'animation-rig.json')
replacements = {}
for part in ('hair', 'hand-left', 'hand-right'):
    elements = read(root / f'components/patchouli/{part}.json')['elements']
    for source in elements:
        element = copy.deepcopy(source)
        group = element.pop('bone')
        element['uuid'] = str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri-library/patchouli/' + element['name']))
        replacements.setdefault(group, []).append(element)

old_elements = bb['elements']
new_elements, new_groups, inserted = [], [], set()
for element, group in zip(old_elements, rig['groups']):
    if group in replacements:
        if group not in inserted:
            new_elements.extend(replacements[group])
            new_groups.extend([group] * len(replacements[group]))
            inserted.add(group)
    else:
        new_elements.append(element)
        new_groups.append(group)
bb['elements'] = new_elements
rig['groups'] = new_groups

def update_nodes(nodes):
    for node in nodes:
        if not isinstance(node, dict):
            continue
        if node['name'] in replacements:
            child_bones = [child for child in node['children'] if isinstance(child, dict)]
            node['children'] = [e['uuid'] for e in replacements[node['name']]] + child_bones
        update_nodes(node.get('children', []))

update_nodes(bb['outliner'])
geo = read(out / 'patchouli.geo.json')
for bone in geo['minecraft:geometry'][0]['bones']:
    if bone['name'] not in replacements:
        continue
    cubes = []
    for element in replacements[bone['name']]:
        cube = dict(origin=element['from'], size=[b-a for a, b in zip(element['from'], element['to'])],
                    uv={name: dict(uv=face['uv'][:2], uv_size=[face['uv'][2]-face['uv'][0], face['uv'][3]-face['uv'][1]])
                        for name, face in element['faces'].items()})
        if any(element.get('rotation', [0, 0, 0])):
            cube.update(pivot=element['origin'], rotation=element['rotation'])
        cubes.append(cube)
    bone['cubes'] = cubes

html_path = out / 'index.html'
html = html_path.read_text(encoding='utf-8-sig')
data_start = html.index('const DATA=') + len('const DATA=')
data, data_length = json.JSONDecoder().raw_decode(html[data_start:])
data['model']['vertices'] = [v for e in new_elements for v in vertices(e, data['textureSize'])]
data['model']['rig']['groups'] = new_groups
new_indices = {e['uuid']: i for i, e in enumerate(new_elements)}
for patches in data['model'].get('expressionPatches', {}).values():
    for patch in patches:
        patch['index'] = new_indices[old_elements[patch['index']]['uuid']]
html = html[:data_start] + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + html[data_start + data_length:]

save(out / 'patchouli.bbmodel', bb)
save(out / 'animation-rig.json', rig)
save(out / 'patchouli.geo.json', geo)
html_path.write_text(html, encoding='utf-8')
print('Updated Patchouli geometry exports and embedded model data; textures and animations were preserved.')

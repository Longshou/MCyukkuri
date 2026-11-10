"""Preview stage and stills for the manga Marisa (run inside Blender after build.py).

  exec(open(r'<this file>').read(), {'__file__': r'<this file>'}); render_stills(r'<out dir>')
Writes <out>/<look>_<view>.png and <out>/pair_<view>.png.
"""
import bpy, json, math, os
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
VARIANTS = {k: v for k, v in json.load(open(os.path.join(HERE, 'variants.json'), encoding='utf-8')).items() if not k.startswith('_')}


def stage():
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_EEVEE'
    sc.view_settings.view_transform = 'Standard'
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = 'PNG'
    sc.eevee.taa_render_samples = 48
    coll = bpy.data.collections.get('COL_Stage') or bpy.data.collections.new('COL_Stage')
    if coll.name not in sc.collection.children:
        sc.collection.children.link(coll)
    for o in list(coll.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for n in ('Light', 'Camera'):
        o = bpy.data.objects.get(n)
        if o and not o.users_collection[0].name.startswith('COL_'):
            bpy.data.objects.remove(o, do_unlink=True)
    world = bpy.data.worlds.get('WLD_sky') or bpy.data.worlds.new('WLD_sky')
    sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes['Background']
    bg.inputs['Color'].default_value = (0.62, 0.74, 0.88, 1)
    bg.inputs['Strength'].default_value = 0.9
    sun = bpy.data.objects.new('LGT_sun', bpy.data.lights.new('LGT_sun', 'SUN'))
    sun.data.energy = 3.2
    sun.data.angle = math.radians(8)
    sun.rotation_euler = (math.radians(42), 0, math.radians(-35))
    coll.objects.link(sun)
    fill = bpy.data.objects.new('LGT_fill', bpy.data.lights.new('LGT_fill', 'SUN'))
    fill.data.energy = 1.1
    fill.data.angle = math.radians(40)
    fill.data.use_shadow = False
    fill.rotation_euler = (math.radians(75), 0, math.radians(15))
    coll.objects.link(fill)
    me = bpy.data.meshes.new('SM_ground')
    me.from_pydata([(-3000, -3000, 0), (3000, -3000, 0), (3000, 3000, 0), (-3000, 3000, 0)], [], [(0, 1, 2, 3)])
    gm = bpy.data.materials.get('MAT_ground') or bpy.data.materials.new('MAT_ground')
    gm.use_nodes = True
    gb = next(n for n in gm.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    gb.inputs['Base Color'].default_value = (0.25, 0.42, 0.16, 1)
    gb.inputs['Roughness'].default_value = 1.0
    me.materials.append(gm)
    coll.objects.link(bpy.data.objects.new('SM_ground', me))
    cam = bpy.data.objects.new('CAM_preview', bpy.data.cameras.new('CAM_preview'))
    cam.data.clip_end = 10000
    coll.objects.link(cam)
    sc.camera = cam
    return cam


VIEWS = {'front': (0, -1, 0.17), 'three_quarter': (0.68, -0.73, 0.24), 'side': (1, 0, 0.15),
         'back': (0, 1, 0.2), 'three_quarter_lock': (-0.68, -0.73, 0.24)}


def aim(cam, pos, target, lens):
    cam.location = Vector(pos)
    cam.rotation_euler = (Vector(target) - Vector(pos)).to_track_quat('-Z', 'Y').to_euler()
    cam.data.lens = lens


def show_only(names):
    for n in VARIANTS:
        c = bpy.data.collections.get('COL_Marisa_%s' % n)
        if c:
            c.hide_render = n not in names


def render_stills(out, size=520, views=None):
    os.makedirs(out, exist_ok=True)
    cam = stage()
    sc = bpy.context.scene
    written = []
    for name, v in VARIANTS.items():
        show_only([name])
        sc.render.resolution_x = sc.render.resolution_y = size
        o = Vector(v['offset'])
        reach = max(v['hat']['brim_rx'], v['profile'][3][1]) * 1.15 + v['H'] * 0.5
        target = o + Vector((0, 0, v['H'] * 0.75))
        for view, d in VIEWS.items():
            if views and view not in views:
                continue
            dirv = Vector(d).normalized()
            aim(cam, target + dirv * reach * 5.2, target, 85)
            sc.render.filepath = os.path.join(out, '%s_%s.png' % (name, view))
            bpy.ops.render.render(write_still=True)
            written.append(sc.render.filepath)
        if not views or 'face' in views:  # close-up at eye height
            eye = o + Vector((0, 0, v['face']['eye_y'] - 0.4))
            aim(cam, eye + Vector((0, -1, 0.12)).normalized() * v['H'] * 3.6, eye, 85)
            sc.render.filepath = os.path.join(out, '%s_face.png' % name)
            bpy.ops.render.render(write_still=True)
            written.append(sc.render.filepath)
    show_only(list(VARIANTS))
    sc.render.resolution_x, sc.render.resolution_y = 1100, 620
    mid = sum((Vector(v['offset']) for v in VARIANTS.values()), Vector()) / len(VARIANTS) + Vector((0, 0, 8))
    for view in ('front', 'three_quarter'):
        if views and view not in views:
            continue
        aim(cam, mid + Vector(VIEWS[view]).normalized() * 125, mid, 70)
        sc.render.filepath = os.path.join(out, 'pair_%s.png' % view)
        bpy.ops.render.render(write_still=True)
        written.append(sc.render.filepath)
    return written

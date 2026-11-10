"""Headless build of the manga Marisa: model, motions, preview stage, .blend, stills and clips.

  <py> faces.py                                        (face textures first)
  D:/Blender/blender.exe -b --factory-startup --python make.py -- <out> [--stills] [--clips] [--size 720x600]
Writes <out>/marisa_manga.blend, <out>/renders/<look>_<view>.png (--stills) and
<out>/clips/<clip>/f####.png (--clips; then run clip_sheets.py <out>).
"""
import bpy, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[sys.argv.index('--') + 1:]
OUT = os.path.abspath(args[0])
os.makedirs(OUT, exist_ok=True)


def run(name, call=None, *a):
    g = {'__file__': os.path.join(HERE, name), '__name__': 'marisa_' + name[:-3]}
    exec(open(os.path.join(HERE, name), encoding='utf-8').read(), g)
    return g[call](*a) if call else g


for ob in list(bpy.data.objects):
    bpy.data.objects.remove(ob, do_unlink=True)
print('BUILD', run('build.py', 'build_all'))
print('CLIPS', run('animate.py', 'animate_all'))
stage = run('stage.py')
stage['stage']()
bpy.context.scene.frame_set(1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, 'marisa_manga.blend'))
if '--stills' in args:
    stage['render_stills'](os.path.join(OUT, 'renders'), 720)
    bpy.context.scene.frame_set(1)
if '--clips' in args:
    run('render_clips.py')

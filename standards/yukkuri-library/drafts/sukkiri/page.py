"""Proposal page for the sukkiri performance: rough strips of the draft faces and father key poses.

Runs author.py, renders every frame through `scripts/preview.py strip` (draft faces with --face-preset, draft clips with
--draft-clip) into <previews>/sukkiri-plan/strips/, then lays out <previews>/sukkiri-plan/index.html.

    python standards/yukkuri-library/drafts/sukkiri/page.py [--skip-render]
"""
from pathlib import Path
import argparse, os, subprocess, sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT/'scripts'))
from preview import SHARED, sheet  # noqa: E402
from PIL import Image  # noqa: E402
import author  # noqa: E402

OUT = SHARED/'.local/previews/sukkiri-plan'
STRIPS = OUT/'strips'
DRAFT = {'flushed_squeeze', 'dazed_flushed', 'smug_flushed', 'rolled_grin'}
LABEL = dict(flushed_smile='潮红闭眼笑', flushed_tongue='潮红闭眼伸舌', impact='受击挤眼', teary_tongue='含泪伸舌',
             satisfied='满足闭眼', smug='得意（现有）', open_drool='睁眼流口水', squeezed_cry_tears='挤眼大哭',
             blank_cry='白眼哭喊', horrified='惊恐', flushed_squeeze='新·潮红挤眼喊', dazed_flushed='新·失神流口水',
             smug_flushed='新·潮红得意（半垂眼）', rolled_grin='新·翻白眼咧嘴笑')
MODELS = dict(marisa='魔理沙', reimu='灵梦', alice='爱丽丝')
# Face sequences per role: (beat label, face).
SEQUENCES = [
    ('父·魔理沙（神气最强）', 'marisa', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'impact'),
                                  ('释放 0–6t', 'flushed_squeeze'), ('释放 6t–事后 40t', 'dazed_flushed'),
                                  ('事后 40–120t', 'smug_flushed')]),
    ('父·灵梦及其他', 'reimu', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'impact'),
                           ('释放 0–6t', 'flushed_squeeze'), ('释放 6t–事后 30t', 'dazed_flushed'),
                           ('事后 30–120t', 'satisfied')]),
    ('母方（各种族相同）', 'reimu', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'teary_tongue'),
                             ('释放 0–6t', 'flushed_squeeze'), ('释放 6t–事后 30t', 'dazed_flushed'),
                             ('事后 30–120t', 'satisfied')]),
    ('爱丽丝·攻击素鸡立', 'alice', [('追击', 'open_drool'), ('动作 0–150t', 'flushed_tongue'), ('冲刺 150–195t', 'impact'),
                              ('深顶 195–200t', 'flushed_squeeze'), ('释放 200–240t', 'rolled_grin'),
                              ('事后', 'smug_flushed')]),
    ('受方（被爱丽丝压住）', 'reimu', [('压住 0–200t', 'squeezed_cry_tears'), ('释放 200–215t', 'blank_cry'),
                               ('215–240t', 'squeezed_cry_tears'), ('松开后逃跑', 'horrified')]),
]
FACE_GRID = ['flushed_squeeze', 'dazed_flushed', 'smug_flushed', 'smug', 'rolled_grin']
# Father key poses: (topic, clip, face, times, beat labels).
POSES = [
    ('pose-slow', 'thrust_slow', 'flushed_tongue', [0, .16, .3], ['慢顶·回位', '慢顶·顶出（8t 一次）', '慢顶·收回']),
    ('pose-fast', 'thrust_fast', 'impact', [0, .08, .14], ['快顶·回位', '快顶·顶出（4t 一次）', '快顶·收回']),
    ('pose-release', 'release', 'flushed_squeeze', [.1, .25, .35, .8, 1.0],
     ['释放 2t 深顶', '5t 停住颤抖·左', '7t 颤抖·右', '16t 松劲', '20t 回位']),
    ('pose-dazed', 'after_dazed', 'dazed_flushed', [0, .6, 1.8], ['事后·失神', '失神摇晃', '失神摇晃']),
    ('pose-proud', 'after_proud', 'smug_flushed', [0, .8, 1.6], ['事后·开始挺起', '神气·挺身小弹', '神气·停住']),
]


def strip(topic, model, times, face=None, clip=None, views=((90, 5),), size=(400, 400), partner=None):
    command = [sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, '--model', model, '--size', *map(str, size),
               '--times', ','.join(f'{t:g}s' for t in times)]
    for az, el in views:
        command += ['--view', str(az), str(el)]
    if face in DRAFT:
        command += ['--face-preset', str(HERE/(face+'.json'))]
    elif face:
        command += ['--face', face]
    if clip:
        command += ['--draft-clip', str(HERE/'clips'/(clip+'.json'))]
    if partner:
        command += ['--partner', partner[0], '--partner-offset', *map(str, partner[1])]
    subprocess.run(command, check=True, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(STRIPS), 'PYTHONUTF8': '1'},
                   stdout=subprocess.DEVNULL)


def frames(topic, count):
    atlas = Image.open(STRIPS/topic/'atlas.png').convert('RGBA')
    width = atlas.width//count
    return [atlas.crop((i*width, 0, (i+1)*width, atlas.height)) for i in range(count)]


def face_frame(model, face):
    return frames(f'face-{model}-{face}', 1)[0]


def render():
    wanted = {(m, f) for m in MODELS for f in FACE_GRID}
    wanted |= {(m, f) for _, m, row in SEQUENCES for _, f in row}
    for model, face in sorted(wanted):
        strip(f'face-{model}-{face}', model, [0], face=face, views=((0, 5),))
    for topic, clip, face, times, _ in POSES:
        strip(topic, 'marisa', times, face=face, clip=clip, views=((90, 5), (30, 8)), size=(360, 360))
    strip('pose-pair', 'marisa', [0, .1, .35], face='flushed_squeeze', clip='release', views=((90, 5), (55, 14)),
          size=(420, 340), partner=('reimu', (0, 0, -12)))


def layout():
    grid = [face_frame(m, f) for f in FACE_GRID for m in MODELS]
    sheet(grid, [f'{LABEL[f]} · {MODELS[m]}' for f in FACE_GRID for m in MODELS], OUT/'faces-new.png', 3, (300, 260))
    for i, (_, model, row) in enumerate(SEQUENCES):
        sheet([face_frame(model, f) for _, f in row], [f'{beat}：{LABEL[f]}' for beat, f in row],
              OUT/f'sequence-{i}.png', len(row), (310, 220))
    for topic, _, _, times, labels in POSES:
        images = frames(topic, 2*len(times))
        sheet(images, [f'{label}·{view}' for view in ('侧面', '斜前') for label in labels], OUT/f'{topic}.png',
              len(times), (300, 300))
    sheet(frames('pose-pair', 6), [f'{label}·{view}' for view in ('侧面', '斜前')
                                    for label in ('释放前', '释放 2t 深顶', '释放 7t 颤抖')],
          OUT/'pose-pair.png', 3, (360, 300))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--skip-render', action='store_true', help='re-lay the page from existing strips')
    args = ap.parse_args()
    author.faces(); author.clips()
    if not args.skip_render:
        render()
    layout()
    (OUT/'index.html').write_text((HERE/'page.html').read_text(encoding='utf-8'), encoding='utf-8')
    print('http://127.0.0.1:8766/.local/previews/sukkiri-plan/index.html')


if __name__ == '__main__':
    main()

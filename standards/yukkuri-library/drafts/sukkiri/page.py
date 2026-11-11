"""Sukkiri preview page: the shipped faces, clips and organ art, rendered through `scripts/preview.py strip`.

Frames go to <previews>/sukkiri-plan/strips/, the page to <previews>/sukkiri-plan/index.html. Faces and clips are the
exported runtime ones (tools/author_sukkiri_faces.py, motions/variants/lore recipe family `sukkiri`). The "before"
frames of the long tongue are strips saved from the proposal round and are only re-laid; the organs compare against
the main line before this package (--before).

    python standards/yukkuri-library/drafts/sukkiri/page.py [--skip-render]
"""
from pathlib import Path
import argparse, os, shutil, subprocess, sys
from functools import lru_cache

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT/'scripts'))
from preview import SHARED, sheet, read as read_json  # noqa: E402
from PIL import Image  # noqa: E402

OUT = SHARED/'.local/previews/sukkiri-plan'
STRIPS = OUT/'strips'
LABEL = dict(flushed_smile='潮红闭眼笑', flushed_tongue='潮红闭眼吐舌尖', impact='受击挤眼', teary_tongue='含泪吐舌尖',
             satisfied='满足闭眼', open_drool='睁眼流口水', squeezed_cry_tears='挤眼大哭', blank_cry='白眼哭喊',
             horrified='惊恐', sukkiri_squeeze='潮红挤眼喊', sukkiri_dazed='失神', sukkiri_smug='潮红得意',
             sukkiri_grin='翻白眼咧嘴笑', sukkiri_manic='癫狂咧嘴笑', sukkiri_tender='温柔满足')
MODELS = dict(marisa='魔理沙', reimu='灵梦', alice='爱丽丝')
NEW = ['sukkiri_squeeze', 'sukkiri_dazed', 'sukkiri_smug', 'sukkiri_grin', 'sukkiri_manic', 'sukkiri_tender',
       'flushed_tongue', 'teary_tongue']
SEQUENCES = [
    ('父·魔理沙', 'marisa', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'impact'),
                          ('释放 0–6t', 'sukkiri_squeeze'), ('释放 6t–事后 40t', 'sukkiri_dazed'),
                          ('事后 40–120t', 'sukkiri_smug')]),
    ('父·灵梦及其他', 'reimu', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'impact'),
                           ('释放 0–6t', 'sukkiri_squeeze'), ('释放 6t–事后 30t', 'sukkiri_dazed'),
                           ('事后 30–120t', 'satisfied')]),
    ('母方', 'reimu', [('擦蹭', 'flushed_smile'), ('慢顶', 'flushed_tongue'), ('快顶', 'teary_tongue'),
                      ('释放 0–6t', 'sukkiri_squeeze'), ('释放 6t–事后 30t', 'sukkiri_dazed'),
                      ('事后 30–120t', 'sukkiri_tender')]),
    ('爱丽丝·攻击素鸡立', 'alice', [('搜寻追击', 'sukkiri_manic'), ('动作 0–240t（含释放）', 'sukkiri_grin'),
                              ('事后', 'sukkiri_manic')]),
    ('受方', 'reimu', [('压住 0–200t', 'squeezed_cry_tears'), ('释放 200–215t', 'blank_cry'),
                      ('215–240t', 'squeezed_cry_tears'), ('松开后逃跑', 'horrified')]),
]
# Clip strips: (topic, model, clip, times in seconds, beat labels, partner).
CLIPS = [
    ('clip-pair60', 'marisa', 'sukkiri_pair_60', [0.2, 0.66, 1.0, 1.44, 2.1, 2.3, 2.4, 2.95],
     ['擦蹭 4t', '慢顶·顶出 13t', '慢顶·收回 20t', '快顶·顶出 29t', '释放·最深 42t', '颤·左 46t', '颤·右 48t', '松劲 59t'], None),
    ('clip-mamu60', 'reimu', 'sukkiri_mamu_60', [0.6, 0.71, 2.15, 2.35],
     ['母方·回位', '母方·被推 14t', '释放·被推 43t', '随他颤 47t'], None),
    ('clip-alice', 'alice', 'sukkiri_alice_act', [1.0, 1.2, 5.0, 8.5, 9.85, 10.3, 11.6],
     ['10t 一次·回位', '顶出', '6t 一次', '冲刺 3–4t 一次', '最深 197t', '释放颤抖 206t', '松劲 232t'], None),
    ('clip-proud', 'marisa', 'sukkiri_after_proud', [0.6, 1.8, 2.6, 4.55, 5.5],
     ['失神晃·右', '失神晃·左', '挺身后仰', '小弹 91t', '停住'], None),
    ('clip-manic', 'alice', 'sukkiri_after_manic', [0.6, 0.7, 3.2, 5.0], ['咯咯抖·右', '咯咯抖·左', '再一阵', '平静'], None),
    ('clip-tender', 'reimu', 'sukkiri_after_tender', [0.6, 1.8, 3.0], ['失神晃', '失神晃', '温柔满足'], None),
]
ORGAN_BEFORE = '13d758f5a'  # main line before this package
GAME = 72  # face frame shrunk to roughly how large the head reads at a few blocks in game
REFS = {'EH27-007.jpg': 'ehentai/images/EH27/007.jpg', 'EH17-002.webp': 'ehentai/images/EH17/002.webp',
        'M02-003.png': 'images/M02/003-9a21620b.png', 'EH16-002.webp': 'ehentai/images/EH16/002.webp',
        'EH06-002.webp': 'ehentai/images/EH06/002.webp'}


def strip(topic, model, times, face=None, clip='idle', views=((0, 5),), size=(400, 400), partner=None, before=None):
    command = [sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, '--model', model, '--clip', clip,
               '--size', *map(str, size), '--times', ','.join(f'{t:g}s' for t in times)]
    for az, el in views:
        command += ['--view', str(az), str(el)]
    if face:
        command += ['--face', face]
    if partner:
        command += ['--partner', partner[0], '--partner-offset', *map(str, partner[1])]
    if before:
        command += ['--before', before]
    subprocess.run(command, check=True, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(STRIPS), 'PYTHONUTF8': '1'},
                   stdout=subprocess.DEVNULL)


def frames(topic, count):
    atlas = Image.open(STRIPS/topic/'atlas.png').convert('RGBA')
    width = atlas.width//count
    return [atlas.crop((i*width, 0, (i+1)*width, atlas.height)) for i in range(count)]


def face_frame(model, face, prefix='final'):
    return frames(f'{prefix}-{model}-{face}', 1)[0]


def with_game_size(image):
    out = image.copy()
    small = image.resize((GAME, GAME), Image.Resampling.LANCZOS)
    out.alpha_composite(Image.new('RGBA', (GAME+8, GAME+8), (60, 60, 60, 255)), (out.width-GAME-12, out.height-GAME-12))
    out.alpha_composite(small, (out.width-GAME-8, out.height-GAME-8))
    return out


@lru_cache(None)
def corrections():
    return read_json(ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json')


def clip_face(model, clip, t):
    spans = corrections()['models'][model]['clips'][clip]['expression_spans']
    return next(face for start, end, face in spans if start <= t < end)


def render():
    wanted = {(m, f) for m in MODELS for f in NEW} | {(m, f) for _, m, row in SEQUENCES for _, f in row}
    for model, face in sorted(wanted):
        strip(f'final-{model}-{face}', model, [0], face=face)
    for topic, model, clip, times, _, partner in CLIPS:
        # The side row shares one camera, so the stroke reads as movement; the oblique row is one render per beat,
        # so each frame wears the face the clip's own expression spans give at that time.
        strip(f'{topic}-side', model, times, clip=clip, views=((90, 5),), size=(330, 330), partner=partner)
        for i, t in enumerate(times):
            strip(f'{topic}-{i}', model, [t], face=clip_face(model, clip, t), clip=clip, views=((30, 8),),
                  size=(330, 330), partner=partner)
    # Both organs sit on the lower front skin; compare against the main line before this package.
    strip('organ-peni', 'reimu', [0.24], clip='anatomy_peni_extend', views=((0, -8), (60, -5), (90, 0)), before=ORGAN_BEFORE)
    strip('organ-mamu', 'reimu', [0.24], clip='anatomy_mamu_extend', views=((0, -8), (40, -12), (0, -35)), before=ORGAN_BEFORE)


def layout():
    grid = [with_game_size(face_frame(m, f)) for f in NEW for m in MODELS]
    sheet(grid, [f'{LABEL[f]} · {MODELS[m]}' for f in NEW for m in MODELS], OUT/'faces-final.png', 3, (300, 260))
    # Before / after for the short tongue (old strips kept from the proposal round).
    pairs, labels = [], []
    for old, new in (('flushed_tongue', 'flushed_tongue'), ('teary_tongue', 'teary_tongue')):
        for model in MODELS:
            pairs += [with_game_size(face_frame(model, old, 'face')), with_game_size(face_frame(model, new))]
            labels += [f'旧·长舌 · {MODELS[model]}', f'新·短舌 · {MODELS[model]}']
    sheet(pairs, labels, OUT/'tongue-compare.png', 6, (260, 250))
    for i, (_, model, row) in enumerate(SEQUENCES):
        sheet([face_frame(model, f) for _, f in row], [f'{beat}：{LABEL[f]}' for beat, f in row],
              OUT/f'sequence-{i}.png', len(row), (310, 220))
    for topic, _, _, times, labels, _ in CLIPS:
        side = frames(f'{topic}-side', len(times))
        sheet(side+[frames(f'{topic}-{i}', 1)[0] for i in range(len(times))], [f'{label}·{view}' for view in ('侧面', '斜前') for label in labels],
              OUT/f'{topic}.png', len(times), (300, 300))
    for organ in ('peni', 'mamu'):
        shutil.copy2(STRIPS/f'organ-{organ}'/'compare.png', OUT/f'organ-{organ}.png')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--skip-render', action='store_true', help='re-lay the page from existing strips')
    args = ap.parse_args()
    if not args.skip_render:
        render()
    layout()
    (OUT/'refs').mkdir(exist_ok=True)
    for name, source in REFS.items():
        shutil.copy2(ROOT/'wiki/art-references'/source, OUT/'refs'/name)
    (OUT/'index.html').write_text((HERE/'page.html').read_text(encoding='utf-8'), encoding='utf-8')
    print('http://127.0.0.1:8766/.local/previews/sukkiri-plan/index.html')


if __name__ == '__main__':
    main()

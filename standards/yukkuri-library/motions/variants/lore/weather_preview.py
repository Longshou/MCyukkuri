"""Offline strips of the weather and warmth clips on the exported runtime data (no game, browser or compiler).

rain_huddle: a Reimu with a Marisa on her right (rain_huddle_right) and the Marisa with the Reimu on her left
(rain_huddle_left), side by side at the distance the server stands them (the two side reaches of side_profile.json added, as
motor/SideContact does), both on one clip clock;
thunder_cower and sunbathe on a Reimu and a Marisa. Each strip is drawn under the storyboard sheet whose beats it follows
(the aligned panels in .local/generated-images/<clip>/), when they exist. Writes .local/previews/<clip>/strip.png and compare.png.
"""
from pathlib import Path
import json, math, runpy, subprocess
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
# Local previews and storyboard panels live in the main checkout, also when this runs from a worktree.
LOCAL = Path(subprocess.run(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=HERE, capture_output=True,
                            text=True, check=True).stdout.strip()).parent/'.local'
CARRY = runpy.run_path(str(HERE/'carry_hold_preview.py'))
Body, Canvas, expression = CARRY['Body'], CARRY['Canvas'], CARRY['expression']
# Beats sampled from each clip (seconds), matching the storyboard panels in order.
BEATS = {
    'rain_huddle': [0, .15, .3, .6, 1.2, 2.0, 3.0, 3.8],
    'thunder_cower': [0, .4, .9, 1.3, 1.7, 2.0, 2.5, 2.9],
    'sunbathe': [0, .6, 1.2, 3.0, 5.0, 7.0, 9.2, 10.0],
}
EYE = (.55, .5, -1)          # three-quarter front view from slightly above (the face is -Z)
PROFILE = json.loads((HERE.parents[4]/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/side_profile.json').read_text(encoding='utf-8'))


def touching(a, a_side, b, b_side, behind):
    """Centre distance (model units, same scale) at which b, `behind` units behind a, just meets a: SideProfile.touching."""
    A, B, z0 = PROFILE['models'][a][a_side], PROFILE['models'][b][b_side], PROFILE['z0']
    out = 0
    for y, row in enumerate(A):
        for z, ra in enumerate(row):
            if ra <= 0 or y >= len(B): continue
            d0 = z0+z-behind                      # this cell's depth in b's frame
            for zb in range(max(0, math.floor(d0-z0)), min(len(B[y]), math.ceil(d0+1-z0))):
                if B[y][zb] > 0: out = max(out, ra+B[y][zb])
    return out


def stand(a, a_side, b, b_side):
    """The closest stand over the server's offsets (a quarter block in front or behind, in 1/16 steps): (across, behind)."""
    return min(((touching(a, a_side, b, b_side, k), k) for k in range(-4, 5)), key=lambda s: (round(s[0], 9), abs(s[1])))


def frame(canvas, box, items):
    pts = np.concatenate([P*size+offset for _, P, _, size, offset in items])
    focus = np.array([pts[:, 0].mean(), 4.5, pts[:, 2].mean()])
    canvas.scene(box, items, EYE, 9.0 if len(items) > 1 else 12.0, focus)


def posed(body, clip, t):
    return body.posed(body.palette([(body.clips[clip], t)]))


def strip(name):
    cell = (300, 260)
    canvas = Canvas(cell[0]*len(BEATS[name]), cell[1]+30)
    for i, t in enumerate(BEATS[name]):
        box = (i*cell[0], 30, (i+1)*cell[0], 30+cell[1])
        if name == 'rain_huddle':
            reimu, marisa = Body('reimu'), Body('marisa')
            across, behind = stand('reimu', 'right', 'marisa', 'left')
            offset = np.array([across, 0, behind])   # the Marisa on the Reimu's +X side (her right), +Z: behind her
            items = [(reimu, posed(reimu, 'rain_huddle_right', t), expression(reimu, 'rain_huddle_right', t), 1, np.zeros(3)),
                     (marisa, posed(marisa, 'rain_huddle_left', t), expression(marisa, 'rain_huddle_left', t), 1, offset)]
        else:
            body = Body('reimu' if name == 'thunder_cower' else 'marisa')
            items = [(body, posed(body, name, t), expression(body, name, t), 1, np.zeros(3))]
        frame(canvas, box, items)
        canvas.text((i*cell[0]+8, 4), f'{i+1}  t={t:.2f}s', small=True)
    out = LOCAL/'previews'/name
    out.mkdir(parents=True, exist_ok=True)
    canvas.img.save(out/'strip.png')
    panels = [LOCAL/'generated-images'/name/f'animatic_{i+1}.png' for i in range(len(BEATS[name]))]
    if all(panel.exists() for panel in panels):   # the aligned storyboard panels, one above each rendered beat
        both = Image.new('RGB', (canvas.img.width, cell[0]+canvas.img.height), 'white')
        for i, panel in enumerate(panels):
            both.paste(Image.open(panel).convert('RGB').resize((cell[0], cell[0])), (i*cell[0], 0))
        both.paste(canvas.img, (0, cell[0]))
        both.save(out/'compare.png')
    print(out/'strip.png')


if __name__ == '__main__':
    for clip in BEATS:
        strip(clip)

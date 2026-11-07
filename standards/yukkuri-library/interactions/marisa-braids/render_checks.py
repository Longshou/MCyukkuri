"""Render phase key frames of each interaction to reports/current/<action>.png (local, not committed) without a browser.

Poses come straight from build.state(), composed with the same bone convention as
runtime/motion.js (Rz*Ry*Rx about the pivot, scale before rotation, parent first), and
are drawn with tools/static_preview_sheet.py. A visual aid, not motion acceptance.

  python standards/yukkuri-library/interactions/marisa-braids/render_checks.py [action ...]
"""
from pathlib import Path
import math, sys

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[1]/'tools')]
import build
from static_preview_sheet import draw, read_png, write_png
sys.path.insert(0, str(HERE.parents[3]/'scripts'))
from pose_client import compose

RAW = [build.vertices(e, 256) for e in build.elements]

def triangles(action, t):
    pose, _ = build.state(action, t)
    values = [pose.get(b['name'], {}).get('position', [0, 0, 0])+
              pose.get(b['name'], {}).get('rotation', [0, 0, 0])+
              pose.get(b['name'], {}).get('scale', [1, 1, 1]) for b in build.bones]
    world = dict(zip((b['name'] for b in build.bones), compose(build.bones, values=values)))
    tris = []
    for vv, group in zip(RAW, build.groups):
        m = world[group]
        if abs(m[0][0])+abs(m[1][1])+abs(m[2][2]) < 1e-3: continue  # hidden by zero scale
        out = []
        for i in range(0, len(vv), 8):
            p = vv[i:i+3]; n = vv[i+5:i+8]
            q = [sum(m[r][k]*p[k] for k in range(3))+m[r][3] for r in range(3)]
            nn = [sum(m[r][k]*n[k] for k in range(3)) for r in range(3)]; ln = math.hypot(*nn) or 1
            out.append(q+vv[i+3:i+5]+[c/ln for c in nn])
        tris += [out[i:i+3] for i in range(0, len(out), 3)]
    return tris

def sheet(action, yaw=32, pitch=-16, scale=11, span=(32, 26), center_y=8.5, suffix="", times=None):
    recipe = build.recipes['actions'][action]
    if times is None:
        times = [t for t, _ in recipe['phases']]+[recipe['duration']]
        times = [min(recipe['duration'], t+.35) if 0 < t < recipe['duration'] else t for t in times]
    texture = read_png(HERE/'assembled/marisa-braids.png')
    W, H = int(span[0]*scale), int(span[1]*scale)
    rows = [[(234, 231, 222)]*(W*len(times)) for _ in range(H)]
    for i, t in enumerate(times):
        draw(rows, i*W, (W, H), triangles(action, t), texture, yaw, pitch, scale, center_y)
        if i:
            for py in range(H): rows[py][i*W] = (205, 199, 188)
    out = HERE/'reports/current'/f'{action}{suffix}.png'; out.parent.mkdir(parents=True, exist_ok=True); write_png(out, rows)
    print('wrote', out.relative_to(HERE), [round(t, 2) for t in times])

if __name__ == '__main__':
    for action in sys.argv[1:] or list(build.recipes['actions']):
        sheet(action)

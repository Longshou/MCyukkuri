"""Read-only check that faces inside motion clips and presentation rows are timed and fitted by the rules in
standards/yukkuri-library/FACE-MOTION.md.

    python scripts/check_face_fit.py --check [--all | --changed REV]   # exit 1 on a finding above the baseline
    python scripts/check_face_fit.py --list [--all | --changed REV]    # every finding, worst first, no exit code
    python scripts/check_face_fit.py --transitions ID ...             # the metrics of one clip / row (source:id or id)
    python scripts/check_face_fit.py --write-baseline                  # rewrite scripts/face_fit_allow.json (counts may only go down)

Sources, the same the exporters read: lore `recipe.json` clip `faces` (with `base` presets resolved), the toilet-contact recipe,
`presentation/moments.json` step `faces` and `presentation/expressions.json`. Face geometry comes from the expression presets and the
shared face element boxes (the data `ecology-expressions` and `test_expression_geometry.py` bake from).

Findings (thresholds are at the top of the file):
  short-span    a face held shorter than MIN_SPAN seconds (same-face neighbours and a loop's wrap merged)
  switch-rate   more face changes per second than MAX_RATE (a loop counts its wrap)
  eye-jump      the eye anchor of two adjacent faces moves more than MAX_ANCHOR with no closed-eye face between
  eye-size      the eye ink grows or shrinks more than MAX_SIZE with no closed-eye face between
"""
import argparse
import fnmatch
import json
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT/'standards/yukkuri-library'
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
ALLOW = Path(__file__).resolve().parent/'face_fit_allow.json'

MIN_SPAN = 0.5      # seconds a face must be visible; the tier floor (SHORT 30 ticks = 1.5 s) is for reactions, a clip beat is shorter
MAX_RATE = 1.5      # face changes per second over a whole loop or row
MAX_ANCHOR = 0.45   # model units; the eye box is 3.75 x 3.18 and an open eye ink 2.25 x 2.65
MAX_SIZE = 0.9      # model units of eye ink height change (width changes are bounded by MAX_ANCHOR's mirror, see eye())
BLINK = 0.25       # seconds; a closed-eye span this short between two spans of one look is a blink, not a face change
CLOSED_HEIGHT = 1.4  # an eye ink lower than this is a closed or squinted eye: a blink or close bridges any jump

SOURCES = ['standards/yukkuri-library/motions/variants/lore/recipe.json',
           'standards/yukkuri-library/interactions/toilet-contact/recipe.json',
           'standards/yukkuri-library/presentation/moments.json',
           'standards/yukkuri-library/presentation/expressions.json',
           'standards/yukkuri-library/expressions/',
           'standards/yukkuri-library/components/common/face.json']
TRIGGERS = SOURCES + ['scripts/check_face_fit.py', 'scripts/face_fit_allow.json', 'standards/yukkuri-library/FACE-MOTION.md']


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, encoding='utf-8')


def read_source(rel, rev=None):
    if rev is None:
        return json.loads((ROOT/rel).read_text(encoding='utf-8'))
    out = git('show', f'{rev}:{rel}')
    if out.returncode:
        return None
    return json.loads(out.stdout)


@lru_cache(None)
def registry():
    return json.loads((LIB/'expressions/registry.json').read_text(encoding='utf-8'))['faces']


def base_face(face):
    """A/_b animation frames are one face."""
    return registry().get(face, {}).get('frame_of', face)


@lru_cache(None)
def eye(face):
    """Eye ink of one face in model units: [(cx, cy, w, h) left, (cx, cy, w, h) right]; None when a face has no preset."""
    from PIL import Image
    import numpy as np
    path = LIB/f'expressions/presets/{base_face(face)}.json'
    if not path.is_file():
        return None
    preset = json.loads(path.read_text(encoding='utf-8'))
    scale = json.loads((LIB/'body-standard/recipe.json').read_text(encoding='utf-8'))['face_scale']
    boxes = {e['name']: e for e in json.loads((LIB/'components/common/face.json').read_text(encoding='utf-8'))['elements']}
    out = []
    for name in ('face_eye_left', 'face_eye_right'):
        box = boxes[name]
        bw, bh = box['to'][0]-box['from'][0], box['to'][1]-box['from'][1]
        alpha = np.array(Image.open(LIB/preset['parts'][name]).convert('RGBA'))[:, :, 3] > 40
        h, w = alpha.shape
        ys, xs = np.nonzero(alpha)
        if not len(xs):
            out.append((0.0, 0.0, 0.0, 0.0)); continue
        off = preset.get('geometry_offsets', {}).get(name, {})
        sx, sy = off.get('scale', [1, 1, 1])[:2]
        px, py = off.get('position', [0, 0, 0])[:2]
        cx = ((xs.mean()+.5)/w-.5)*bw*sx+px*scale[0]
        cy = (.5-(ys.mean()+.5)/h)*bh*sy+py*scale[1]
        out.append((float(cx), float(cy), float((xs.max()-xs.min()+1)/w*bw*sx), float((ys.max()-ys.min()+1)/h*bh*sy)))
    return out


@lru_cache(None)
def look(face):
    """What a viewer reads as the expression: eye, brow and mouth images and their geometry. Blush, tear and effect parts are left
    out (they follow the face and never read as a change of expression by themselves)."""
    path = LIB/f'expressions/presets/{base_face(face)}.json'
    if not path.is_file():
        return face
    preset = json.loads(path.read_text(encoding='utf-8'))
    names = ('face_eye_left', 'face_eye_right', 'face_brow_left', 'face_brow_right', 'face_mouth')
    return json.dumps([[preset['parts'].get(n) for n in names],
                       [preset.get('geometry_offsets', {}).get(n) for n in names]], sort_keys=True)


def closed(face):
    e = eye(face)
    return e is None or max(h for *_, h in e) < CLOSED_HEIGHT


def anchor_shift(a, b):
    ea, eb = eye(a), eye(b)
    if ea is None or eb is None:
        return 0.0, 0.0
    shift = max(((p[0]-q[0])**2+(p[1]-q[1])**2)**.5 for p, q in zip(ea, eb))
    size = max(abs(p[3]-q[3]) for p, q in zip(ea, eb))
    return shift, size


# ---- timelines -----------------------------------------------------------------------------------------------------------------

class Timeline:
    def __init__(self, source, ident, duration, loop, spans, covers=True):
        self.source, self.ident, self.duration, self.loop = source, ident, duration, loop
        self.spans = spans          # [[start, end, face]] sorted, resolved to base faces
        self.covers = covers        # False: gaps fall back to the body's own face, so only listed neighbours count

    def key(self):
        return f'{self.source}:{self.ident}'


def merged(spans, loop):
    """Neighbours with the same look (and a loop's last and first span) become one run; returns [[start, end, first face]]."""
    spans = [[a, b, base_face(face)] for a, b, face in spans]
    n = len(spans)
    for i in range(0 if loop else 1, n if loop else n-1):   # a blink belongs to the look around it
        a, b, face = spans[i]
        if n > 2 and b-a <= BLINK+1e-6 and closed(face) and look(spans[i-1][2]) == look(spans[(i+1) % n][2]):
            spans[i][2] = spans[i-1][2]
    out = []
    for a, b, face in spans:
        if out and look(out[-1][2]) == look(face) and abs(out[-1][1]-a) < 1e-6:
            out[-1][1] = b
        else:
            out.append([a, b, face])
    wrapped = loop and len(out) > 1 and look(out[0][2]) == look(out[-1][2])
    return out, wrapped


def wraps(t):
    """A loop whose faces end with the motion loop restarts its faces with it; a longer face clock (a performance that outlasts
    the motion loop) never wraps."""
    return t.loop and t.spans[-1][1] <= t.duration*1.01+1e-6


def lengths(t):
    """The timeline as looks held: ([[start, end, first face]], [seconds each is visible]); a wrapped loop joins its last and first run."""
    out, joined = merged(t.spans, wraps(t))
    sizes = [b-a for a, b, _ in out]
    if joined:
        sizes[0] += sizes.pop()
        out = out[:-1]
    return out, sizes


def resolve_clip(clips, name):
    entry = clips[name]
    if 'base' not in entry:
        return entry
    return {**resolve_clip(clips, entry['base']), **{k: v for k, v in entry.items() if k != 'base'}}


def clip_timelines(rev):
    recipe = read_source('standards/yukkuri-library/motions/variants/lore/recipe.json', rev)
    out = []
    if recipe:
        clips = recipe['clips']
        for name in clips:
            row = resolve_clip(clips, name)
            if row.get('faces'):
                out.append(Timeline('clip', name, row['duration'], row.get('loop', False), [list(s) for s in row['faces']]))
    toilet = read_source('standards/yukkuri-library/interactions/toilet-contact/recipe.json', rev)
    if toilet:
        for name, row in toilet['clips'].items():
            if row.get('faces'):
                out.append(Timeline('toilet', name, row['duration_ticks']/20, row.get('loop', False), [list(s) for s in row['faces']]))
    spans = read_source('standards/yukkuri-library/presentation/expressions.json', rev)
    if spans:
        durations = {t.ident: t for t in out}
        for name, rows in spans.items():
            known = durations.get(name)
            end = max(r['to'] for r in rows)
            out.append(Timeline('expressions', name, known.duration if known else end, known.loop if known else False,
                                [[r['from'], r['to'], r['expression']] for r in rows], covers=False))
    return out


def clip_durations(rev):
    out = {}
    recipe = read_source('standards/yukkuri-library/motions/variants/lore/recipe.json', rev)
    if recipe:
        for name in recipe['clips']:
            row = resolve_clip(recipe['clips'], name)
            out[name] = row['duration']
    for path in (ASSETS/'yukkuri').glob('marisa.json'):
        for name, row in json.loads(path.read_text(encoding='utf-8')).get('clips', {}).items():
            out.setdefault(name, row['duration'])
    return out, recipe


def moment_timelines(rev):
    moments = read_source('standards/yukkuri-library/presentation/moments.json', rev)
    if not moments:
        return []
    durations, recipe = clip_durations(rev)
    clips = recipe['clips'] if recipe else {}
    out = []
    for ident, row in moments['moments'].items():
        spans, cursor, covered = [], 0.0, True
        for step in row['steps']:
            length = durations.get(step['clip'], 0)/step['tempo']
            own = resolve_clip(clips, step['clip']).get('faces') if step['clip'] in clips else None
            faces = step['faces']
            if not faces:
                covered = False
            elif own and step['tempo'] == 1 and [[a, face] for a, _, face in own] == [[a, f] for a, f in faces]:
                covered = False   # identical to the clip's own faces: the clip is the finding, not its row
            else:
                for i, (at, face) in enumerate(faces):
                    end = faces[i+1][0] if i+1 < len(faces) else length
                    spans.append([cursor+at, cursor+end, face])
            cursor += length
        if spans:
            out.append(Timeline('moment', ident, cursor, row['loop'], spans, covers=covered and spans[0][0] == 0))
    return out


def timelines(rev=None):
    return clip_timelines(rev)+moment_timelines(rev)


# ---- findings --------------------------------------------------------------------------------------------------------------------

def findings(t):
    """[(kind, severity, detail)] for one timeline; severity orders the worst first."""
    out = []
    spans, sizes = lengths(t)
    if len(spans) < 2:
        return out
    wrap = wraps(t)
    for (a, b, face), size in zip(spans, sizes):
        if size < MIN_SPAN-1e-6:
            out.append(('short-span', MIN_SPAN-size, f'{face} {size:.2f}s at {a:.2f}'))
    changes = len(spans)-1+(1 if wrap else 0)
    window = t.duration if wrap else spans[-1][0]+min(sizes[-1], 1.0)-spans[0][0]
    if window > 0 and changes/window > MAX_RATE+1e-6:
        out.append(('switch-rate', changes/window-MAX_RATE, f'{changes} changes in {window:.2f}s'))
    pairs = list(zip(spans, spans[1:]))
    if wrap:
        pairs.append((spans[-1], spans[0]))
    for (_, _, x), (_, _, y) in pairs:
        shift, size = anchor_shift(x, y)
        if closed(x) or closed(y):
            continue
        if shift > MAX_ANCHOR:
            out.append(('eye-jump', shift-MAX_ANCHOR, f'{x} -> {y} anchor {shift:.2f}'))
        elif size > MAX_SIZE:
            out.append(('eye-size', size-MAX_SIZE, f'{x} -> {y} size {size:.2f}'))
    return out


def run(rev=None, changed_from=None):
    old = {t.key(): [s[:3] for s in t.spans] for t in timelines(changed_from)} if changed_from else None
    result = {}
    for t in timelines(rev):
        if old is not None and old.get(t.key()) == t.spans:
            continue
        rows = findings(t)
        if rows:
            result[t.key()] = rows
    return result


def counts(result):
    return {key: {kind: sum(1 for r in rows if r[0] == kind) for kind in sorted({r[0] for r in rows})} for key, rows in result.items()}


def severity(rows):
    return sum(r[1] for r in rows)


def main(argv=None):
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--check', action='store_true', help='Exit 1 when a timeline has more findings than the baseline allows.')
    ap.add_argument('--list', action='store_true', help='Print every finding, worst timeline first.')
    ap.add_argument('--all', action='store_true', help='Check every timeline (default).')
    ap.add_argument('--changed', metavar='REV', help='Only timelines whose face spans differ from REV.')
    ap.add_argument('--transitions', nargs='+', metavar='ID', help='Print spans and per-pair eye metrics of these clips or rows.')
    ap.add_argument('--write-baseline', action='store_true', help='Rewrite the baseline with the current counts.')
    args = ap.parse_args(argv)
    if args.transitions:
        wanted = set(args.transitions)
        for t in timelines():
            if t.key() in wanted or t.ident in wanted:
                spans, sizes = lengths(t)
                print(f'{t.key()} duration={t.duration} loop={t.loop} covers={t.covers}')
                for (a, b, face), size in zip(spans, sizes):
                    e = eye(face)
                    print(f'  {a:6.2f}-{b:6.2f} held {size:5.2f}s {face:<16} eye {e[0][0]:+.2f},{e[0][1]:+.2f} h{e[0][3]:.2f}' if e else f'  {face} (no preset)')
                for (_, _, x), (_, _, y) in zip(spans, spans[1:]):
                    print(f'  {x} -> {y}: anchor/size {anchor_shift(x, y)[0]:.2f}/{anchor_shift(x, y)[1]:.2f} closed={closed(x) or closed(y)}')
                for row in findings(t):
                    print('  FINDING', *row[::2])
        return 0
    result = run(changed_from=args.changed)
    now = counts(result)
    if args.write_baseline:
        payload = {'note': 'Baseline of existing face-fit offenders (scripts/check_face_fit.py). Counts may only go down; a new or edited timeline must be clean.',
                   'allow': now}
        ALLOW.write_text(json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=True)+'\n', encoding='utf-8', newline='\n')
        print(f'Wrote baseline: {len(now)} timelines, {sum(sum(v.values()) for v in now.values())} findings')
        return 0
    allow = json.loads(ALLOW.read_text(encoding='utf-8'))['allow'] if ALLOW.is_file() else {}
    bad = []
    for key, kinds in now.items():
        for kind, count in kinds.items():
            cap = allow.get(key, {}).get(kind, 0)
            if count > cap:
                bad.append((key, kind, count, cap))
    if args.list:
        for key, rows in sorted(result.items(), key=lambda kv: -severity(kv[1])):
            print(f'{key}  (baseline {allow.get(key, {})})')
            for kind, _, detail in sorted(rows, key=lambda r: -r[1]):
                print(f'    {kind:<11} {detail}')
    for key, kind, count, cap in bad:
        print(f'FAIL {key}: {kind} {count} > allowed {cap}')
        for kind2, _, detail in result[key]:
            if kind2 == kind:
                print(f'    {detail}')
    total = sum(sum(v.values()) for v in now.values())
    base = sum(sum(v.values()) for v in allow.values())
    print(f'face fit: {len(result)} timelines with findings, {total} findings, baseline {base}, {len(bad)} above the baseline'
          +(f' (checked only spans changed since {args.changed})' if args.changed else ''))
    if not args.changed:
        stale = [k for k, kinds in allow.items() for kind, cap in kinds.items() if now.get(k, {}).get(kind, 0) < cap]
        if stale:
            print(f'baseline can be lowered for {len(stale)} timelines: run --write-baseline ({", ".join(sorted(stale)[:6])}...)')
    return 1 if (args.check and bad) else 0


if __name__ == '__main__':
    raise SystemExit(main())

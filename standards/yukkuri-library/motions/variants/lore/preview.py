"""Poses the world-setting clips on the game models: shared by the preview page (births.py, page.py).

Poses src/main/resources/assets/mcyukkuri/yukkuri/<id>.json with its two-bone weights, the
idle clip at t=0 under the clip from the motion corrections (as the game layers them). Faces
follow each clip's expression timeline. A clip is shown only on the forms it applies to; the
aquatic Marisa is drawn without her hat, sitting low in her own larger hat turned over afloat
(marisa_hat_turned). Needs numpy and Pillow.
"""
from pathlib import Path
import json, math
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUT = ROOT/'.local/previews/lore'  # the preview page (index.html) stays local, not committed
BOAT_CLIPS = ('boat_drift', 'boat_steady', 'boat_call', 'boat_brood', 'boat_labor')
# Panels: (label, model id, mode). Land forms never show the boat set; the aquatic Marisa shows only it;
# the young panel shows a baby sitting on its mother's brim.
PANELS = [('魔理沙', 'marisa', 'land'), ('水生魔理沙', 'marisa', 'aquatic'), ('帽中幼体', 'marisa', 'young'), ('灵梦', 'reimu', 'land'),
          ('家油魔理沙', 'house_marisa', 'land'), ('豆形魔理沙', 'marisa_bean', 'land'), ('蕾米莉亚', 'remilia', 'land'),
          ('螺壳魔理沙', 'marisa_tsumuri', 'land')]
FALLBACK_FACE = {'hop': 'happy', 'shell_retract': 'surprise'}


def shown(mode, clip):
    if clip.endswith('_aq'): return False  # the larger aquatic hat floated at a bank: shown in the scenes
    if mode == 'aquatic': return clip in BOAT_CLIPS
    if mode == 'young': return clip == 'hat_cradle'
    return clip not in BOAT_CLIPS and clip != 'hat_cradle'


def euler(r):
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def sample(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]


class Posed:
    def __init__(self, ident, corrections, aquatic=False):
        self.model = m = json.loads((ASSETS/'yukkuri'/f'{ident}.json').read_text(encoding='utf-8'))
        rev = corrections['models'].get(ident, {}); index = {b['name']: i for i, b in enumerate(m['bones'])}
        remap = lambda clip: dict(clip, tracks=[dict(tr, bone=index[rev['bone_names'][tr['bone']]]) for tr in clip['tracks']])
        self.clips = {**m['clips'], **m.get('behavior_clips', {}), **{k: remap(v) for k, v in rev.get('clips', {}).items()}}
        V = np.array(m['vertices']).reshape(-1, 8); W = np.array(m['weights'])
        hidden = tuple(json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants'][ident]['accessory_prefixes']) if aquatic else ()
        keep = [i for p in m['parts'] if not p.get('anatomy') and not (hidden and p['name'].startswith(hidden)) for i in range(p['start'], p['start']+p['count'])]
        self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = W[keep, 0].astype(int), W[keep, 1].astype(int), W[keep, 2:3]
        self.ident, self.aquatic, self.boat = ident, aquatic, 0
        if aquatic:
            # Her own hat as the boat (marisa_hat_turned at the aquatic size, its crown pressed as afloat, hat_vessel.json): static,
            # its brim a freeboard over the water line, the rider on the ledge at the stern as HatBoatEntity seats her. Same atlas.
            vessel = json.loads((ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8')); k = vessel['aquatic_scale']
            prop = json.loads((ASSETS/'yukkuri_props/marisa_hat_turned.json').read_text(encoding='utf-8'))
            B = np.array(prop['vertices']).reshape(-1, 8); bowl = vessel['bowl']; sq = vessel['squash']['bowl']
            seat = np.array([0, bowl['owner_y']*k, vessel['opening'][3]*k-5.9])
            B[:, :3] = B[:, :3]*[k, k*sq, k]+[0, bowl['freeboard']*k, 0]-seat  # drawn under the rider, who stays at the origin
            B[:, 5:8] /= [1, sq, 1]; B[:, 5:8] /= np.linalg.norm(B[:, 5:8], axis=1, keepdims=True)
            self.v = np.r_[self.v, B[:, :3]]; self.uv = np.r_[self.uv, B[:, 3:5]]; self.n = np.r_[self.n, B[:, 5:8]]
            zero = np.zeros(len(B), int)
            self.a, self.b, self.q = np.r_[self.a, zero], np.r_[self.b, zero], np.r_[self.q, np.zeros((len(B), 1))]
            self.boat = len(B)

    def frame(self, name, t):
        pose = {}
        for clip, at in ((self.clips['idle'], 0), (self.clips[name], t)):
            for tr in clip['tracks']:
                pose.setdefault(tr['bone'], {})[tr['channel']] = sample(tr['keys'], at)
        mats = []
        for i, b in enumerate(self.model['bones']):
            p = pose.get(i, {}); m = np.eye(4)
            m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*np.array(p.get('scale', [1, 1, 1]))
            pv = np.array(b['pivot']); m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
            mats.append(mats[b['parent']]@m if b['parent'] >= 0 else m)
        M = np.array(mats); hv = np.c_[self.v, np.ones(len(self.v))]
        pos = np.einsum('nij,nj->ni', M[self.a], hv)[:, :3]*(1-self.q)+np.einsum('nij,nj->ni', M[self.b], hv)[:, :3]*self.q
        nrm = np.einsum('nij,nj->ni', M[self.a][:, :3, :3], self.n)
        if self.boat: pos[-self.boat:], nrm[-self.boat:] = self.v[-self.boat:], self.n[-self.boat:]
        return pos.reshape(-1, 3, 3), self.uv.reshape(-1, 3, 2), nrm.reshape(-1, 3, 3)[:, 0]

    def face(self, name, t):
        clip = self.clips[name]; d = clip['duration']; at = t % d if clip['loop'] else min(t, d)
        for start, end, face in clip.get('expression_spans', []):
            if start <= at < end: return face
        return FALLBACK_FACE.get(name, 'neutral')

    def texture(self, face):
        folder = ASSETS/'textures/entity'/self.ident; path = folder/f'{face}.png'
        return np.array(Image.open(path if path.exists() else folder/'neutral.png').convert('RGBA'))



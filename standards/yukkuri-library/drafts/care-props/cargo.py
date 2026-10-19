"""Hat provisions reuse the existing forage_pile, scaled as one complete heap.

No new food geometry or recolouring. The current forage-pile source (including
its leaf mat, stalks, bundles, wheat, fruit and nuts) is copied intact. Only its
overall scale and location change. Shared server cylinders are measured from
those exact transformed vertices, never maintained as separate coordinates.
"""
import copy
import json
import math
from pathlib import Path
import designs
from kit import unit

ROOT = Path(__file__).resolve().parents[4]
VESSEL_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json'
LAYOUT_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_cargo_layout.json'


def placements():
    vessel=json.loads(VESSEL_PATH.read_text(encoding='utf-8'))
    # The whole original pile rests on the deepest real floor. Modest X
    # compression fits its naturally round footprint into the narrow crown.
    inside=[[]]+[[dict(x=-.55,z=-.10,bottom=-vessel['floor']+.025,
                       scale=[.39*f,.55*f,.55*f])] for f in (.40,.70,1.0)]
    # Temporarily set aside, at most two natural piles; no queue of tiny heaps.
    aside=[[],[dict(x=5.42,z=2.3,bottom=.025,scale=[.08]*3)],
           [dict(x=5.42,z=2.3,bottom=.025,scale=[.115]*3),dict(x=-5.22,z=3.1,bottom=.025,scale=[.09]*3)],
           [dict(x=5.42,z=2.3,bottom=.025,scale=[.145]*3),dict(x=-5.22,z=3.1,bottom=.025,scale=[.12]*3)]]
    return inside,aside


def _source():
    return designs.forage_pile()


def _points(source):
    return [position for part in source.parts for triangle in part['tris'] for position,normal,uv in triangle]


def layout():
    source=_source();points=_points(source)
    min_y=min(p[1] for p in points);max_y=max(p[1] for p in points)
    inside,aside=placements()
    def zones(groups):
        result=[]
        for group in groups:
            row=[]
            for placement in group:
                sx,sy,sz=placement['scale']
                radius=max(math.hypot(p[0]*sx,p[2]*sz) for p in points)
                row.append(dict(x=placement['x'],z=placement['z'],depth=-placement['bottom'],
                                radius=math.ceil(radius*10000)/10000,
                                height=math.ceil((max_y-min_y)*sy*10000)/10000))
            result.append(row)
        return result
    return dict(version=1,status='pending_user_review',units='unsquashed_hat_units',
                source='standards/yukkuri-library/drafts/care-props/cargo.py',
                source_prop='forage_pile',source_factory='designs.py:forage_pile',source_units=4,
                vessel_source=VESSEL_PATH.relative_to(ROOT).as_posix(),
                tier_max_counts=[0,3,7,12],full_tier_excludes_interior=True,
                note='Exact existing forage pile, only whole-pile scaling and placement; the full pile represents 8-12 portions, and side stow is the same inventory. Occupancy encloses the transformed source vertices.',
                tiers=zones(inside),aside_tiers=zones(aside),
                visual_placements=dict(tiers=inside,aside_tiers=aside))


def provisions():
    source=_source();p=copy.deepcopy(source);p.name='hat_provisions';p.parts=[]
    min_y=min(v[1] for v in _points(source))
    inside,aside=placements()
    for side,groups in ((False,inside),(True,aside)):
        for tier,group in enumerate(groups):
            for n,placement in enumerate(group):
                sx,sy,sz=placement['scale']
                for original in source.parts:
                    part=copy.deepcopy(original)
                    part['name']=f'{"aside" if side else "inside"}_tier{tier}_copy{n}_{original["name"]}'
                    part['unit']=tier+(3 if side else 0)
                    part['tris']=[[
                        ([v[0]*sx+placement['x'],(v[1]-min_y)*sy+placement['bottom'],v[2]*sz+placement['z']],
                         unit([normal[0]/sx,normal[1]/sy,normal[2]/sz]),uv)
                        for v,normal,uv in triangle] for triangle in original['tris']]
                    p.parts.append(part)
    return p


CARGO={'hat_provisions':provisions}

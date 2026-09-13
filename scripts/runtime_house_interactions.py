"""Bake house-only bow, short-lock grip and contextual crawl into runtime rigs.

Called by export_runtime_models; writes no files and leaves selected shape sources
untouched. Angles follow the client Rz Ry Rx convention. Model units are 1/16 block.
"""
import math
import json
from pathlib import Path


def transform(p, angle, origin, shift):
    c, s = math.cos(angle), math.sin(angle)
    x, y, z = [p[i]-origin[i] for i in range(3)]
    return [x+origin[0]+shift[0], c*y-s*z+origin[1]+shift[1],
            s*y+c*z+origin[2]+shift[2]]


def aim(source, target):
    """Shortest rigid rotation from source to target, returned as XYZ Euler."""
    a = [v/math.sqrt(sum(x*x for x in source)) for v in source]
    b = [v/math.sqrt(sum(x*x for x in target)) for v in target]
    v = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
    c = sum(x*y for x, y in zip(a, b)); k = 1/(1+c)
    x, y, z = v
    m = [[1-k*(y*y+z*z), -z+k*x*y, y+k*x*z],
         [z+k*x*y, 1-k*(x*x+z*z), -x+k*y*z],
         [-y+k*x*z, x+k*y*z, 1-k*(x*x+y*y)]]
    return [math.degrees(math.atan2(m[2][1], m[2][2])),
            math.degrees(math.asin(max(-1, min(1, -m[2][0])))),
            math.degrees(math.atan2(m[1][0], m[0][0]))]


def add_house_interactions(rig, species):
    carry_recipe = json.loads((Path(__file__).resolve().parents[1] /
        'art/source/standard-runtime/carry-motion/recipe.json').read_text(encoding='utf-8'))
    bones = {b['name']: b for b in rig['bones']}
    # Endpoint centres taken from the fitted short locks, not the adult rig pivots.
    tips = [[sign*1.985, 1.515 if species == 'reimu' else 1.629,
             -1.57 if species == 'reimu' else -1.256] for sign in (-1, 1)]
    grip = {'bones': ['hand_left', 'hand_right'], 'points': tips,
            'contact_seconds': .65, 'release_seconds': .65}
    rig['grip'] = grip
    rig['behavior_clips'] = {}

    def pose(bow, close):
        tracks = {}
        angle = math.radians(-55*bow)
        origin = [0, .95, -1]
        shift = [0, -.40*bow, -.30*bow]

        def affine(name, q=1):
            pivot = bones[name]['pivot']
            desired = transform(pivot, angle, origin, shift)
            tracks[name] = {'rotation': [math.degrees(angle)*q, 0, 0],
                            'position': [(v-p)*q for v, p in zip(desired, pivot)]}

        affine('features_lift')
        for bone in rig['bones']:
            name = bone['name']
            if name.startswith('soft_head_slice_'):
                y = bone['pivot'][1]
                q = min(1, max(0, y/.95)); q = q*q*(3-2*q)
                affine(name, q)
            elif name.endswith('_skin_anchor'):
                affine(name)
        for side, tip in zip(('left', 'right'), tips):
            name = 'hand_'+side; root = bones[name]['pivot']
            root_world = transform(root, angle, origin, shift)
            tip_world = transform(tip, angle, origin, shift)
            sign = -1 if side == 'left' else 1
            source = [v-p for v, p in zip(tip, root)]
            length2 = sum(v*v for v in source)
            # Carry above the forehead: a centre grip at eye height occludes
            # expressions when walking overrides the small upper-body bow.
            destination = [sign*carry_recipe['half_grip_width'],
                           carry_recipe['contact_height']+carry_recipe['lift_height']*(1-bow), 0]
            vertical_reach = math.sqrt(length2-(destination[0]-root_world[0])**2)
            reach = carry_recipe['vertical_reach_share']*vertical_reach
            destination[1] = max(root_world[1]-reach, min(destination[1], root_world[1]+reach))
            remaining = length2-(destination[0]-root_world[0])**2-(destination[1]-root_world[1])**2
            if remaining < 0:
                raise ValueError('Short lock cannot reach the authored grip height')
            destination[2] = root_world[2]-math.sqrt(remaining)
            destination = [p+(v-p)*close for p, v in zip(tip_world, destination)]
            # Undo the upper-body rotation before aiming each short lock.
            relative = [v-p for v, p in zip(destination, root_world)]
            local = transform(relative, -angle, [0, 0, 0], [0, 0, 0])
            tracks[name] = {'rotation': aim(source, local)}
        return tracks

    def crawl_pose(front, abdomen):
        # A sustained waist bend with a small abdominal wave. The
        # literary reference describes dragging, not a measured wave mechanism;
        # these timings and this continuous height field are model adaptation.
        tracks = {}
        angle = math.radians(-22-10*abdomen)
        origin = [0, .95, -1.4]
        # Rotation makes the bow; avoid pulling the upper body away from its base.
        shift = [0, -.035*abdomen, -.05*front]
        for bone in rig['bones']:
            name, pivot = bone['name'], bone['pivot']
            soft = name.startswith('soft_head_slice_')
            if not (soft or name == 'features_lift' or name.endswith('_skin_anchor')):
                continue
            # Finish the blend below the lowest facial patch (rest Y=1.014).
            # The supporting face surface must share its rigid transform; a
            # blend extending to eye height pushes the abdomen through the mouth.
            q = min(1, max(0, (pivot[1]-.25)/.60)) if soft else 1
            q = q*q*(3-2*q)
            upper = transform(pivot, angle, origin, shift)
            # Contact skin stretches slightly while the whole animal drags ahead.
            # Root travel is authored separately; neither edge is planted.
            lower_scale = [1+.008*abdomen, .94-.025*abdomen, 1+.20/1.68*abdomen]
            lower = [pivot[0]*lower_scale[0], pivot[1]*lower_scale[1],
                     .84+(pivot[2]-.84)*lower_scale[2]]
            desired = [a+(b-a)*q for a, b in zip(lower, upper)]
            tracks[name] = {
                'rotation': [math.degrees(angle)*q, 0, 0],
                'position': [v-p for v, p in zip(desired, pivot)],
                'scale': [v+(1-v)*q for v in lower_scale]}
        # Upper attachments remain rigid, so the short locks retain their length.
        return tracks

    # Both ends of adjacent clips are identical: no item or body snap at handoff.
    definitions = {
        'house_pickup': (1.3, False, [(0, 0, 0), (.20, .2, .05), (.50, 1, 1),
                        (.65, 1, 1), (.80, 1, 1), (1.3, .18, 1)]),
        'house_carry': (1, True, [(0, .18, 1), (.5, .20, 1), (1, .18, 1)]),
        'house_deposit': (1.3, False, [(0, .18, 1), (.50, 1, 1), (.65, 1, 1),
                         (.82, .6, .5), (1.3, 0, 0)]),
        'house_crawl': (.6, True, [])}
    # Sample the same 12-tick wave used by HouseCrawlMotion.
    def smooth(q):
        q = max(0, min(1, q))
        return q*q*(3-2*q)
    for tick in range(13):
        extension = smooth(tick/4) if tick <= 4 else 1-smooth((tick-5)/5)
        definitions['house_crawl'][2].append((tick/20, extension, extension))
    for name, (duration, loop, keys) in definitions.items():
        result = {'duration': duration, 'loop': loop, 'tracks': {}}
        for t, bow, close in keys:
            frame = crawl_pose(bow, close) if name == 'house_crawl' else pose(bow, close)
            for bone, channels in frame.items():
                for channel, values in channels.items():
                    result['tracks'].setdefault(bone, {}).setdefault(channel, {})[str(t)] = [round(v, 7) for v in values]
        rig['behavior_clips'][name] = result
    return {'method': 'house_lower_body_bow_and_short_lock_grip',
            'behavior_clips': list(definitions), 'grip': grip,
            'carry_pose': carry_recipe,
            'crawl': {'method': 'bowed_abdominal_drag',
                      'duration': .6, 'period_ticks': 12, 'reach_tick': 4,
                      'gather_tick': 5, 'settle_tick': 10, 'stroke_units': .20,
                      'travel_units': 1.92, 'drag_share': .60,
                      'bow_degrees': [22, 32],
                      'upper_reach_units': .05, 'max_upper_drop_units': .035,
                      'reference': 'https://ncode.syosetu.com/n5569bl/8/',
                      'rigid_face_support_from_y': .85,
                      'interpretation': 'original_model_adaptation_of_described_dragging'}}

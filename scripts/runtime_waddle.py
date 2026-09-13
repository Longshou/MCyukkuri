"""Retarget ordinary walking to the selected runtime bodies, without changing shape.

Called by export_runtime_models. House skin uses height slices rather than the
standard rump hierarchy, so its rear-led sway must animate those actual slices.
"""
import math


def fit_waddle(rig, is_house):
    clip = rig['clips']['waddle']
    tracks = clip['tracks']
    duration = clip['duration']
    times = [round(i * duration / 60, 8) for i in range(61)]

    def wave(t):
        return math.sin(2 * math.pi * t / duration)

    if not is_house:
        # The five ordinary species already bind their rear to these joints.
        for channel, axes in (('position', (0,)), ('rotation', (1,))):
            for value in tracks['rump_sway'][channel].values():
                for axis in axes:
                    value[axis] = round(value[axis] * 1.6, 7)
        tracks['body_motion'] = {'rotation': {
            str(t): [0, round(-2 * wave(t), 7), 0] for t in times}}
    else:
        # Rotate around the face-side base: the rear travels, the face stays calm.
        # Shared height slices and their scalp bindings keep a continuous skin.
        origin_z = -1.45
        for bone in rig['bones']:
            name = bone['name']
            if not (name.startswith('soft_head_slice_') or name.endswith('_skin_anchor')
                    or name == 'features_lift'):
                continue
            x, y, z = bone['pivot']
            height = min(1, max(0, y / 3.82))
            amplitude = 10 - 7 * height * height
            if name == 'features_lift':
                amplitude = 3
            channels = {'rotation': {}, 'position': {}}
            for t in times:
                degrees = amplitude * wave(t)
                angle = math.radians(degrees)
                c, s = math.cos(angle), math.sin(angle)
                channels['rotation'][str(t)] = [0, round(degrees, 7), 0]
                channels['position'][str(t)] = [round(c*x+s*(z-origin_z)-x, 7), 0,
                                                round(-s*x+c*(z-origin_z)+origin_z-z, 7)]
            tracks[name] = channels
        for name in ('upper_body', 'features_squash'):
            tracks[name] = {'scale': {str(t): [1, round(1-.025*wave(t)**2, 7), 1] for t in times}}
    return {'method': 'continuous_house_rear_sway' if is_house else 'enhanced_standard_rump_sway',
            'duration_seconds': duration, 'shape_changed': False, 'status': 'pending_visual_confirmation'}

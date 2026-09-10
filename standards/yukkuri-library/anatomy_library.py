"""Derive mutually exclusive extend/retract clips from the shared actuator settings."""

def compile_anatomy(config):
    clips = {}
    for name, action in config['exported_actions'].items():
        extending = action['direction'] == 'extend'
        duration = config['extend_seconds' if extending else 'retract_seconds']
        tracks = {}
        for part in config['parts']:
            channels = {'position': part['offset']}
            if 'rotation' in part:
                channels['rotation'] = part['rotation']
            tracks[part['bone']] = {}
            for channel, target in channels.items():
                keys = {}
                for i in range(17):
                    phase = i / 16 if extending else 1 - i / 16
                    weight = phase * phase * (3 - 2 * phase) if part['id'] == action['part'] else 0
                    keys[str(round(duration * i / 16, 8))] = [round(v * weight, 8) for v in target]
                tracks[part['bone']][channel] = keys
        clips[name] = {'id': name, 'duration': duration, 'loop': False, 'tracks': tracks}
    return clips

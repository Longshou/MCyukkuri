#!/usr/bin/env python3
"""Capture one full cycle of every packaged action in the running isolated P0 preview.

Writes unmodified Minecraft framebuffer PNGs and JSON state timelines under .local/p0.
The preview must already be loaded by run_p0.py. This is not model approval.
"""
import math
import re
import time
import run_p0 as p0


def main():
    source = p0.ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java'
    actions = re.findall(r'\("([a-z_]+)",\s*([\d.]+),\s*(true|false)\)', source.read_text())
    if len(actions) != 19:
        raise RuntimeError('Expected 19 actions; update the capture contract explicitly.')
    captures = []
    p0.request('preview', 'preview', command='view', view='front')
    for name, duration, loop in actions:
        p0.request('preview', 'preview', command='action', action='idle')
        if name.endswith('_retract'):
            p0.request('preview', 'preview', command='action', action=name.replace('_retract', '_extend'))
            time.sleep(.4)
        result = p0.request('preview', 'record', timeout=45, name='action-' + name,
                            action=name, ticks=math.ceil(float(duration) * 20) + 20, every=2)
        captures.append({'action': name, 'duration': float(duration), 'loop': loop == 'true', **result})
        print(name, result['count'], 'frames', flush=True)
    p0.request('preview', 'preview', command='view', view='house')
    for name in ('side_bend', 'puff', 'taiso'):
        duration = next(float(d) for n, d, _ in actions if n == name)
        result = p0.request('preview', 'record', timeout=45, name='house-' + name,
                            action=name, ticks=math.ceil(duration * 20) + 20, every=2)
        captures.append({'action': name, 'view': 'house', **result})
        print('house', name, result['count'], 'frames', flush=True)
    p0.request('preview', 'preview', command='action', action='idle')
    p0.request('preview', 'preview', command='view', view='front')
    p0.write_json(p0.WORK / 'action-captures.json', captures)


if __name__ == '__main__':
    main()

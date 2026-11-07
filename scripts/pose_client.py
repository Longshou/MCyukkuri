"""Warm JSON-lines client for pose_check.py, shared by authoring tools."""
import atexit
import json
import subprocess
from pose_check import java_command

_server = None


def request(value):
    global _server
    if _server is None:
        _server = subprocess.Popen(java_command() + ['--serve'], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, text=True, encoding='utf-8')
        atexit.register(_server.terminate)
    _server.stdin.write(json.dumps(value, allow_nan=False) + '\n')
    _server.stdin.flush()
    line = _server.stdout.readline()
    if not line:
        raise RuntimeError(f'Pose server exited: {_server.poll()}')
    result = json.loads(line)
    if 'error' in result:
        raise ValueError(result['error'])
    return result


def compose(bones, tracks=(), defaults=(), time=0, values=None, **extra):
    """Return row-major affine matrices; named parents/tracks are accepted at this boundary."""
    names = {b['name']: i for i, b in enumerate(bones)}
    normalized = [{**b, 'parent': names[b['parent']] if isinstance(b['parent'], str)
                   else -1 if b['parent'] is None else b['parent']} for b in bones]
    def channels(rows):
        return [{**r, 'bone': names[r['bone']] if isinstance(r['bone'], str) else r['bone']} for r in rows]
    return request(dict(op='compose', bones=normalized, tracks=channels(tracks),
                        defaults=channels(defaults), time=time, values=values, **extra))['world']

"""Re-evaluate the two labelled sail transfers using independent archived endpoint evidence.

This is an evidence reconstruction, not a general pose evaluator. No drawn-item
coordinates enter the reconstructed anchors. Verdicts use the shared Java checker.
"""
import argparse
import copy
import gzip
import json
import math
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from pose_client import request


def transform(matrix, point):
    return [matrix[12+i] + sum(matrix[4*j+i]*point[j] for j in range(3)) for i in range(3)]


def mouth(frame):
    # YukkuriModel.updateMouthAttachment: bounds in normalized mouth-bone rotation,
    # center x/y and outer lip z; crosswise() offsets the grip five mu along local X.
    matrix = frame['bones']['face_mouth']
    axes = [[matrix[4*j+i] for i in range(3)] for j in range(3)]
    axes = [[x/math.sqrt(sum(v*v for v in axis)) for x in axis] for axis in axes]
    points = [[sum(p[i]*axis[i] for i in range(3)) for axis in axes]
              for tri in frame['meshes']['mouth']['triangles'] for p in tri]
    low = [min(p[i] for p in points) for i in range(3)]
    high = [max(p[i] for p in points) for i in range(3)]
    center = [(low[0]+high[0])/2+5, (low[1]+high[1])/2, low[2]]
    return [sum(axes[j][i]*center[j] for j in range(3)) for i in range(3)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    actor = 'cd7a1675-404a-4ae4-a393-744871b540e7'
    assets = args.capture/'instance/mod/assets/mcyukkuri'
    model = json.loads((assets/'yukkuri/marisa.json').read_text())
    motions = json.loads((assets/'yukkuri_presentation/motion_corrections.json').read_text())
    prop = motions['models']['marisa']['clips']['braid_item_hold']['props']['item']
    pivot = next(b['pivot'] for b in model['bones'] if b['name'] == prop['bone'])
    fit = json.loads((assets/'yukkuri_presentation/placement_fit.json').read_text())['models']['marisa']
    for moved in fit['bone_pivots']:
        if moved['name'] == prop['bone']:
            pivot = moved['pivot']
    # idle has no keyed prop; renderer samples the hold prop at time zero.
    assert prop['keys'][0][0] == 0
    grip_local = [pivot[i]+prop['keys'][0][i+1] for i in range(3)]
    log = (args.capture/'stdout.log').read_text(encoding='utf-8')
    beat = re.search(r'\[body\][^\n]*uuid='+actor+r' t=(\d+)[^\n]*held beat from=MOUTH to=GRIP[^\n]*handover=(\d+)', log)
    start, handover = map(int, beat.groups())
    results = []
    for number, expected_old in ((262, 'suspect'), (263, 'fail')):
        path = args.capture/'sail'/f'pose-{number}-{actor}.json.gz'
        frame = json.loads(gzip.decompress(path.read_bytes()))
        # Keep only small metric inputs in the output; the archived evidence stays untouched.
        sample = {k: frame[k] for k in ('k', 'run', 'win', 'u', 't', 'pt', 'f', 'item')}
        before = request(dict(frames=[sample], metric=['held']))['results'][0]
        assert before['verdict'] == expected_old
        elapsed = frame['t']+frame['pt']-start
        glide = min(3, handover)  # Archived HeldItemPresentation.GLIDE, not a metric band.
        linear = min(1, max(0, (elapsed-(handover-glide))/(2*glide)))
        share = linear*linear*(3-2*linear)
        endpoints = {'from': mouth(frame), 'to': transform(frame['bones'][prop['bone']], grip_local)}
        recorded = endpoints['from'] if elapsed < handover else endpoints['to']
        error = math.dist(recorded, sample['item']['anchor'])
        assert error < .002, (number, 'reconstructed recorded endpoint disagrees', error)
        sample['item']['handover'] = dict(endpoints, share=share)
        after = request(dict(frames=[sample], metric=['held']))['results'][0]
        broken = copy.deepcopy(sample)
        broken['item']['drawn'][13] += 2  # Deliberate 0.125-block vertical displacement.
        fault = request(dict(frames=[broken], metric=['held']))['results'][0]
        assert after['verdict'] == 'pass' and fault['verdict'] == 'fail', (after, fault)
        results.append(dict(frame=number, endpoint_error_mu=error, reconstructed=sample,
                            before=before, after=after, deliberate_break=fault))
        print(f"frame {number}: {before['verdict']} {before['value']:.9f} -> "
              f"{after['verdict']} {after['value']:.9f}; off-path {fault['verdict']} {fault['value']:.9f}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(capture=str(args.capture), beat_start=start,
                                         handover=handover, results=results), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()

"""Select capture evidence by game time; geometry verdicts come only from PoseChecks."""
import json
from pathlib import Path


def read_pose(directory):
    with (Path(directory) / 'pose.jsonl').open(encoding='utf-8') as source:
        rows = []
        for line in source:
            if not line.strip():
                continue
            row = json.loads(line)
            for key in ('meshes', 'bones', 'skin', 'skin_points', 'pairs'):
                row.pop(key, None)
            if 'sup' in row:
                row['sup'].pop('mesh', None)
            rows.append(row)
        return rows


def postprocess(directory, frames):
    """Join exact dense server ticks; do not interpolate or substitute synchronized yaw."""
    from pose_client import request
    import re
    directory = Path(directory)
    poses = read_pose(directory)
    start = frames[0]['gameTime'] - frames[0]['tick']
    end = frames[-1]['gameTime']
    poses = [p for p in poses if start <= p['t'] <= end]
    yaw, events = {}, []
    with (directory.parent / 'stdout.log').open(encoding='utf-8') as log:
        for line in log:
            if '[trace] ' not in line:
                continue
            row = json.loads(line.split('[trace] ', 1)[1])
            if not start <= row['t'] <= end:
                continue
            if row['k'] == 'st' and 'by' in row['st']:
                yaw[row['dim'], row['u'], row['t']] = row['st']['by']
            elif row['k'] == 'ev':
                events.append(row)
    plan = next(p for p in json.loads((directory.parent/'plan.json').read_text()) if p['id'] == directory.name)
    patterns = []
    for path in plan.get('milestones', []):
        for _, predicate in path['steps']:
            if predicate.startswith('seen:'):
                _, category, pattern = predicate.split(':', 2)
                patterns.append((category, re.compile(pattern)))
    milestones = [e for e in events if any(e['cat'] == cat and pattern.search(e['msg']) for cat, pattern in patterns)]
    groups = {}
    for p in poses:
        if p['k'] != 'pose':
            continue
        value = yaw.get((p['dim'], p['u'], p['t']))
        if value is not None:
            p['server'] = dict(t=p['t'], by=value)
        groups.setdefault(p['u'], []).append(p)
    fields = ('v', 'k', 'run', 'win', 'u', 't', 'pt', 'f', 'ct', 'yawsrc', 'yaw', 'server')
    for rows in groups.values():
        result = request(dict(frames=[{k: p[k] for k in fields if k in p} for p in rows], metric=['yaw']))
        for p, metric in zip(rows, result['results'], strict=True):
            p['m'] = [m for m in p['m'] if m['metric'] != 'yaw'] + [metric]
    for event in milestones:
        nearest = min(frames, key=lambda f: abs(f['gameTime']-event['t']))
        nearest.setdefault('milestones', []).append(dict(t=event['t'], cat=event['cat'], msg=event['msg']))
    with (directory/'pose-analysis.jsonl').open('w', encoding='utf-8') as output:
        for p in poses:
            output.write(json.dumps(p, ensure_ascii=False)+'\n')
    return poses


def select_frames(frames, poses, limit=10):
    """Keep endpoints, then distinct metric hits, markers and phase/clip changes.

    Fill unused slots by distance in game time, not by frame-list index. Missing
    pose evidence is an error: old captures cannot establish geometry results.
    """
    if not frames or not poses:
        raise ValueError('frame selection requires screenshots and pose evidence')
    by_frame = {}
    previous = {}
    seen_hits = set()
    for pose in poses:
        if pose.get('k') != 'pose':
            continue
        rank = 0
        for metric in pose['m']:
            signature = pose['u'], metric['metric'], metric['where'], metric['verdict']
            if metric['verdict'] in ('fail', 'suspect') and signature not in seen_hits:
                rank = max(rank, 3)
                seen_hits.add(signature)
        if pose['markers']:
            rank = max(rank, 2)
        state = pose['clip'], pose['phase']
        if pose['u'] in previous and previous[pose['u']] != state:
            rank = max(rank, 1)
        previous[pose['u']] = state
        by_frame[pose['f']] = max(by_frame.get(pose['f'], 0), rank)
    chosen = {0, len(frames) - 1}
    for f in frames:
        if f.get('milestones'):
            by_frame[f['poseFrame']] = max(2, by_frame.get(f['poseFrame'], 0))
    candidates = sorted(range(1, len(frames) - 1), key=lambda i: (-by_frame.get(frames[i]['poseFrame'], 0), frames[i]['gameTime']))
    for i in candidates:
        if len(chosen) >= limit or by_frame.get(frames[i]['poseFrame'], 0) == 0:
            break
        chosen.add(i)
    while len(chosen) < min(limit, len(frames)):
        remaining = set(range(len(frames))) - chosen
        chosen.add(max(remaining, key=lambda i: (min(abs(frames[i]['gameTime'] - frames[j]['gameTime']) for j in chosen), -i)))
    return [frames[i] for i in sorted(chosen)]


def geometry_report(poses):
    """Report every evaluated frame, including faults between selected screenshots."""
    counts = {}
    findings = []
    for pose in poses:
        if pose.get('k') != 'pose':
            continue
        for metric in pose['m']:
            key = metric['metric']
            count = counts.setdefault(key, {})
            verdict = metric['verdict']
            count[verdict] = count.get(verdict, 0) + 1
            if verdict in ('fail', 'suspect', 'error'):
                findings.append(dict(metric, u=pose['u'], t=pose['t'], pt=pose['pt'], f=pose['f']))
    return dict(counts=counts, findings=findings, report_only=True,
                complete=bool(counts) and not any(c.get('error') for c in counts.values()))


def beat_at(beats, tick):
    """Latest explicit second/tick beat at a scene-relative tick; never a panel index."""
    import re
    parsed = []
    for line in beats.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)(s|t)\s+(.+)', line)
        if not match:
            raise ValueError('beats must use explicit times, e.g. "0.5s lift" or "20t land"')
        time = float(match[1]) * (20 if match[2] == 's' else 1)
        parsed.append((time, match[3]))
    eligible = [row for row in parsed if row[0] <= tick]
    return max(eligible)[1] if eligible else ''

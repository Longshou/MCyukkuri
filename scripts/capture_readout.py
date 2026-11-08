"""Select capture evidence by game time; geometry verdicts come only from PoseChecks."""
import json
from pathlib import Path


def read_pose(directory):
    with (Path(directory) / 'pose.jsonl').open(encoding='utf-8') as source:
        return [json.loads(line) for line in source if line.strip()]


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

"""Select capture evidence by game time; geometry verdicts come only from PoseChecks."""
import json
from pathlib import Path


def loop_context(before, after):
    """Supply authored motion only for a saved pair crossing a loop seam."""
    import tempfile
    from pose_client import request
    root = Path(__file__).resolve().parents[1]
    assets = root/'src/main/resources/assets/mcyukkuri'
    model, clip = after['model'], after['clip']
    data = json.loads((assets/'yukkuri'/f'{model}.json').read_text(encoding='utf-8'))
    spec = data.get('clips', {}).get(clip)
    for name in ('placement_fit', 'motion_corrections', 'house_ecology', 'toilet_contact', 'face_library'):
        revision = json.loads((assets/'yukkuri_presentation'/f'{name}.json').read_text(encoding='utf-8'))['models'].get(model, {})
        spec = revision.get('clips', {}).get(clip, spec)
    spec = data.get('behavior_clips', {}).get(clip, spec)
    if (spec is None or not spec['loop'] or before['ct'] < spec['duration']/2
            or after['ct'] >= spec['duration']/2):
        return None
    work = root/'.local/tmp'
    work.mkdir(parents=True, exist_ok=True)
    samples = {}
    with tempfile.TemporaryDirectory(prefix='loop-evidence-', dir=work) as directory:
        output = Path(directory)/'pose.jsonl'
        for name, time in (('start', 0), ('end', spec['duration']-1e-6),
                           ('before', before['ct']), ('after', after['ct'])):
            result = request(dict(model=model, clip=clip, t=str(time), metric=[], pose_out=str(output)))
            if result['fp'] != after['fp'] or before['fp'] != after['fp']:
                return None  # A different asset bundle cannot reconstruct the captured clip.
            samples[name] = json.loads(output.read_text(encoding='utf-8'))['bones']
    return dict(samples, fp=after['fp'], clip=clip, duration=spec['duration'])


def read_pose(directory):
    if not (Path(directory) / 'pose.jsonl').exists():
        return []
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
    """Join dense server ticks, including vanilla's finite yaw interpolation window."""
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
            if not start - 4 <= row['t'] <= end:
                continue
            if row['k'] == 'st' and 'by' in row['st']:
                yaw[row['dim'], row['u'], row['t']] = row['st']['by']
            elif row['k'] == 'ev' and row['t'] >= start:
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
            # Three packet lerp steps plus the renderer's previous/current tick blend.
            ticks = range(p['t'] - 4, p['t'] + 1)
            if all((p['dim'], p['u'], t) in yaw for t in ticks):
                p['server']['window'] = [dict(t=t, by=yaw[p['dim'], p['u'], t]) for t in ticks]
        groups.setdefault(p['u'], []).append(p)
    fields = ('v', 'k', 'run', 'win', 'u', 't', 'pt', 'f', 'ct', 'yawsrc', 'yaw', 'body_yaw', 'server')
    for rows in groups.values():
        result = request(dict(frames=[{k: p[k] for k in fields if k in p} for p in rows], metric=['yaw']))
        for p, metric in zip(rows, result['results'], strict=True):
            p['m'] = [m for m in p['m'] if m['metric'] != 'yaw'] + [metric]
    # Re-evaluate complete saved geometry; unsaved frames retain their measured
    # findings. A nearby screenshot is never substituted for missing matrices.
    import gzip
    previous = {}
    for p in poses:
        if p['k'] != 'pose':
            continue
        if 'evidence' not in p:
            previous.pop(p['u'], None)
            continue
        evidence = (directory/p['evidence']).resolve()
        if evidence.parent != directory.resolve():
            raise ValueError('pose evidence must be beside frames JSONL')
        with gzip.open(evidence, 'rt', encoding='utf-8') as source:
            full = json.load(source)
        if full.get('gap_applicable'):
            gap = request(dict(frames=[full], metric=['gap']))['results'][0]
            p['m'] = [m for m in p['m'] if m['metric'] != 'gap'] + [gap]
        before = previous.get(p['u'])
        if (before and full['f'] == before['f']+1 and full['clip'] == before['clip']
                and full['ct'] < before['ct'] and not full['transition']):
            context = loop_context(before, full)
            if context is not None:
                full['authored_loop'] = context
                jump = request(dict(frames=[before, full], metric=['posejump']))['results'][-1]
                p['m'] = [m for m in p['m'] if m['metric'] != 'posejump'] + [jump]
        previous[p['u']] = full
    for event in milestones:
        nearest = min(frames, key=lambda f: abs(f['gameTime']-event['t']))
        nearest.setdefault('milestones', []).append(dict(t=event['t'], cat=event['cat'], msg=event['msg']))
    with (directory/'pose-analysis.jsonl').open('w', encoding='utf-8') as output:
        for p in poses:
            output.write(json.dumps(p, ensure_ascii=False)+'\n')
    return poses


def select_frames(frames, poses, limit=10):
    """Keep endpoints, then distinct metric hits, markers and phase/clip changes.

    Fill unused slots by distance in game time, even without pose evidence.
    """
    if not frames:
        raise ValueError('frame selection requires screenshots')
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
        # Yaw is joined after capture; a hit can fall between screenshots. Preserve its
        # actual source frame while selecting the nearest available visual evidence.
        shot = min(frames, key=lambda f: abs(f['poseFrame'] - pose['f'])) if rank else None
        if shot is not None:
            by_frame[shot['poseFrame']] = max(by_frame.get(shot['poseFrame'], 0), rank)
            if rank == 3 and shot['poseFrame'] != pose['f']:
                shot.setdefault('nearby_metric_frames', []).append(dict(f=pose['f'], u=pose['u']))
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


def geometry_report(poses, frames=()):
    """Report every evaluated frame, including faults between selected screenshots."""
    counts = {}
    findings = []
    no_evidence = []
    actors = {p['u'] for p in poses if p.get('k') == 'pose'}
    if not actors:
        no_evidence.append(dict(metric='pose', verdict='no-evidence', u=None, f=None,
                                reason='no recorded pose actor in this view'))
    for frame in frames:
        if frame.get('cameraReady') is False:
            no_evidence.append(dict(metric='camera', verdict='no-evidence', u=None, f=frame['poseFrame'],
                                    reason='requested camera has no recorded target pose at screenshot frame'))
        present = {p['u'] for p in poses if p.get('k') == 'pose' and p['f'] == frame['poseFrame']}
        for actor in sorted(actors - present):
            no_evidence.append(dict(metric='pose', verdict='no-evidence', u=actor, f=frame['poseFrame'],
                                    reason='recorded actor absent at screenshot frame'))
    for pose in poses:
        if pose.get('k') != 'pose':
            continue
        for metric in pose['m']:
            if metric['metric'] not in ('pass', 'tilt', 'gap', 'wet', 'held', 'yaw', 'posejump'):
                raise ValueError(f"unknown metric: {metric['metric']}")
            # Preserve the original checker result. Missing observations are coverage
            # gaps, while malformed values and checker failures remain errors.
            reason = metric['where']
            surface_reason = reason
            while surface_reason.startswith('partner: '):
                surface_reason = surface_reason.removeprefix('partner: ')
            missing = metric['verdict'] == 'error' and (
                reason.startswith('missing context: ')
                or surface_reason in (
                    'specialized wound/hat/delivery surface', 'damaged placed hat',
                    'custom held-item renderer', 'missing skinned body surface',
                    'no support triangle under lowest skin point'))
            if missing:
                no_evidence.append(dict(metric, verdict='no-evidence', reason=reason,
                                        input_verdict='error', u=pose['u'], t=pose['t'],
                                        pt=pose['pt'], f=pose['f']))
            key = metric['metric']
            count = counts.setdefault(key, {})
            verdict = 'no-evidence' if missing else metric['verdict']
            count[verdict] = count.get(verdict, 0) + 1
            if verdict in ('fail', 'suspect', 'error'):
                findings.append(dict(metric, u=pose['u'], t=pose['t'], pt=pose['pt'], f=pose['f']))
    return dict(counts=counts, findings=findings, no_evidence=no_evidence, report_only=True,
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

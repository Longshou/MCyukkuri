"""Replay scene END contracts from saved logs; inject a stopped-stage control without changing source evidence."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import museum_scenarios
import smoke_checks
import timeline


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    summary = []
    for path in sorted(args.source.glob('*.log')):
        sid = path.stem.rsplit('-', 1)[0]
        scene = next((s for s in museum_scenarios.S if s.id == sid), None)
        if scene is None:
            continue
        spec = scene.smoke['end']
        rows, issues = timeline.read([path])
        rows = [r for r in rows if r['win'] == sid]
        raw = ''.join(line for line in timeline.lines([path]) if '[trace]' not in line)
        findings = smoke_checks.endings(rows, timeline.states(rows), spec, raw)
        verdict = ('no-evidence' if issues else max((f['verdict'] for f in findings), key=smoke_checks.RANK.get))
        # Keep the original states and identities. Removing a required event category stops a real stage;
        # remove its raw counterpart too, so the bounded legacy event reader cannot restore the injected break.
        cat = spec['paths'][0]['steps'][0][1].split(':', 2)[1]
        stopped = [r for r in rows if not (r['k'] == 'ev' and r.get('cat') == cat)]
        broken_raw = ''.join(line for line in raw.splitlines(keepends=True) if f'[{cat}]' not in line)
        broken = smoke_checks.endings(stopped, timeline.states(stopped), spec, broken_raw)
        broken_verdict = max((f['verdict'] for f in broken), key=smoke_checks.RANK.get)
        assert broken_verdict == 'fail', (sid, broken)
        for finding in findings:
            finding['timeline'] = [r for r in rows if finding['t'] is not None and abs(r['t'] - finding['t']) <= 100
                                   and r['u'] in (finding['actor'], None)]
        seeds = set(re.findall(r'\[seed\].*?\brun=(-?\d+)\b', raw))
        report = dict(scene=sid, evidence='H', seed=int(next(iter(seeds))) if len(seeds) == 1 else None,
                      verdict=verdict, issues=issues, source=str(path.resolve()),
                      rerun=f'{sys.executable} {Path(__file__).resolve()} {args.source.resolve()} --output {args.output.resolve()}',
                      findings=findings, stopped=dict(evidence='F', removed_event_category=cat, verdict=broken_verdict, findings=broken))
        (args.output / (sid + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
        summary.append(dict(scene=sid, verdict=verdict, stopped=broken_verdict))
        print(sid, verdict, 'stopped=' + broken_verdict, flush=True)
        for finding in findings:
            if finding['verdict'] != 'pass':
                print(' ', finding['verdict'], finding['actor'], finding['t'], finding['detail'], flush=True)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf8')
    print('TOTAL', dict(Counter(row['verdict'] for row in summary)), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

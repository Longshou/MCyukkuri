"""One-command capture geometry report plus AI readability review. No desktop automation."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from capture_readout import beat_at

ROOT = Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], text=True).strip()).parent
sys.path.insert(0, str(ROOT / '.local/tools'))
import storyboard


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scene')
    parser.add_argument('--worktree', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--criteria', help='User words or UTF-8 file; scene criteria are always included')
    parser.add_argument('--beats', type=Path)
    parser.add_argument('--ref', nargs='*', default=[])
    parser.add_argument('--reuse', type=Path)
    parser.add_argument('--view', action='append', help='Review only this declared full view ID; repeatable')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--compare-old', type=Path, help='Preserved old capture_review.py: same images, original prompt')
    args = parser.parse_args()
    out = args.out or ROOT/'.local/reports/capture-review'/f'{args.scene}-{time.strftime("%Y%m%d-%H%M%S")}'
    out.mkdir(parents=True, exist_ok=True)
    cap = args.reuse
    if cap is None:
        view_args=[arg for view in args.view or [] for arg in ('--view',view)]
        result = subprocess.run([sys.executable, str(args.worktree/'scripts/capture_museum.py'), '--worktree', str(args.worktree), args.scene, *view_args],
                                text=True, encoding='utf-8', errors='replace', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env={**os.environ, 'PYTHONUTF8':'1'})
        print(result.stdout, flush=True)
        match = re.search(r'Capture output: (.+)', result.stdout)
        if result.returncode or not match:
            raise RuntimeError(f'capture failed with exit {result.returncode}')
        cap = Path(match[1].strip())
    criteria = '' if not args.criteria else Path(args.criteria).read_text(encoding='utf-8') if os.path.isfile(args.criteria) else args.criteria
    beats = args.beats.read_text(encoding='utf-8') if args.beats else ''
    legacy = None
    if args.compare_old:
        spec = importlib.util.spec_from_file_location('legacy_capture_review', args.compare_old)
        legacy = importlib.util.module_from_spec(spec); spec.loader.exec_module(legacy)
    plans = {p['id']: p for p in json.loads((cap/'plan.json').read_text())}
    if args.view and set(args.view)-plans.keys():
        raise ValueError('requested view absent from capture plan')
    jobs, geometry = [], {}
    for manifest in sorted(cap.glob(f'{args.scene}*/selected-frames.json')):
        view = manifest.parent.name
        if args.view and view not in args.view:
            continue
        frames = json.loads(manifest.read_text())
        geometry[view] = json.loads((manifest.parent/'geometry.json').read_text())
        for i, frame in enumerate(frames):
            jobs.append(dict(view=view, panel=i+1, count=len(frames), frame=frame,
                             file=str(manifest.parent/frame['file']), first=str(manifest.parent/frames[0]['file']),
                             criteria=plans[view]['settings'].get('criteria', ''),
                             beat=beat_at(beats, frame['tick'])))
    if not jobs:
        raise ValueError('no selected frame manifests; postprocess the capture first')

    def ask(job):
        images = [job['file'], job['first'], *args.ref]
        prompt = (f'Image 1 is the current frame at scene tick {job["frame"]["tick"]}, view {job["view"]}. '
                  f'Image 2 is this view at its start. User criteria: {criteria}\nView criteria: {job["criteria"]}\n'
                  f'Time-aligned intended beat: {job["beat"]}\n'
                  'Judge ONLY readability: can a viewer see and understand the intended action, expression, contact and participants? '
                  'Note camera distance, occlusion or missing visible action. Do not estimate angles, penetration, contact gaps, '
                  'teleportation, physical correctness or item conservation: those are independently measured. '
                  'Answer JSON: {"readability":"clear|unclear","issues":["concrete readability issue, empty if clear"],"seen":"one sentence"}.')
        try:
            result = storyboard.kimi(images, prompt)
            if result.get('readability') not in ('clear', 'unclear') or not isinstance(result.get('issues'), list) or not result.get('seen'):
                raise ValueError(f'invalid readability response: {result}')
            if (result['readability']=='clear') == bool(result['issues']):
                raise ValueError('contradictory readability/issue list')
            result = {k: result[k] for k in ('readability','issues','seen')}
            if legacy:
                old_prompt = (f'Image 1 is panel {job["panel"]} of {job["count"]}, view {job["view"]}, actual tick {job["frame"]["tick"]}. '
                              f'Image 2 is panel 1 (start state). User criteria: {criteria}\n{job["criteria"]}\n'
                              f'{storyboard.BODY_RULES}\n{legacy.FIXED}\nJudge Image 1 only. Answer JSON: '
                              '{"body_verdict":"ok|problem","framing_verdict":"ok|problem","tilt_deg":0,"body_issues":[],"framing_issues":[],"seen":"one sentence"}.')
                old = storyboard.kimi(images, old_prompt)
                if old.get('body_verdict') not in ('ok','problem'):
                    raise ValueError(f'invalid legacy response: {old}')
                result['old'] = old
        except Exception as error:  # external model/process boundary: failure never becomes an OK review
            result = dict(readability='error', error=str(error))
        result.update(view=job['view'], panel=job['panel'], file=job['file'], tick=job['frame']['tick'], poseFrame=job['frame']['poseFrame'])
        # Persist individual calls so a later provider failure loses no completed evidence.
        (out/f'{job["view"]}-{job["panel"]}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(job['view'], job['panel'], result['readability'], result.get('old',{}).get('body_verdict',''), flush=True)
        return result

    with ThreadPoolExecutor(3) as executor:
        results = list(executor.map(ask, jobs))
    errors = sum(r['readability']=='error' for r in results)
    incomplete = [view for view, report in geometry.items() if not report['complete']]
    # Checks remain report-only until calibrated; no AI opinion can erase a measured finding.
    summary = dict(frames=len(results), readability_errors=errors,
                   unclear=sum(r['readability']=='unclear' for r in results), incomplete_geometry=incomplete,
                   old_body_flags=sum(r.get('old',{}).get('body_verdict')=='problem' for r in results),
                   geometry_fail_samples=sum(m['verdict']=='fail' for report in geometry.values() for m in report['findings']),
                   geometry_no_evidence_samples=sum(len(report.get('no_evidence', [])) for report in geometry.values()),
                   exit_code=2 if errors or incomplete else 0, report_only=True)
    payload = dict(scene=args.scene, capture=str(cap), criteria=criteria, summary=summary, geometry=geometry, frames=results)
    (out/'review.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = [f'# Capture review: {args.scene}', f'Capture: `{cap}`', '', json.dumps(summary,ensure_ascii=False), '',
             'Geometry comes from PoseChecks; AI judges readability only. Old flags are not automatically false positives.', '']
    for r in results:
        lines.append(f'- {r["view"]} tick {r["tick"]}: {r["readability"]}; {r.get("seen",r.get("error",""))}; {r.get("issues",[])}')
    for view, report in geometry.items():
        for gap in report.get('no_evidence', []):
            lines.append(f'- {view} frame {gap["f"]} actor {gap["u"]} {gap["metric"]}: no-evidence; {gap["reason"]}')
    (out/'review.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print('report:', out, json.dumps(summary), flush=True)
    return summary['exit_code']


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'capture review failed: {error}', file=sys.stderr)
        raise SystemExit(2)

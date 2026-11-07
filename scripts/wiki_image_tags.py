"""Tag every wiki image with one resumable, validated Kimi vision workflow.

``run`` updates wiki/reference-library/classifications.json after each image.
Unchanged SHA-256 values are skipped. ``check`` is offline and read-only.
``apply-deletions`` applies a reviewed JSON plan and synchronizes source records;
run the three wiki catalog generators afterwards to rebuild their owned pages.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'wiki/reference-library'
CLASSIFICATIONS = LIB / 'classifications.json'
GAME_ANNOTATIONS = LIB / 'game-annotations.json'
QUALITY = LIB / 'quality-selection.json'
MANIFESTS = [ROOT / 'wiki/art-references/manifest.json', ROOT / 'wiki/art-references/ehentai/manifest.json']
LOG = ROOT / '.local/reports/wiki-image-tags.jsonl'
EXTENSIONS = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'}
KIMI = [
    'D:/NodeJs/node.exe',
    'C:/Users/Administrator/AppData/Roaming/npm/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js',
    '-p', '--no-session', '-nt', '--model', 'kimi-coding/k3.1-pro', '--thinking', 'high',
]
BASE_FACETS = ['entities', 'scenarios', 'emotions', 'targets']
EXTRA_FACETS = ['behaviors', 'appearance']
TAG_FACETS = BASE_FACETS + EXTRA_FACETS
DELETE_REASONS = {'exact_duplicate', 'near_duplicate', 'blank', 'unreadable', 'ui_only', 'no_game_value'}
LOG_LOCK = threading.Lock()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, path)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_paths():
    return sorted((p for p in (ROOT / 'wiki').rglob('*') if p.is_file() and p.suffix.lower() in EXTENSIONS),
                  key=lambda p: p.relative_to(ROOT).as_posix())


def manifest_images():
    rows = {}
    for path in MANIFESTS:
        for row in read(path)['images']:
            rows[row['path']] = row
    return rows


def item_id(path, manifests):
    relative = path.relative_to(ROOT).as_posix()
    if relative in manifests:
        return manifests[relative]['id']
    return relative.removeprefix('wiki/art-references/')


def taxonomy():
    annotations = read(GAME_ANNOTATIONS)
    facets = {key: annotations['facets'][key] for key in BASE_FACETS}
    facets.update(annotations['image_tagging']['facets'])
    return annotations['image_tagging'], facets


def sync_inventory():
    config = read(CLASSIFICATIONS)
    manifests = manifest_images()
    paths = image_paths()
    by_path = {item.get('path'): item for item in config['items'] if item.get('path')}
    by_id = {item['id']: item for item in config['items']}
    items = []
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        iid = item_id(path, manifests)
        item = by_path.get(relative) or by_id.get(iid)
        if item is None:
            item = {
                'id': iid,
                'source_id': manifests[relative]['source_id'] if relative in manifests else 'game',
                'kind': 'inventory',
                'topics': [],
                'classification_basis': '逐图视觉标签；不自动成为精选参考或游戏采用依据',
                'classified_on': datetime.now(timezone.utc).date().isoformat(),
            }
        item['path'] = relative
        item['sha256'] = sha256(path)
        items.append(item)
    config['schema_version'] = 2
    config['items'] = items
    dump(CLASSIFICATIONS, config)
    return config, paths


def fixed_prompt():
    tagging, facets = taxonomy()
    category_text = '\n'.join(f"- {key} ({spec['title']}): {'、'.join(spec['values'])}" for key, spec in facets.items())
    return f"""You are classifying one image from a Yukkuri game-reference wiki. Look at Image 1 itself; do not infer story facts from a title, filename, or surrounding work. A yukkuri is a head-only round creature. Classify visible content for reuse in a Minecraft game with movement, eating, sleep, care, carrying, play, threats, injury, water/hat boats, birth, mating, toilet and human interaction.

Return exactly one JSON object with these keys and no markdown:
{{
  "entities": [], "scenarios": [], "emotions": [], "targets": [],
  "behaviors": [], "appearance": [],
  "reference_value": "high|medium|low|none",
  "delete_candidate": "none|blank|unreadable|ui_only|no_game_value",
  "observation": "one concise Simplified Chinese sentence describing only visible content",
  "game_use": "one concise Simplified Chinese sentence stating the concrete game-model/action/scene reference use, or empty when none",
  "delete_reason": "concise Simplified Chinese evidence when delete_candidate is not none, otherwise empty"
}}

Allowed category values (use exact strings, no invented values; arrays may be empty and must not repeat):
{category_text}

Use 通用油库里 when the depicted species is unclear. Tags describe visible subjects and directly usable reference features, not whether the mechanic is implemented. emotions may be empty for objects or neutral sprites. targets says what the picture can support: 模型、动作、表情、形变、道具、场景、事件. appearance records visible shape, proportions, hair/hat/braid/accessories, faces, material or image type. behaviors records only visible actions, not imagined preceding or following events.
reference_value: high = clear and directly useful; medium = useful with limitations; low = weak, highly redundant or very narrow reference worth keeping; none = no useful game-reference content. delete_candidate is none unless the image itself is clearly blank, unreadable, UI-only with no reference value, or plainly irrelevant to the game. Contact sheets, reconstructed views and small but legible sprites are not UI-only. When in doubt, keep and use low or medium.
"""


def validate_result(result, facets):
    expected = set(TAG_FACETS) | {'reference_value', 'delete_candidate', 'observation', 'game_use', 'delete_reason'}
    if not isinstance(result, dict) or set(result) != expected:
        raise ValueError('JSON keys do not match the schema')
    for key in TAG_FACETS:
        values = result[key]
        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
            raise ValueError(f'{key} must be an array of strings')
        if len(values) != len(set(values)) or not set(values) <= set(facets[key]['values']):
            raise ValueError(f'{key} contains duplicate or unknown values')
    if result['reference_value'] not in {'high', 'medium', 'low', 'none'}:
        raise ValueError('unknown reference_value')
    if result['delete_candidate'] not in {'none', 'blank', 'unreadable', 'ui_only', 'no_game_value'}:
        raise ValueError('unknown delete_candidate')
    for key in ['observation', 'game_use', 'delete_reason']:
        if not isinstance(result[key], str) or len(result[key]) > 500:
            raise ValueError(f'{key} must be a short string')
    if not result['observation'].strip():
        raise ValueError('observation is empty')
    if (result['reference_value'] == 'none') != (result['delete_candidate'] != 'none'):
        raise ValueError('reference_value none and delete_candidate must agree')
    if (result['delete_candidate'] == 'none') == bool(result['delete_reason'].strip()):
        raise ValueError('delete_reason must be empty unless a deletion candidate is given')
    if result['reference_value'] == 'none' and result['game_use'].strip():
        raise ValueError('game_use must be empty for no-value images')
    return result


def log_event(event):
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG_LOCK:
        with LOG.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps({'time': datetime.now(timezone.utc).isoformat(), **event}, ensure_ascii=False) + '\n')


def kimi(path):
    prompt = fixed_prompt()
    process = subprocess.run(KIMI + ['@' + str(path), prompt], stdin=subprocess.DEVNULL,
                             capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
    output = (process.stdout or '').strip()
    if process.returncode:
        raise RuntimeError(f'kimi exited {process.returncode}: {(process.stderr or output).strip()[-500:]}')
    try:
        return json.loads(output, parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
    except json.JSONDecodeError as error:
        raise ValueError(f'not exactly one JSON object: {error}: {output[-500:]}') from error


def tag_one(path, delay, rate_lock, last_start):
    relative = path.relative_to(ROOT).as_posix()
    log_event({'event': 'start', 'path': relative})
    error = None
    for attempt in [1, 2]:
        with rate_lock:
            wait = delay - (time.monotonic() - last_start[0])
            if wait > 0:
                time.sleep(wait)
            last_start[0] = time.monotonic()
        started = time.monotonic()
        try:
            result = kimi(path)
            _, facets = taxonomy()
            validate_result(result, facets)
            log_event({'event': 'model_success', 'path': relative, 'attempt': attempt,
                       'seconds': round(time.monotonic() - started, 3)})
            return result
        except (ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
            error = str(exc)
            log_event({'event': 'invalid', 'path': relative, 'attempt': attempt, 'error': error})
    raise RuntimeError(error)


def run(args):
    config, paths = sync_inventory()
    selected = [path for path in paths if not args.path or any(part in path.relative_to(ROOT).as_posix() for part in args.path)]
    by_path = {item['path']: item for item in config['items']}
    pending = []
    skipped = 0
    for path in selected:
        relative = path.relative_to(ROOT).as_posix()
        item = by_path[relative]
        tags = item.get('image_tags')
        if tags and item['sha256'] == sha256(path) and tags.get('prompt_version') == taxonomy()[0]['prompt_version']:
            skipped += 1
        else:
            pending.append(path)
    if args.limit is not None:
        pending = pending[:args.limit]
    print(json.dumps({'images': len(paths), 'selected': len(selected), 'pending': len(pending), 'skipped': skipped}, ensure_ascii=False), flush=True)
    succeeded, failed, interrupted = 0, 0, False
    rate_lock = threading.Lock()
    last_start = [time.monotonic() - args.delay]
    executor = ThreadPoolExecutor(max_workers=args.workers)
    futures = {executor.submit(tag_one, path, args.delay, rate_lock, last_start): path for path in pending}
    try:
        for future in as_completed(futures):
            path = futures[future]
            relative = path.relative_to(ROOT).as_posix()
            item = by_path[relative]
            try:
                result = future.result()
            except Exception as exc:
                failed += 1
                log_event({'event': 'failed', 'path': relative, 'error': str(exc)})
                print(f'FAIL {relative}: {exc}', flush=True)
                continue
            item['image_tags'] = {
                **result,
                'model': 'kimi-coding/k3.1-pro',
                'prompt_version': taxonomy()[0]['prompt_version'],
                'tagged_at': datetime.now(timezone.utc).isoformat(),
            }
            dump(CLASSIFICATIONS, config)
            succeeded += 1
            log_event({'event': 'saved', 'path': relative, 'reference_value': result['reference_value']})
            print(f'OK {relative}: {result["reference_value"]} ({succeeded + failed}/{len(pending)})', flush=True)
    except KeyboardInterrupt:
        interrupted = True
        for future in futures:
            future.cancel()
        executor.shutdown(wait=False, cancel_futures=True)
        log_event({'event': 'interrupted', 'saved_this_run': succeeded})
        print('INTERRUPTED: completed images were saved; rerun the same command to resume.', flush=True)
    else:
        executor.shutdown()
    summary = {'tagged': succeeded, 'skipped': skipped, 'failed': failed,
               'remaining_selected': len(pending) - succeeded - failed, 'interrupted': interrupted}
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    if failed:
        raise SystemExit(1)


def validate_inventory(config, paths):
    manifests = manifest_images()
    expected_paths = {path.relative_to(ROOT).as_posix() for path in paths}
    assert len(config['items']) == len(expected_paths), 'classification count differs from wiki image count'
    assert {item['path'] for item in config['items']} == expected_paths, 'classification paths differ from wiki images'
    assert len({item['id'] for item in config['items']}) == len(config['items']), 'duplicate classification IDs'
    tagging, facets = taxonomy()
    counts = {key: Counter() for key in TAG_FACETS}
    values = Counter()
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        item = next(item for item in config['items'] if item['path'] == relative)
        assert item['id'] == item_id(path, manifests), relative
        assert item['sha256'] == sha256(path), f'stale SHA: {relative}'
        with Image.open(path) as image:
            image.verify()
        tags = item.get('image_tags')
        assert tags, f'untagged image: {relative}'
        assert tags['model'] == 'kimi-coding/k3.1-pro' and tags['prompt_version'] == tagging['prompt_version'], relative
        validate_result({key: tags[key] for key in set(TAG_FACETS) | {'reference_value', 'delete_candidate', 'observation', 'game_use', 'delete_reason'}}, facets)
        for key in TAG_FACETS:
            counts[key].update(tags[key])
        values[tags['reference_value']] += 1
    return counts, values


def check():
    config = read(CLASSIFICATIONS)
    counts, values = validate_inventory(config, image_paths())
    print(json.dumps({'status': 'PASS', 'images': len(config['items']), 'tagged': sum(values.values()),
                      'reference_value': values, 'categories': counts}, ensure_ascii=False))


def summary():
    config = read(CLASSIFICATIONS)
    counts = {key: Counter() for key in TAG_FACETS}
    values = Counter()
    candidates = Counter()
    tagged = 0
    for item in config['items']:
        tags = item.get('image_tags')
        if not tags:
            continue
        tagged += 1
        values[tags['reference_value']] += 1
        candidates[tags['delete_candidate']] += 1
        for key in TAG_FACETS:
            counts[key].update(tags[key])
    print(json.dumps({'images': len(image_paths()), 'classification_items': len(config['items']), 'tagged': tagged,
                      'reference_value': values, 'delete_candidates': candidates, 'categories': counts}, ensure_ascii=False))


def apply_deletions(args):
    plan = read(Path(args.plan))
    entries = plan['deletions'] if isinstance(plan, dict) else plan
    assert isinstance(entries, list) and entries, 'deletion plan is empty'
    paths = {entry['path'] for entry in entries}
    assert len(paths) == len(entries), 'duplicate paths in deletion plan'
    config = read(CLASSIFICATIONS)
    by_path = {item['path']: item for item in config['items']}
    for entry in entries:
        assert set(entry) == {'path', 'reason', 'detail'}, entry
        assert entry['reason'] in DELETE_REASONS and entry['detail'].strip(), entry
        assert entry['path'] in by_path and (ROOT / entry['path']).is_file(), entry['path']
        if entry['reason'] == 'exact_duplicate':
            assert entry['detail'].startswith('same SHA-256 as '), entry
            canonical = entry['detail'].removeprefix('same SHA-256 as ').strip()
            assert canonical in by_path and (ROOT / canonical).is_file(), entry
            assert sha256(ROOT / canonical) == sha256(ROOT / entry['path']), entry
    annotations = read(GAME_ANNOTATIONS)
    quality = read(QUALITY)
    manifest_data = [read(path) for path in MANIFESTS]
    deleted_ids = {by_path[path]['id'] for path in paths}
    for data in manifest_data:
        data['images'] = [row for row in data['images'] if row['path'] not in paths]
    eh = manifest_data[1]
    for source in eh['sources']:
        removed = [row['gallery_page'] for row in read(MANIFESTS[1])['images']
                   if row['source_id'] == source['id'] and row['path'] in paths]
        if removed:
            source['selected_pages'] = [page for page in source['selected_pages'] if page not in removed]
    eh_registry_path = ROOT / 'wiki/art-references/ehentai/sources.json'
    eh_registry = read(eh_registry_path)
    for source in eh_registry['sources']:
        removed = [row['gallery_page'] for row in read(MANIFESTS[1])['images']
                   if row['source_id'] == source['id'] and row['path'] in paths]
        if removed:
            source['selected_pages'] = [page for page in source['selected_pages'] if page not in removed]
    blog = manifest_data[0]
    blog_registry_path = ROOT / 'wiki/art-references/sources.json'
    blog_registry = read(blog_registry_path)
    for dataset in [blog, blog_registry]:
        for source in dataset['sources']:
            removed = [row['article_image_order'] for row in read(MANIFESTS[0])['images']
                       if row['source_id'] == source['id'] and row['path'] in paths]
            if removed:
                source['excluded_images'] = sorted(set(source.get('excluded_images', [])) | set(removed))
    config['items'] = [item for item in config['items'] if item['path'] not in paths]
    for topic in config['topics']:
        topic['featured'] = [iid for iid in topic['featured'] if iid not in deleted_ids]
    annotations['images'] = [item for item in annotations['images'] if item['id'] not in deleted_ids]
    annotations['image_queue'] = [item for item in annotations['image_queue'] if item['id'] not in deleted_ids]
    quality['items'] = [item for item in quality['items'] if item['id'] not in deleted_ids]
    dump(CLASSIFICATIONS, config)
    dump(GAME_ANNOTATIONS, annotations)
    dump(QUALITY, quality)
    for path, data in zip(MANIFESTS, manifest_data):
        dump(path, data)
    dump(eh_registry_path, eh_registry)
    dump(blog_registry_path, blog_registry)
    for relative in sorted(paths):
        (ROOT / relative).unlink()
        print(f'DELETED {relative}')
    print(json.dumps({'deleted': len(paths), 'by_reason': Counter(entry['reason'] for entry in entries)}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    run_parser = sub.add_parser('run', help='tag all new or changed images and save after each one')
    run_parser.add_argument('--limit', type=int, help='maximum changed images to tag in this run')
    run_parser.add_argument('--path', action='append', help='only paths containing this text; repeatable')
    run_parser.add_argument('--workers', type=int, default=2)
    run_parser.add_argument('--delay', type=float, default=0.8, help='minimum seconds between model-call starts')
    sub.add_parser('check', help='offline validation of every image, SHA, tag and category')
    sub.add_parser('summary', help='print current counts without validating file hashes')
    delete_parser = sub.add_parser('apply-deletions', help='apply a reviewed JSON deletion plan')
    delete_parser.add_argument('--plan', required=True)
    args = parser.parse_args()
    if args.command == 'run':
        if args.workers < 1 or args.delay < 0 or (args.limit is not None and args.limit < 1):
            parser.error('workers and limit must be positive; delay cannot be negative')
        run(args)
    elif args.command == 'check':
        check()
    elif args.command == 'summary':
        summary()
    else:
        apply_deletions(args)


if __name__ == '__main__':
    main()

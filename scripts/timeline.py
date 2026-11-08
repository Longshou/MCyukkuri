#!/usr/bin/env python
"""Parse existing [trace] logs / pose JSONL; export per-window actor timelines. No runtime logging."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path

ENVELOPE = {'v', 'run', 'seq', 't', 'dim', 'win', 'u', 'k', 'src'}


def validate(row):
    """Validate the external record boundary before arithmetic or delta reconstruction."""
    if not isinstance(row, dict) or not ENVELOPE <= row.keys() or row['v'] != 1:
        raise ValueError('missing envelope fields or unsupported version')
    if type(row['seq']) is not int or type(row['t']) not in (int, float) or not math.isfinite(row['t']):
        raise ValueError('seq/t must be finite numeric values')
    if not all(isinstance(row[k], str) for k in ('run', 'dim', 'k', 'src')):
        raise ValueError('run/dim/k/src must be strings')
    if row['k'] == 'life':
        for key in ('age', 'lifespan'):
            if key in row and (type(row[key]) not in (int, float) or not math.isfinite(row[key])):
                raise ValueError(f'{key} must be finite numeric')
    if row['k'] != 'st':
        return
    s = row.get('st')
    if not isinstance(s, dict):
        raise ValueError('st payload must be an object (velocity v lives inside st)')
    for key in ('p', 'v'):
        value = s.get(key)
        if value is not None and (not isinstance(value, list) or len(value) != 3 or
                                  any(type(n) not in (int, float) or not math.isfinite(n) for n in value)):
            raise ValueError(f'{key} must be a finite three-vector or null')
    for key in ('hold', 'att', 'act', 'int', 'need'):
        if s.get(key) is not None and not isinstance(s[key], dict):
            raise ValueError(f'{key} must be an object or null')
    for key in ('careParent', 'socialGreeting'):
        if s.get(key) is not None and not isinstance(s[key], str):
            raise ValueError(f'{key} must be a string or null')
    for key in ('waitElapsed', 'contactProgress', 'careChewTicks', 'dailyContactTicks',
                'thermalRestTicks', 'dailyGreetingWait', 'defenseWait', 'step', 'scale'):
        if s.get(key) is not None and (type(s[key]) not in (int, float) or not math.isfinite(s[key])):
            raise ValueError(f'{key} must be finite numeric or null')


def parse(texts):
    return parse_lines(line for text in texts for line in text.splitlines())


def parse_lines(lines):
    """Merge copied/rotated logs, deduplicate identical records, and expose corrupt/lost evidence."""
    records, issues, seen = [], [], {}
    for number, line in enumerate(lines, 1):
        payload = line.split('[trace] ', 1)[1] if '[trace] ' in line else line.strip()
        structured = '[trace] ' in line or payload.startswith('{')
        if not structured:
            continue
        try:
            row = json.loads(payload)
            validate(row)
            key = row['src'], row['run'], row['seq']
            if key in seen:
                if seen[key] != row:
                    issues.append(f'conflicting record {key}')
                continue
            seen[key] = row
            records.append(row)
        except (ValueError, TypeError) as error:
            issues.append(f'line {number}: {error}')
    records.sort(key=lambda r: (str(r['run']), r['src'], r['seq']))
    previous = {}
    windows = {}
    for row in records:
        key = row['src'], row['run']
        if key in previous and row['seq'] != previous[key] + 1:
            issues.append(f'seq gap {key}: {previous[key]} -> {row["seq"]}')
        previous[key] = row['seq']
        window_key = row['run'], row['dim'], row['win']
        if row['k'] == 'win':
            windows[window_key] = f'{row["win"]}@{row["t"]}#{row["seq"]}'
        row['_window'] = windows.get(window_key, row['win'])
    return records, issues


def read_text(path):
    path = Path(path)
    if path.suffix == '.gz':
        with gzip.open(path, 'rt', encoding='utf-8') as source:
            return source.read()
    return path.read_text(encoding='utf-8-sig')


def read(paths):
    return parse_lines(lines(paths))


def lines(paths):
    for path in paths:
        path = Path(path)
        opener = gzip.open if path.suffix == '.gz' else open
        with opener(path, 'rt', encoding='utf-8-sig') as source:
            yield from source


def scope(row):
    return row['run'], row['dim'], row.get('_window', row['win'])


def states(records):
    """Top-level st deltas: absent = unchanged; explicit null stays unknown. Reset at every window/life boundary."""
    cache, out = {}, []
    for row in records:
        key = (*scope(row), row['u'])
        if row['k'] == 'win':
            cache = {k: v for k, v in cache.items() if k[:2] != key[:2]}
        elif row['k'] == 'life':
            cache.pop(key, None)
        elif row['k'] == 'st':
            state = cache.setdefault(key, {})
            state.update(row['st'])
            out.append(dict(row, state=dict(state)))
    return out


def export(records, directory, issues=()):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    groups = {}
    for row in records:
        groups.setdefault((*scope(row), row['u']), []).append(row)
    entries = []
    for key, rows in groups.items():
        run, dim, win, actor = key
        window = hashlib.sha256(json.dumps(key[:3]).encode()).hexdigest()[:16]
        folder = directory / window
        folder.mkdir(exist_ok=True)
        name = 'world' if actor is None else hashlib.sha256(str(actor).encode()).hexdigest()[:24]
        path = folder / f'{name}.jsonl'
        with path.open('w', encoding='utf-8') as target:
            for row in rows:
                target.write(json.dumps(row, ensure_ascii=False) + '\n')
        entries.append(dict(run=run, dim=dim, win=win, actor=actor, records=len(rows),
                            path=path.relative_to(directory).as_posix()))
    index = dict(schema=1, issues=list(issues), streams=entries)
    (directory / 'index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    return index


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('logs', nargs='+', type=Path)
    ap.add_argument('--output', required=True, type=Path)
    args = ap.parse_args()
    records, issues = read(args.logs)
    print(json.dumps(export(records, args.output, issues), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

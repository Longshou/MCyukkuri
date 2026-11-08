#!/usr/bin/env python
"""Compare ordered INFO gameplay lines from two logs or smoke result JSON files; observation only."""
import argparse
import gzip
import json
from pathlib import Path
import re


def gameplay(path):
    opener = gzip.open if path.suffix == '.gz' else open
    rows = []
    with opener(path, 'rt', encoding='utf-8', errors='replace') as stream:
        for line in stream:
            if '/INFO]' not in line:
                continue
            match = re.search(r'\]: (\[[a-z][a-z-]*\] .*)', line)
            if not match or match[1].startswith(('[trace]', '[aqdiag]', '[layout]')):
                continue
            text = match[1]
            text = re.sub(r'\b(?:t|tick)=\d+', 't=<tick>', text)
            text = re.sub(r'\bpos=-?\d+, -?\d+, -?\d+', 'pos=<pos>', text)
            text = re.sub(r'BlockPos\{x=-?\d+, y=-?\d+, z=-?\d+\}', '<pos>', text)
            # Vanilla entity ids and diagnostic timings are not mod randomness.
            text = re.sub(r'#\d+', '#<id>', text)
            text = re.sub(r'elapsedMs=\d+', 'elapsedMs=<ms>', text)
            # GameplayLog.at and Vec3.toString coordinate tuples.
            number = r'[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?'
            text = re.sub(r'\(' + number + r',\s*' + number + r',\s*' + number + r'\)', '<pos>', text)
            rows.append(text)
    return rows


def compare(left, right):
    a, b = gameplay(left), gameplay(right)
    prefix = 0
    for x, y in zip(a, b):
        if x != y:
            break
        prefix += 1
    return dict(prefix=prefix, left_lines=len(a), right_lines=len(b), identical=bool(a) and a == b,
                evidence='present' if a and b else 'no-evidence',
                first_left=a[prefix] if prefix < len(a) else None,
                first_right=b[prefix] if prefix < len(b) else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('left', type=Path)
    parser.add_argument('right', type=Path)
    args = parser.parse_args()
    if args.left.suffix != '.json':
        print(json.dumps(compare(args.left, args.right), ensure_ascii=False, indent=2))
        return
    a = json.loads(args.left.read_text(encoding='utf-8'))['runs']
    b = json.loads(args.right.read_text(encoding='utf-8'))['runs']
    if [(r['scene'], r['seed']) for r in a] != [(r['scene'], r['seed']) for r in b]:
        parser.error('Scene order and run seeds must match')
    for x, y in zip(a, b):
        result = compare(Path(x['log']), Path(y['log']))
        result.update(scene=x['scene'], seed=x['seed'], outcome_match=x['passed'] == y['passed'],
                      left_passed=x['passed'], right_passed=y['passed'])
        print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()

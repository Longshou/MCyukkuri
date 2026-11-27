#!/usr/bin/env python
"""Machine-wide quiet mode: agent heavy work at idle priority pinned to a few logical CPUs, leaving the rest to the user.

Usage: <py> scripts/quiet_mode.py on [--cpus 12-15] | off | status
`on` writes the main checkout's .local/quiet-mode.json (default: the last four logical CPUs), which every worktree reads.
"""
import json
import os
import sys

import headless_server as server

FLAG = server.COMMON / '.local/quiet-mode.json'


def describe(cpus):
    return f'{cpus[0]}-{cpus[-1]}' if len(cpus) > 1 else str(cpus[0])


def parse_cpus(text):
    try:
        first, _, last = text.partition('-')
        cpus = list(range(int(first), int(last or first) + 1))
    except ValueError:
        raise ValueError(f'--cpus takes logical CPU numbers like 12-15, not {text!r}') from None
    if not cpus:
        raise ValueError(f'--cpus range is empty: {text}')
    if cpus[0] < 0 or cpus[-1] >= os.cpu_count():
        raise ValueError(f'CPUs must be within 0-{os.cpu_count() - 1}: {text}')
    return cpus


def run(argv):
    command = argv[0] if argv else 'status'
    if command == 'on':
        if '--cpus' not in argv:
            text = f'{max(0, os.cpu_count() - 4)}-{os.cpu_count() - 1}'
        elif argv.index('--cpus') + 1 == len(argv):
            raise ValueError('--cpus needs a range like 12-15')
        else:
            text = argv[argv.index('--cpus') + 1]
        cpus = parse_cpus(text)
        FLAG.parent.mkdir(parents=True, exist_ok=True)
        FLAG.write_text(json.dumps({'cpus': cpus}), encoding='utf-8')
        print(f'Quiet mode on: CPUs {describe(cpus)}', flush=True)
    elif command == 'off':
        FLAG.unlink(missing_ok=True)
        print('Quiet mode off', flush=True)
    elif command == 'status':
        cpus = server.quiet_cpus()
        print(f'Quiet mode on: CPUs {describe(cpus)}' if cpus else 'Quiet mode off', flush=True)
    else:
        raise ValueError(f'unknown command {command!r}')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(run(sys.argv[1:]))
    except ValueError as error:
        print(f'ERROR: {error}; usage: quiet_mode.py on [--cpus 12-15] | off | status', file=sys.stderr)
        raise SystemExit(2)

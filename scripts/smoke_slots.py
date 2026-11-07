#!/usr/bin/env python
"""Machine-wide queue for smoke servers and capture clients across all worktrees: at most N at once (default 3,
``--slots`` or MCY_SMOKE_SLOTS), first come first served. Records live in the main checkout's .local/smoke/slots/
(pid and process start time; records of exited runners are reclaimed). ``python scripts/smoke_slots.py`` lists them."""
import json
import os
import time
from contextlib import contextmanager

import headless_server as server

DIRECTORY = server.COMMON / '.local/smoke/slots'
DEFAULT_SLOTS = 3
ENVIRONMENT = 'MCY_SMOKE_SLOTS'


def limit(override=None):
    count = override if override is not None else int(os.environ.get(ENVIRONMENT, DEFAULT_SLOTS))
    if count < 1:
        raise ValueError('Smoke/capture slots must be at least 1')
    return count


def _write(path, row):
    pending = path.with_suffix('.tmp')
    pending.write_text(json.dumps(row), encoding='utf-8')
    pending.replace(path)


def entries():
    """[(path, record)] of live holders (hold-*) and waiters (wait-*); records whose runner has exited are removed."""
    rows = []
    for path in sorted(DIRECTORY.glob('*.json')):
        row = json.loads(path.read_text(encoding='utf-8'))
        if server.process_started(row['pid']) != row['started']:
            path.unlink(missing_ok=True)
            continue
        rows.append((path, row))
    return rows


def others():
    """Live holders and waiters other than this process (the baseline filler yields to any of them)."""
    with server.instance_lock('MCyukkuriSmokeSlots'):
        return [row for _, row in entries() if row['pid'] != os.getpid()]


def describe(rows):
    return ', '.join(f'{row["kind"]} {row["label"][:60]}{"..." if len(row["label"]) > 60 else ""} (pid {row["pid"]})'
                     for row in rows) or 'none'


@contextmanager
def slot(kind, label, slots=None):
    """Hold one slot while a server or capture client runs; waits (one notice) while all are taken."""
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    pid = os.getpid()
    me = dict(pid=pid, started=server.process_started(pid), kind=kind, label=label, since=time.time())
    hold, wait = DIRECTORY / f'hold-{pid}.json', DIRECTORY / f'wait-{pid}.json'
    count = limit(slots)
    announced = False
    try:
        while True:
            with server.instance_lock('MCyukkuriSmokeSlots'):
                rows = entries()
                holders = [row for path, row in rows if path.name.startswith('hold-')]
                ahead = [row for path, row in rows if path.name.startswith('wait-') and row['pid'] != pid and row['since'] < me['since']]
                if len(holders) < count and not ahead:
                    _write(hold, me)
                    wait.unlink(missing_ok=True)
                    break
                if not wait.exists():
                    _write(wait, me)
            if not announced:
                print(f'All {count} smoke/capture slots are taken ({describe(holders)}); waiting in the queue '
                      f'({len(ahead)} ahead)', flush=True)
                announced = True
            time.sleep(5)
        yield
    finally:
        with server.instance_lock('MCyukkuriSmokeSlots'):
            hold.unlink(missing_ok=True)
            wait.unlink(missing_ok=True)


if __name__ == '__main__':
    with server.instance_lock('MCyukkuriSmokeSlots'):
        rows = entries()
    print(f'Slots: {limit()}; holding: {describe([r for p, r in rows if p.name.startswith("hold-")])}; '
          f'waiting: {describe([r for p, r in rows if p.name.startswith("wait-")])}')

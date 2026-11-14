#!/usr/bin/env python
"""Machine-wide queue for smoke servers and capture clients across all worktrees: at most N at once (default by time of day, see default_slots;
``--slots`` or MCY_SMOKE_SLOTS), first come first served. Records live in the main checkout's .local/smoke/slots/
(pid and process start time; records of exited runners are reclaimed). ``python scripts/smoke_slots.py`` lists them."""
import json
import os
import shutil
import time
from contextlib import contextmanager

import headless_server as server
import smoke_prune

DIRECTORY = server.COMMON / '.local/smoke/slots'
# The user is away from this PC on weekdays 03:00-19:00 (local time): heavy load is fine then. Evenings, nights and weekends
# they use it, so agents keep the load low.
BUSY_SLOTS = 6
QUIET_SLOTS = 2
ENVIRONMENT = 'MCY_SMOKE_SLOTS'
# A dense-trace server's uncompressed stdout reaches tens of GB before it is gzipped at stop (F: filled up on 11-08 with three
# running); below this a new server or client waits instead of filling the user's disk.
MIN_FREE_GB = 50


def free_gb():
    return shutil.disk_usage(server.COMMON).free / 2 ** 30


def default_slots(now=None):
    now = now or time.localtime()
    return BUSY_SLOTS if now.tm_wday < 5 and 3 <= now.tm_hour < 19 else QUIET_SLOTS


def limit(override=None):
    count = override if override is not None else int(os.environ.get(ENVIRONMENT, default_slots()))
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
    announced = pruned = short = False
    try:
        while True:
            if free_gb() < MIN_FREE_GB:
                if not pruned:
                    removed = smoke_prune.prune(quick=True)
                    print(f'F: below {MIN_FREE_GB} GB free: shed the large files of {removed} old uncited smoke/capture runs', flush=True)
                    pruned = True
                    continue
                if not short:
                    print(f'Waiting: {free_gb():.0f} GB free, a run needs {MIN_FREE_GB} GB; free space or run '
                          f'scripts/smoke_prune.py with a shorter --keep-hours', flush=True)
                    short = True
                time.sleep(30)
                continue
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

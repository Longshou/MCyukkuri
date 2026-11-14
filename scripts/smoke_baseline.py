#!/usr/bin/env python
"""Fill the smoke baseline of the current origin/main while the machine is idle: no user museum client and nobody else
holding or waiting for a smoke/capture slot. Stops before the next scene as soon as either changes; what ran is kept in
the main checkout's .local/smoke/baseline/<commit>.json, which ``smoke_museum.py --compare`` reads."""
import argparse
import subprocess
import sys
import time
import secrets

sys.dont_write_bytecode = True
import headless_server as server
import smoke_museum as smoke
import smoke_slots as slots


def busy():
    running = server.museum_clients()
    if running:
        return f"the user's museum client is running ({', '.join(running)})"
    queued = slots.others()
    return f'the smoke/capture queue is in use ({slots.describe(queued)})' if queued else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repeat', type=int, default=2, help='Runs per scene to reach (default 2)')
    parser.add_argument('--seed', type=int, default=secrets.randbits(63), help='Post-join actor RNG seed (partial replay)')
    parser.add_argument('--slots', type=int, help=f'Machine-wide limit of smoke/capture servers (default {slots.DEFAULT_SLOTS})')
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: every smoke scene of origin/main)')
    args = parser.parse_args()
    if args.repeat < 1 or args.slots is not None and args.slots < 1:
        parser.error('--repeat and --slots are at least 1')
    subprocess.run(['git', '-C', str(server.COMMON), 'fetch', '-q', 'origin'], check=True)
    commit = smoke.git(server.COMMON, 'rev-parse', 'origin/main')
    reason = busy()
    if reason:
        print(f'Machine busy, baseline of {commit[:9]} not filled: {reason}', flush=True)
        return 0
    args.layout, args.no_wait = False, True
    table = smoke.baseline(commit, args.scenes or None, args, stop=busy)
    smoke.print_table(table, f'Main baseline {commit[:9]} (fate checker {smoke.fate.CHECKER}):')
    return 0


if __name__ == '__main__':
    server.below_normal_priority()
    started = time.monotonic()
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('Interrupted; server cleanup completed.', file=sys.stderr)
        raise SystemExit(130)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise SystemExit(1)
    finally:
        print(f'Wall time: {time.monotonic() - started:.1f}s', flush=True)

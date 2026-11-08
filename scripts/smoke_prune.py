"""Delete old smoke instances and capture runs that no document still points to.

    <py> scripts/smoke_prune.py [--keep-hours 24] [--apply]

Candidates are `.local/smoke/instance-*` / `pack-*` and `.local/captures/<run>/` directories whose own modification time is older than
--keep-hours. A candidate is kept when its directory name appears in docs/, AGENTS.md, scripts/README.md, `.local/HANDOFF.md`,
`.local/refactor/` or `.local/reports/` text files (evidence the TODO, a report or a design cites). Result and baseline indexes under
`.local/smoke/` are not references: they name every run. Without --apply only the plan is printed.
"""
import argparse
import re
import shutil
import sys
import time
import headless_server as server

# Smoke instances and captures of every worktree live in the main checkout.
ROOT = server.COMMON
LOCAL = ROOT / '.local'
TEXT = {'.md', '.json', '.txt', '.html', '.jsonl'}
REFERENCE_ROOTS = [ROOT / 'docs', ROOT / 'AGENTS.md', ROOT / 'scripts/README.md', LOCAL / 'HANDOFF.md', LOCAL / 'refactor', LOCAL / 'reports']
# Raw copied logs inside reports are evidence themselves, not citations; reading them would only slow the scan.
REFERENCE_LIMIT = 4 << 20
# Smoke instances/packs are cited as instance-<12 hex>; capture runs by their <date>-<time>-<6 hex> directory name.
CITED = re.compile(r'(?:instance|pack)-([0-9a-f]{12})|(\d{8}-\d{6}-[0-9a-f]{6})')


def candidates():
    yield from sorted((LOCAL / 'smoke').glob('instance-*'))
    yield from sorted((LOCAL / 'smoke').glob('pack-*'))
    yield from sorted(p for p in (LOCAL / 'captures').iterdir() if p.is_dir())


def references():
    cited = set()
    for root in REFERENCE_ROOTS:
        files = [root] if root.is_file() else root.rglob('*') if root.is_dir() else []
        for f in files:
            if f.is_file() and f.suffix in TEXT and f.stat().st_size <= REFERENCE_LIMIT:
                for instance, capture in CITED.findall(f.read_text(encoding='utf-8', errors='replace')):
                    cited.add(instance or capture)
    return cited


def newest(path):
    # A run creates a new file per window, which updates the directory's own time on NTFS; walking the tree is far too slow.
    return path.stat().st_mtime


def stale(keep_hours=24):
    """Old candidates no document cites."""
    cited = references()
    cutoff = time.time() - keep_hours * 3600
    return [path for path in candidates()
            if path.name.removeprefix('pack-').removeprefix('instance-') not in cited and newest(path) < cutoff]


def shed(path):
    """Delete a run's large parts: its top-level files (window logs, JFR, gzipped stdout) and the copied mod/run trees.
    The many small timeline files left behind hold little space and are slow to delete."""
    for entry in path.iterdir():
        if entry.is_file():
            entry.unlink()
        elif entry.name in ('mod', 'run'):
            shutil.rmtree(entry)


def prune(keep_hours=24, quick=False):
    """Delete the stale candidates (quick: only their large parts); returns how many were touched. Runners and
    landings may prune at the same time, so one machine-wide lock serializes them."""
    with server.instance_lock("MCyukkuriSmokePrune"):
        paths = stale(keep_hours)
        for path in paths:
            shed(path)
            if not quick:
                shutil.rmtree(path)
    return len(paths)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--keep-hours', type=float, default=24)
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args()
    paths = stale(args.keep_hours)
    for path in paths:
        if args.apply:
            shed(path)
            shutil.rmtree(path)
        print(('removed ' if args.apply else 'would remove ') + str(path.relative_to(ROOT)))
    print(f'{len(paths)} {"removed" if args.apply else "to remove (dry run: add --apply to delete)"}')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    main()

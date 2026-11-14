#!/usr/bin/env python
"""Package lifecycle for the lead (docs/roles/lead.md): start, land, runtime, status.

Run with PYTHONUTF8=1: python scripts/pkg.py <start|land|runtime|status> ...
Every step fails loudly with a message; nothing is retried silently.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import headless_server as server  # noqa: E402  (git common root, user-client detection)
import smoke_prune  # noqa: E402

MAIN = server.COMMON
TODO = 'docs/TODO.md'
SECTION = '## 进行中'
PUSH_ATTEMPTS = 5


class Fail(SystemExit):
    def __init__(self, message):
        super().__init__(f'pkg: {message}')


def git(cwd, *args, check=True):
    done = subprocess.run(['git', *args], cwd=cwd, text=True, encoding='utf-8', capture_output=True)
    if check and done.returncode:
        raise Fail(f'git {" ".join(args)} (in {cwd}) failed: {(done.stderr or done.stdout).strip()}')
    return done.stdout.strip()


def worktree_path(name):
    return MAIN / '.worktrees' / name


def require_clean(path, what):
    dirty = git(path, 'status', '--porcelain')
    if dirty:
        raise Fail(f'{what} is dirty ({path}):\n{dirty}')


def require_main_branch():
    branch = git(MAIN, 'branch', '--show-current')
    if branch != 'main':
        raise Fail(f'main checkout is on {branch!r}, expected main')


def todo_bounds(lines):
    """Index range [first table line, one past the last table line) of the 进行中 table."""
    start = next((i for i, line in enumerate(lines) if line.strip() == SECTION), None)
    if start is None:
        raise Fail(f'{TODO} has no "{SECTION}" section')
    table = []
    for i in range(start + 1, len(lines)):
        if lines[i].startswith('## '):
            break
        if lines[i].startswith('|'):
            table.append(i)
    if not table:
        raise Fail(f'{TODO} 进行中 section has no table')
    return table[0], table[-1] + 1


def row_names(text):
    lines = text.splitlines()
    first, end = todo_bounds(lines)
    names = {}
    for line in lines[first:end]:
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        found = re.search(r'\.worktrees/([\w.-]+)', cells[1]) if len(cells) > 1 else None
        if found:
            names[found[1]] = line
    return names


def write_lines(path, lines):
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')


def cmd_start(args):
    name = args.name
    if any(sep in args.title or sep in args.files for sep in ('|', '\n')):
        raise Fail('--title/--files must not contain "|" or newlines (table cell separator)')
    require_main_branch()
    require_clean(MAIN, 'main checkout')
    git(MAIN, 'pull', '--ff-only')
    wt = worktree_path(name)
    if wt.exists() or git(MAIN, 'branch', '--list', name):
        raise Fail(f'worktree or branch {name} already exists')
    todo = MAIN / TODO
    lines = todo.read_text(encoding='utf-8').splitlines()
    if name in row_names('\n'.join(lines)):
        raise Fail(f'{TODO} already has a row for {name}')
    _, end = todo_bounds(lines)
    lines.insert(end, f'| {args.title} | `.worktrees/{name}` | {args.files} |')
    write_lines(todo, lines)
    git(MAIN, 'add', TODO)
    git(MAIN, 'commit', '-q', '-m', f'TODO: register {name}')
    git(MAIN, 'push', 'origin', 'HEAD:main')
    git(MAIN, 'worktree', 'add', '-b', name, str(wt), 'origin/main')
    brief = MAIN / '.local/refactor/briefs' / f'{name}.md'
    if not brief.exists():
        art = ('Art / motion: role file `docs/roles/art.md` (storyboard or concept from the image liaison first, visual review by '
               'another agent, previews under `.local/previews/`).\n') if args.art else ''
        brief.parent.mkdir(parents=True, exist_ok=True)
        brief.write_text(
            f'# {name} — {args.title}\n\n'
            f'Worktree `F:/yukkuri/.worktrees/{name}`. Role: (TODO: coder / art / ...). Read `F:/yukkuri/AGENTS.md`, your role file under `docs/roles/` and the model notes in `.local/refactor/model-notes/`.\n'
            f'Owner files: {args.files}\n'
            f'{art}\n'
            '## Goal\n\n(TODO)\n\n'
            '## User words\n\n(TODO: verbatim acceptance wording)\n\n'
            '## Hard gates (fail the package)\n\n- (TODO: deaths, drownings, new loops, ...)\n\n'
            '## Reported only\n\n- (TODO)\n\n'
            '## Scenes\n\n- (TODO: scene IDs and where to find them)\n', encoding='utf-8', newline='\n')
        print(f'brief skeleton: {brief}')
    print(f'registered {name}: commit {git(MAIN, "rev-parse", "--short", "HEAD")} pushed; worktree {wt}')


def dry_copy(name, wt):
    """Temporary detached copy for land --dry-run; rebasing it cannot move the package branch."""
    tmp = MAIN / '.local/tmp' / f'pkg-dry-{name}'
    if tmp.exists():
        raise Fail(f'temp copy already exists: {tmp}')
    tmp.parent.mkdir(parents=True, exist_ok=True)
    git(MAIN, 'worktree', 'add', '--detach', str(tmp), git(wt, 'rev-parse', 'HEAD'))
    return tmp


def drop_worktree(path):
    """Remove a worktree that holds no uncommitted state; never leave it silently."""
    done = subprocess.run(['git', 'worktree', 'remove', '--force', str(path)], cwd=MAIN, text=True, capture_output=True)
    if done.returncode:
        git(MAIN, 'worktree', 'prune')
        if path.exists():
            raise Fail(f'cannot remove {path}: {(done.stderr or done.stdout).strip()}')


def check_tree(tree, scenes):
    """Compile check when Java changed, then the requested smoke scenes (never the whole battery)."""
    changed = git(tree, 'diff', '--name-only', 'origin/main...HEAD').splitlines()
    if any(path.endswith('.java') for path in changed):
        print('java changed: javac_check.sh', flush=True)
        if subprocess.run(['bash', 'scripts/javac_check.sh', str(tree)], cwd=tree).returncode:
            raise Fail('javac_check.sh failed')
    if scenes:
        print(f'smoke: {" ".join(scenes)}', flush=True)
        code = subprocess.run([sys.executable, str(tree / 'scripts/smoke_museum.py'), '--worktree', str(tree), *scenes], cwd=tree).returncode
        if code:
            print(f'pkg: smoke_museum.py exited {code}; not landing', file=sys.stderr)
            raise SystemExit(code)


def row_removed_in_head(tree, name):
    """True when HEAD already carries this package's row removal; a rerun continues instead of refusing."""
    subject = f'TODO: {name} landed'
    if subject not in git(tree, 'log', '--format=%s', 'origin/main..HEAD').splitlines():
        return False
    print(f'{name}: row already removed by {subject}; continuing that landing', flush=True)
    return True


def push_landing(tree, name):
    """A lost push race means another package landed: rebase onto it, recompile, push again."""
    for attempt in range(1, PUSH_ATTEMPTS + 1):
        done = subprocess.run(['git', 'push', 'origin', 'HEAD:main'], cwd=tree, text=True, encoding='utf-8', capture_output=True)
        if not done.returncode:
            return git(tree, 'rev-parse', 'HEAD')
        git(tree, 'fetch', '-q', 'origin')
        # Worktrees share remote refs, so another landing may already have fetched the new tip: compare with HEAD, not the old ref.
        if not subprocess.run(['git', 'merge-base', '--is-ancestor', 'origin/main', 'HEAD'], cwd=tree).returncode:
            raise Fail(f'git push origin HEAD:main failed: {(done.stderr or done.stdout).strip()}')
        message = next((line.strip() for line in (done.stderr or done.stdout).splitlines()
                        if 'rejected' in line or line.startswith(('error:', 'fatal:'))), 'unknown')
        print(f'{name}: push rejected ({attempt}/{PUSH_ATTEMPTS}): {message}; origin/main moved to '
              f'{git(tree, "rev-parse", "--short", "origin/main")}, rebasing the landing commits', flush=True)
        git(tree, 'rebase', 'origin/main')
        # The scene content already ran its smoke; only the rebase needs the compile check.
        check_tree(tree, [])
    raise Fail(f'{name}: push still rejected after {PUSH_ATTEMPTS} attempts')


def cmd_land(args):
    name = args.name
    wt = worktree_path(name)
    if not wt.is_dir():
        raise Fail(f'no worktree {wt}')
    require_clean(wt, 'worktree')
    require_main_branch()
    git(wt, 'fetch', '-q', 'origin')
    tree = dry_copy(name, wt) if args.dry_run else wt
    try:
        git(tree, 'rebase', 'origin/main')
        check_tree(tree, args.scene)
        landed = row_removed_in_head(tree, name)
        text = (tree / TODO).read_text(encoding='utf-8')
        row = row_names(text).get(name)
        if not landed and row is None:
            raise Fail(f'{TODO} has no row for {name} (worktree .worktrees/{name})')
        head = git(tree, 'rev-parse', 'HEAD')
        if not landed and not args.dry_run:
            lines = text.splitlines()
            lines.remove(row)
            write_lines(tree / TODO, lines)
            git(tree, 'add', TODO)
            git(tree, 'commit', '-q', '-m', f'TODO: {name} landed')
    finally:
        if tree is not wt:
            drop_worktree(tree)
    if args.dry_run:
        print(f'dry run: {name} would land at {head} (rebased, checks passed); no push/row/worktree changes')
        return
    pushed = push_landing(tree, name)
    git(MAIN, 'pull', '--ff-only')
    removed = False
    for attempt in range(2):
        done = subprocess.run(['git', 'worktree', 'remove', '--force', str(wt)], cwd=MAIN, text=True, capture_output=True)
        if done.returncode == 0:
            removed = True
            break
        git(MAIN, 'worktree', 'prune')
        if not wt.exists():
            removed = True
            break
        time.sleep(2)
    if not removed:
        print(f'pkg: worktree directory left over (locked?): {wt}', file=sys.stderr)
    git(MAIN, 'branch', '-d', name)
    print(f'landed {name}: pushed {pushed}')
    print(f'shed the large files of {smoke_prune.prune(quick=True)} old uncited smoke/capture runs')


def find_run(sha):
    out = subprocess.check_output(['gh', 'run', 'list', '--workflow', 'build.yml', '--commit', sha,
                                   '--json', 'databaseId,status,conclusion', '-L', '5'], cwd=MAIN, text=True, encoding='utf-8')
    runs = json.loads(out)
    return runs[0] if runs else None


def wait_build(sha, args):
    print(f'waiting for Build run of {sha[:9]}', flush=True)
    appear_deadline = time.monotonic() + args.appear_timeout
    while True:
        run = find_run(sha)
        if run is None and time.monotonic() > appear_deadline:
            raise Fail(f'no Build run for {sha} appeared within {args.appear_timeout}s')
        if run and run['status'] == 'completed':
            break
        time.sleep(args.poll)
    if run['conclusion'] not in ('success', 'cancelled'):
        log = subprocess.run(['gh', 'run', 'view', str(run['databaseId']), '--log-failed'], cwd=MAIN, text=True,
                             encoding='utf-8', capture_output=True).stdout.splitlines()
        print('\n'.join(log[-60:]))
        raise Fail(f'Build run {run["databaseId"]} for {sha} ended {run["conclusion"]}')
    return run


def cmd_runtime(args):
    git(MAIN, 'fetch', '-q', 'origin')
    while True:
        sha = git(MAIN, 'rev-parse', args.commit or 'origin/main')
        run = wait_build(sha, args)
        if run['conclusion'] == 'cancelled':
            # A newer push cancels the older Build of the same branch; following main means following that push.
            git(MAIN, 'fetch', '-q', 'origin')
            moved = git(MAIN, 'rev-parse', 'origin/main')
            if args.commit or moved == sha:
                raise Fail(f'Build run {run["databaseId"]} for {sha} ended cancelled')
            print(f'Build run {run["databaseId"]} for {sha[:9]} was cancelled; following origin/main {moved[:9]}', flush=True)
            continue
        print(f'Build run {run["databaseId"]} succeeded; waiting for the museum client to close', flush=True)
        # Running smoke/capture servers keep the runtime dir they started with; the next update removes it once unused.
        server.wait_closed(poll=args.poll)
        if args.commit:
            break
        git(MAIN, 'fetch', '-q', 'origin')
        moved = git(MAIN, 'rev-parse', 'origin/main')
        if moved == sha:
            break
        print(f'origin/main moved to {moved[:9]} while waiting; following it', flush=True)
    require_main_branch()
    require_clean(MAIN, 'main checkout')
    if args.dry_run:
        print(f'dry run: would ff main to {sha} and run update_museum_runtime.py --run {run["databaseId"]}')
        return
    git(MAIN, 'merge', '--ff-only', sha)
    code = subprocess.run([sys.executable, str(MAIN / 'scripts/update_museum_runtime.py'), '--run', str(run['databaseId'])], cwd=MAIN).returncode
    if code:
        raise SystemExit(code)


def report_status(name):
    path = MAIN / '.local/refactor/reports' / f'{name}.md'
    if not path.is_file():
        return None
    lines = [l for l in path.read_text(encoding='utf-8').splitlines() if l.startswith('STATUS:')]
    return lines[-1] if lines else '(report without STATUS line)'


def worktrees():
    found = {}
    block = {}
    for line in git(MAIN, 'worktree', 'list', '--porcelain').splitlines() + ['']:
        if not line:
            path = Path(block.get('worktree', ''))
            if block.get('branch') and path.parent == MAIN / '.worktrees':
                found[path.name] = block['branch'].removeprefix('refs/heads/')
            block = {}
        else:
            key, _, value = line.partition(' ')
            block[key] = value
    return found


def cmd_status(args):
    git(MAIN, 'fetch', '-q', 'origin')
    rows = row_names(git(MAIN, 'show', f'origin/main:{TODO}'))
    trees = worktrees()
    problems = []
    for name in sorted(set(rows) | set(trees)):
        info = []
        if name in trees:
            wt = worktree_path(name)
            ahead = git(wt, 'rev-list', '--count', 'origin/main..HEAD')
            dirty = len(git(wt, 'status', '--porcelain').splitlines())
            info.append(f'worktree=yes branch={trees[name]} ahead={ahead} dirty={dirty}')
        else:
            info.append('worktree=no')
            ahead = '0'
        report = report_status(name)
        info.append(f'report={report or "none"}')
        info.append(f'todo-row={"yes" if name in rows else "no"}')
        print(f'{name}: ' + ' '.join(info))
        if name in rows and name not in trees:
            problems.append(f'{name}: TODO row without worktree')
        if name in trees and name not in rows:
            problems.append(f'{name}: worktree without TODO row')
        if report == 'STATUS: DONE' and (name in trees or name in rows):
            problems.append(f'{name}: report DONE but not landed (ahead={ahead})')
    print('inconsistencies:' if problems else 'inconsistencies: none')
    for problem in problems:
        print(f'  {problem}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('start', help='register the TODO row, push, create the worktree and a brief skeleton')
    p.add_argument('name')
    p.add_argument('--title', required=True)
    p.add_argument('--files', required=True)
    p.add_argument('--art', action='store_true', help='art/motion package: brief points at docs/roles/art.md')
    p.set_defaults(run=cmd_start)
    p = sub.add_parser('land', help='rebase, check, smoke, drop the TODO row, push, fast-forward main, remove worktree')
    p.add_argument('name')
    p.add_argument('--scene', action='append', default=[], metavar='ID', nargs='+')
    p.add_argument('--dry-run', action='store_true', help='run every check on a temporary detached copy; no push/row/worktree changes')
    p.set_defaults(run=cmd_land)
    p = sub.add_parser('runtime', help='wait for CI on a commit, then fast-forward main and install the runtime')
    p.add_argument('--commit')
    p.add_argument('--poll', type=int, default=60)
    p.add_argument('--appear-timeout', type=int, default=600)
    p.add_argument('--dry-run', action='store_true', help='stop before fast-forwarding and installing')
    p.set_defaults(run=cmd_runtime)
    p = sub.add_parser('status', help='one line per package plus inconsistencies')
    p.set_defaults(run=cmd_status)
    args = parser.parse_args()
    if args.command == 'land':
        args.scene = [s for group in args.scene for s in group]
    args.run(args)


if __name__ == '__main__':
    server.below_normal_priority()
    main()

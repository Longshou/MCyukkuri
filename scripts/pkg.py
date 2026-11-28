#!/usr/bin/env python
"""Package lifecycle for the lead (docs/roles/lead.md): start, land, runtime, status.

Run with PYTHONUTF8=1: python scripts/pkg.py <start|land|runtime|status> ...
Every step fails loudly with a message; nothing is retried silently.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import export  # noqa: E402  (generated-output map and re-run, next to the exporter table)
import headless_server as server  # noqa: E402  (git common root, user-client detection)
import smoke_prune  # noqa: E402
import update_museum_runtime as installer  # noqa: E402  (Build run matching shared with the installer)

MAIN = server.COMMON
TODO = 'docs/TODO.md'
SECTION = '## 进行中'
PUSH_ATTEMPTS = 5
DOC_REFS = 'scripts/check_doc_refs.py'
DOC_TRIGGERS = ('scripts/museum_scene_numbers.json', 'scripts/playtest_watch.json')
# Sources of face timelines and of the face geometry the checker reads; a change runs scripts/check_face_fit.py before landing.
FACE_FIT_SOURCES = ('standards/yukkuri-library/motions/', 'standards/yukkuri-library/presentation/', 'standards/yukkuri-library/expressions/',
                    'standards/yukkuri-library/interactions/toilet-contact/', 'standards/yukkuri-library/components/common/face.json',
                    'scripts/check_face_fit.py', 'scripts/face_fit_allow.json')
# Runtime face cards, expression textures and the renderer's layer table; a change runs scripts/check_face_layers.py before landing.
FACE_LAYER_SOURCES = ('src/main/resources/assets/mcyukkuri/yukkuri/', 'src/main/resources/assets/mcyukkuri/textures/entity/',
                      'src/main/resources/assets/mcyukkuri/yukkuri_presentation/face_library.json',
                      'src/main/java/io/github/longshou/mcyukkuri/pose/Skin.java',
                      'scripts/check_face_layers.py', 'scripts/face_layer_allow.json')


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
        serial = MAIN / '.local/tools/javac_serial.py'  # one compile at a time machine-wide (a check peaks near 6 GB)
        command = [sys.executable, str(serial), str(tree)] if serial.is_file() else ['bash', 'scripts/javac_check.sh', str(tree)]
        if subprocess.run(command, cwd=tree).returncode:
            raise Fail('javac_check.sh failed')
    if any(path.startswith('scripts/museum_') for path in changed):
        # The launcher builds the playground from museum_scenarios.py; an import-time assertion there stops world generation.
        print('museum scenes changed: import check and pack check', flush=True)
        for check in (['-c', 'import museum_scenarios'], [str(tree / 'scripts/check_museum_pack.py')]):
            if subprocess.run([sys.executable, *check], cwd=tree / 'scripts').returncode:
                raise Fail('museum scene check failed: ' + ' '.join(check))
    if any(path.endswith('.java') or path.startswith('art/dialogue/') or path.endswith('dialogue/catalog.json') for path in changed):
        # A removed cue leaves its lines and voices behind; a new pool without a cue is never heard.
        print('java or dialogue changed: check_dialogue_live.py', flush=True)
        if subprocess.run([sys.executable, str(tree / 'scripts/check_dialogue_live.py'), '--check'], cwd=tree).returncode:
            raise Fail('check_dialogue_live.py found dialogue events no code can cue')
    docs = [path for path in changed if path.endswith('.md') or path in DOC_TRIGGERS]
    if docs:
        # Only the package's own documents: dead links elsewhere are someone else's package (--all checks the whole repo).
        print(f'docs changed: check_doc_refs.py on {len(docs)} file(s)', flush=True)
        if subprocess.run([sys.executable, str(tree / DOC_REFS), '--check', *docs], cwd=tree).returncode:
            raise Fail('check_doc_refs.py found dead references')
    if any(path.startswith(('src/main/resources/assets/mcyukkuri/yukkuri_props/', 'src/main/resources/assets/mcyukkuri/textures/props/',
                            'art/props/', 'standards/yukkuri-library/drafts/', 'standards/yukkuri-library/ecology/'))
           or path in ('scripts/check_prop_meshes.py', 'scripts/prop_mesh_allow.json') for path in changed):
        full_lint = any(path in ('scripts/check_prop_meshes.py', 'scripts/prop_mesh_allow.json') for path in changed)
        lint_args = ['--check', '--all'] if full_lint else ['--check', '--changed', 'origin/main']
        print(f'art/models changed: check_prop_meshes.py {" ".join(lint_args)}', flush=True)
        if subprocess.run([sys.executable, str(tree / 'scripts/check_prop_meshes.py'), *lint_args], cwd=tree).returncode:
            raise Fail('check_prop_meshes.py found prop mesh defects')
    if any(path.startswith(FACE_FIT_SOURCES) for path in changed):
        # Face timelines in clips and presentation rows: hold, switch rate and eye anchor (FACE-MOTION.md); the baseline holds old offenders.
        print('face or motion sources changed: check_face_fit.py --check', flush=True)
        if subprocess.run([sys.executable, str(tree / 'scripts/check_face_fit.py'), '--check'], cwd=tree).returncode:
            raise Fail('check_face_fit.py found face timelines above the baseline')
    if any(path.startswith(FACE_LAYER_SOURCES) for path in changed):
        # Expression textures, face geometry and the layer table: two face slots never draw ink on one depth.
        print('face textures or geometry changed: check_face_layers.py --check', flush=True)
        if subprocess.run([sys.executable, str(tree / 'scripts/check_face_layers.py'), '--check'], cwd=tree).returncode:
            raise Fail('check_face_layers.py found face slots drawing ink on one depth')
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


def archive_notes(name):
    """Move this package's brief and report (with their -review files) into the monthly archive, never overwriting."""
    if name.startswith('_'):
        return []
    month = time.strftime('%Y-%m')
    moved = []
    for folder in ('briefs', 'reports'):
        for suffix in ('', '-review'):
            path = MAIN / '.local/refactor' / folder / f'{name}{suffix}.md'
            if not path.is_file():
                continue
            target = MAIN / '.local/archive/refactor' / month / folder
            target.mkdir(parents=True, exist_ok=True)
            dest, serial = target / path.name, 2
            while dest.exists():
                dest = target / f'{path.stem}-{serial}{path.suffix}'
                serial += 1
            path.rename(dest)
            moved.append(dest)
    return moved


GIT_ENV = {**os.environ, 'GIT_EDITOR': 'true'}


def unmerged(tree):
    return git(tree, 'diff', '--name-only', '--diff-filter=U').splitlines()


def json_keys(tree, stage, path):
    """Top-level keys of a JSON object: an index stage of a conflicted file ('1' base, '2' ours, '3' theirs), or the
    working-tree file when stage is None. A missing side or a non-object file has none."""
    if stage is None:
        raw = (tree/path).read_bytes() if (tree/path).exists() else b''
    else:
        shown = subprocess.run(['git', 'show', f':{stage}:{path}'], cwd=tree, capture_output=True)
        raw = shown.stdout if shown.returncode == 0 else b''
    try:
        data = json.loads(raw.decode('utf-8-sig'))
    except ValueError:
        return set()
    return set(data) if isinstance(data, dict) else set()


def resolve_generated(tree, conflict, main_side):
    """Resolve a conflict whose files are all exporter-written: keep main's version, re-run the exporters that write
    them and stage the fresh output. A file the re-run does not rewrite, or one no exporter declares, stops instead."""
    try:
        owners = export.output_owners(conflict)
    except ValueError as error:
        raise Fail(f'{error}; a person resolves these files, then rerun land')
    print(f'generated conflict: {len(conflict)} file(s); keeping main\'s version and re-running '
          f'{", ".join(sorted(owners))}', flush=True)
    # Shared JSON maps (lang files, sounds.json) are partly hand-edited and exporters merge into them, so a key the branch
    # added by hand would vanish silently: remember the keys the branch added over the merge base and check them after.
    branch_stage = '3' if main_side == '--ours' else '2'
    added = {path: keys for path in conflict if path.endswith('.json')
             if (keys := json_keys(tree, branch_stage, path) - json_keys(tree, '1', path))}
    for path in conflict:
        git(tree, 'checkout', main_side, '--', path)
    main_bytes = {path: (tree/path).read_bytes() for path in conflict}
    # A family run always rebuilds the registry aggregates, so their paths are staged too.
    idents = list(dict.fromkeys([*owners, 'registries']))
    code = export.rerun(tree, idents)
    if code:
        raise Fail(f'export.py exited {code} while re-exporting {", ".join(sorted(owners))}')
    kept = [path for path in conflict if (tree/path).read_bytes() == main_bytes[path]]
    if kept:
        raise Fail('the re-export left main\'s version of ' + ', '.join(kept) + '; resolve by hand')
    lost = {path: sorted(keys - json_keys(tree, None, path)) for path, keys in added.items()}
    lost = {path: keys for path, keys in lost.items() if keys}
    if lost:
        raise Fail('the re-export dropped keys the branch added by hand: ' +
                   '; '.join(f'{path}: {", ".join(keys[:5])}' for path, keys in lost.items()) + '; resolve by hand')
    # Unstaged and untracked files are what the re-run wrote plus the conflicted files; stage them all, but stop on a
    # path the selected exporters do not declare (a hand-edited conflict or a missing OUTPUTS entry). The raw call
    # keeps the status columns of the first line, which git()'s strip would eat.
    status = subprocess.run(['git', 'status', '--porcelain=v1', '-z'], cwd=tree, text=True, encoding='utf-8',
                            capture_output=True, check=True).stdout.split('\0')
    written = [line[3:] for line in status if line and (line[0] == '?' or line[1] != ' ')]
    undeclared = [path for path in written if not export.declared(path, idents)]
    if undeclared:
        raise Fail('the re-export wrote files OUTPUTS does not declare: ' + ', '.join(undeclared) + '; resolve by hand')
    git(tree, 'add', '-A', '--', *conflict, *written)


def sync_rebase(tree):
    """Rebase onto origin/main, resolving exporter-written conflicts commit by commit; `--ours` is main's side. A
    conflict that needs a person aborts the rebase, so the branch is left as it was and a rerun starts clean."""
    done = subprocess.run(['git', 'rebase', '--empty=drop', 'origin/main'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
    while done.returncode:
        conflict = unmerged(tree)
        if not conflict:
            subprocess.run(['git', 'rebase', '--abort'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
            raise Fail(f'git rebase origin/main failed: {(done.stderr or done.stdout).strip()}')
        try:
            resolve_generated(tree, conflict, '--ours')
        except Fail:
            subprocess.run(['git', 'rebase', '--abort'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
            raise
        done = subprocess.run(['git', 'rebase', '--continue'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)


def sync_merge(tree):
    """Merge origin/main into a branch built from merges, resolving exporter-written conflicts; `--theirs` is main's side.
    A conflict that needs a person aborts the merge, so a rerun starts clean."""
    done = subprocess.run(['git', 'merge', '--no-edit', 'origin/main'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
    while done.returncode:
        conflict = unmerged(tree)
        if not conflict:
            subprocess.run(['git', 'merge', '--abort'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
            raise Fail(f'git merge origin/main failed: {(done.stderr or done.stdout).strip()}')
        try:
            resolve_generated(tree, conflict, '--theirs')
        except Fail:
            subprocess.run(['git', 'merge', '--abort'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)
            raise
        done = subprocess.run(['git', 'merge', '--continue'], cwd=tree, env=GIT_ENV, text=True, encoding='utf-8', capture_output=True)


def sync_main(tree):
    """Bring the landing onto origin/main. A branch that already contains it is left alone; a branch built from merges (art
    branches merged into a wiring branch) merges origin/main instead of rebasing, because a rebase would replay each art commit
    and conflict on generated exports; any other branch rebases as before. A conflict on exporter-written files is resolved
    from main's side plus a re-run of their exporters; a hand-edited conflict stops with the files named."""
    if not subprocess.run(['git', 'merge-base', '--is-ancestor', 'origin/main', 'HEAD'], cwd=tree).returncode:
        return
    if git(tree, 'rev-list', '--merges', 'origin/main..HEAD'):
        sync_merge(tree)
    else:
        sync_rebase(tree)


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
        sync_main(tree)
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
        sync_main(tree)
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
    notes = archive_notes(name)
    if notes:
        print(f'archived {len(notes)} package note(s) to {notes[0].parent.parent.relative_to(MAIN)}')
    print(f'landed {name}: pushed {pushed}')
    print(f'shed the large files of {smoke_prune.prune(quick=True)} old uncited smoke/capture runs')


def find_run(sha):
    out = subprocess.check_output(['gh', 'run', 'list', '--workflow', 'build.yml', '--commit', sha,
                                   '--json', 'databaseId,status,conclusion,headSha', '-L', '5'], cwd=MAIN, text=True, encoding='utf-8')
    runs = json.loads(out)
    return runs[0] if runs else None


def finish_run(run):
    if run['conclusion'] not in ('success', 'cancelled'):
        log = subprocess.run(['gh', 'run', 'view', str(run['databaseId']), '--log-failed'], cwd=MAIN, text=True,
                             encoding='utf-8', capture_output=True).stdout.splitlines()
        print('\n'.join(log[-60:]))
        raise Fail(f'Build run {run["databaseId"]} for {run["headSha"]} ended {run["conclusion"]}')
    return run


def wait_build(sha, args):
    """Wait for a Build run of sha itself: the target is the tip of a push that changed a Build path."""
    print(f'waiting for Build run of {sha[:9]}', flush=True)
    appear_deadline = time.monotonic() + args.appear_timeout
    while True:
        run = find_run(sha)
        if run is None and time.monotonic() > appear_deadline:
            raise Fail(f'no Build run for {sha} appeared within {args.appear_timeout}s')
        if run and run['status'] == 'completed':
            return finish_run(run)
        time.sleep(args.poll)


def wait_run(run, args):
    """Wait for a Build run found for an earlier push tip, whose commit covers the target on every Build path."""
    print(f'waiting for Build run {run["databaseId"]} of {run["headSha"][:9]}', flush=True)
    while run['status'] != 'completed':
        time.sleep(args.poll)
        run = json.loads(subprocess.check_output(
            ['gh', 'run', 'view', str(run['databaseId']), '--json', 'databaseId,status,conclusion,headSha'],
            cwd=MAIN, text=True, encoding='utf-8'))
    return finish_run(run)


def cmd_runtime(args):
    git(MAIN, 'fetch', '-q', 'origin')
    while True:
        sha = git(MAIN, 'rev-parse', args.commit or 'origin/main')
        covering = installer.build_run(MAIN, sha)
        if covering is None:
            run = wait_build(sha, args)
        else:
            if covering['headSha'] != sha:
                print(f'{sha[:9]} changes no Build path; its Build is run {covering["databaseId"]} of '
                      f'{covering["headSha"][:9]}', flush=True)
            run = wait_run(covering, args)
        if run['conclusion'] == 'cancelled':
            # A newer push cancels the older Build of the same branch; following main means following that push.
            git(MAIN, 'fetch', '-q', 'origin')
            moved = git(MAIN, 'rev-parse', 'origin/main')
            if args.commit or moved == sha:
                raise Fail(f'Build run {run["databaseId"]} for {run["headSha"]} ended cancelled')
            print(f'Build run {run["databaseId"]} for {run["headSha"][:9]} was cancelled; following origin/main '
                  f'{moved[:9]}', flush=True)
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
        print(f'dry run: would ff main to {sha} and run update_museum_runtime.py --run {run["databaseId"]} '
              f'of {run["headSha"][:9]}')
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

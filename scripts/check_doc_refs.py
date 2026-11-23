#!/usr/bin/env python3
"""Read-only dead-reference checks for the docs. Python 3.10+, standard library only.

Scans the markdown files listed below (all tracked by Git, never `.local/`): relative
links and their heading anchors, backticked Java identifiers, repo paths, scene numbers
and the playtest watch list. Findings print as `path:line: reason`; `--check` exits 1.

Java identifiers resolve by file name or occurrence under `src/main/java` or
`src/test/java` (nested types and fields). Only the shapes `Class`, `Class.member` and
`pkg/Class` are checked; lowercase calls and prose are left alone. Scene ids in prose
are indistinguishable from clip and dialogue ids, so only the unambiguous scene numbers
are checked against `scripts/museum_scene_numbers.json`.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import playtest_list  # noqa: E402  (same directory; the item-label rule stays in one place)
DOCS = ('docs/FEATURES.md', 'docs/TODO.md', 'docs/roles/*.md', 'AGENTS.md', 'scripts/README.md',
        'art/CATALOG.md', 'wiki/**/*.md')
GENERATED = 'wiki/reference-library/'
SCENE_DOCS = {'docs/FEATURES.md', 'docs/TODO.md'}
SCENE_NUMBERS = 'scripts/museum_scene_numbers.json'
WATCH = 'scripts/playtest_watch.json'
JAVA_ROOTS = ('src/main/java/', 'src/test/java/')
LINK = re.compile(r'!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
TOKEN = re.compile(r'`([^`\n]+)`')
SCENE_NUMBER = re.compile(r'(?<![A-Za-z0-9-])[A-Z]{2}-\d{2}(?![0-9])')
FENCE = re.compile(r'^\s*(```|~~~)')
ATX = re.compile(r'^(#{1,6})\s+(.*?)\s*#*\s*$')
HTML_ANCHOR = re.compile(r'<(?:a|span)\s+(?:id|name)=["\']([^"\']+)["\']')
CLASS = r'[A-Z][A-Za-z0-9_]*'
DOTTED = re.compile(rf'({CLASS})\.([A-Za-z_][A-Za-z0-9_]*)')
PACKAGED = re.compile(rf'([a-z][a-z0-9_]*(?:/[a-z][a-z0-9_]*)*)/({CLASS})')
URL = re.compile(r'^[A-Za-z][A-Za-z0-9+.-]*:')


def git_ls(*patterns):
    done = subprocess.run(['git', 'ls-files', '-z', '--', *patterns], cwd=ROOT, capture_output=True)
    if done.returncode:
        raise SystemExit(done.stderr.decode('utf-8', 'replace').strip())
    return [path for path in done.stdout.decode('utf-8').split('\0') if path]


def slug(heading):
    """GitHub heading slug: links/emphasis stripped, lowercase, punctuation dropped, spaces hyphenated."""
    text = re.sub(r'<[^>]*>', '', heading)
    text = re.sub(r'!?\[([^\]]*)\]\([^)]*\)', r'\1', text)
    text = text.replace('`', '')
    text = re.sub(r'[*~]', '', text).lower()
    return re.sub(r'[^\w\- ]', '', text, flags=re.UNICODE).replace(' ', '-')


class Checks:
    def __init__(self):
        self.tracked = git_ls()
        self.tracked_set = set(self.tracked)
        self.dirs = set()
        for path in self.tracked:
            parts = path.split('/')
            for depth in range(1, len(parts)):
                self.dirs.add('/'.join(parts[:depth]))
        self.roots = {path.split('/')[0] for path in self.tracked}
        self.basenames = {path.rsplit('/', 1)[-1] for path in self.tracked}
        self.java = [path for path in self.tracked
                     if path.endswith('.java') and path.startswith(JAVA_ROOTS)]
        self.java_by_stem = {}
        for path in self.java:
            self.java_by_stem.setdefault(Path(path).stem, []).append(path)
        self._text = {}
        self._words = None
        self._anchors = {}
        self.problems = []

    def text(self, path):
        if path not in self._text:
            self._text[path] = (ROOT / path).read_text(encoding='utf-8')
        return self._text[path]

    def problem(self, path, line, reason):
        self.problems.append((path, line, reason))

    # --- markdown links and anchors -------------------------------------------------

    def anchors(self, path):
        if path not in self._anchors:
            found, seen, fenced = set(), {}, False
            for line in self.text(path).splitlines():
                if FENCE.match(line):
                    fenced = not fenced
                    continue
                if fenced:
                    continue
                found.update(HTML_ANCHOR.findall(line))
                heading = ATX.match(line)
                if heading:
                    base = slug(heading.group(2))
                    count = seen.get(base, 0)
                    seen[base] = count + 1
                    found.add(base if count == 0 else f'{base}-{count}')
            self._anchors[path] = {name.lower() for name in found}
        return self._anchors[path]

    def check_links(self, path):
        for number, line in enumerate(self.lines(path), 1):
            for match in LINK.finditer(line):
                target = match.group(1)
                if URL.match(target) or target.startswith('//'):
                    continue
                raw, _, anchor = target.partition('#')
                anchor = urllib.parse.unquote(anchor).lower()
                if not raw:
                    dest = path
                else:
                    resolved = ((ROOT / path).parent / urllib.parse.unquote(raw)).resolve()
                    try:
                        dest = resolved.relative_to(ROOT).as_posix().rstrip('/')
                    except ValueError:
                        self.problem(path, number, f'link target outside the repository: {target}')
                        continue
                    if dest not in self.tracked_set and dest not in self.dirs:
                        self.problem(path, number, f'link target does not exist: {target}')
                        continue
                if anchor and dest.endswith('.md') and anchor not in self.anchors(dest):
                    self.problem(path, number, f'anchor does not exist in {dest}: #{anchor}')

    # --- backticked identifiers, paths, scene numbers --------------------------------

    def check_tokens(self, path):
        for number, line in enumerate(self.lines(path), 1):
            for match in TOKEN.finditer(line):
                token = match.group(1).strip()
                if not token:
                    continue
                status = self.repo_path_status(token)
                if status == 'dead':
                    self.problem(path, number, f'repo path does not exist: {token}')
                elif status == 'no':
                    self.check_java(path, number, token)

    def repo_path_status(self, token):
        """'ok' for an existing repo path, 'dead' for a missing one, 'no' when it is not a repo path."""
        if any(char in token for char in ' \t<>{}"\'\\') or token.startswith('/'):
            return 'no'
        if token.split('/')[0] not in self.roots:
            return 'no'
        clean = token.rstrip('/')
        if '*' in clean or '?' in clean:
            pattern = re.compile(fnmatch.translate(clean))
            return 'ok' if any(pattern.match(candidate) for candidate in self.tracked_set | self.dirs) else 'dead'
        return 'ok' if clean in self.tracked_set or clean in self.dirs else 'dead'

    def check_java(self, path, number, token):
        if token in self.basenames:
            return
        dotted = DOTTED.fullmatch(token)
        if dotted:
            self.require_member(path, number, *dotted.groups())
            return
        packaged = PACKAGED.fullmatch(token)
        if packaged:
            package, name = packaged.groups()
            if not any(candidate.endswith(f'/{package}/{name}.java') for candidate in self.java):
                self.problem(path, number, f'no Java class {package}/{name}')
            return
        if not re.fullmatch(CLASS, token) or not re.search(r'[a-z]', token):
            return
        if token in self.java_by_stem or token in self.java_words():
            return
        self.problem(path, number, f'no Java class or identifier {token}')

    def require_member(self, path, number, name, member):
        files = self.java_by_stem.get(name)
        if files:
            if not any(member in self.text(candidate) for candidate in files):
                self.problem(path, number, f'{name} has no member {member}')
        elif name not in self.java_words():
            self.problem(path, number, f'no Java class or identifier {name}')
        elif member not in self.java_words():
            # Nested types and NBT paths (`YukkuriMind.Feeling`) span files; both words must exist.
            self.problem(path, number, f'no Java identifier {member} for {name}')

    def java_words(self):
        """Identifier index over all Java sources, built once when a bare name misses its file."""
        if self._words is None:
            self._words = set()
            for path in self.java:
                self._words.update(re.findall(r'[A-Za-z_$][A-Za-z0-9_$]*', self.text(path)))
        return self._words

    # --- registries ------------------------------------------------------------------

    def check_registry(self, targets):
        numbers = json.loads(self.text(SCENE_NUMBERS))['numbers']
        known = set(numbers.values())
        for path in sorted(SCENE_DOCS & set(targets)):
            for number, line in enumerate(self.lines(path), 1):
                for found in SCENE_NUMBER.findall(line):
                    if found not in known:
                        self.problem(path, number, f'scene number {found} is not in {SCENE_NUMBERS}')
        watch_text = self.text(WATCH)
        labels = {playtest_list.item_label(item) for item in playtest_list.todo_items(self.text('docs/TODO.md'))}
        for key in json.loads(watch_text):
            if key not in numbers and key not in labels:
                line = next((n for n, text in enumerate(watch_text.splitlines(), 1) if f'"{key}"' in text), 1)
                self.problem(WATCH, line, f'watch key {key} is not a scene id in {SCENE_NUMBERS} nor a TODO item label')

    # --- driver ----------------------------------------------------------------------

    def lines(self, path):
        fenced, out = False, []
        for line in self.text(path).splitlines():
            if FENCE.match(line):
                fenced = not fenced
                out.append('')
                continue
            out.append('' if fenced else line)
        return out

    def run(self, targets):
        for path in targets:
            self.check_links(path)
            self.check_tokens(path)
        self.check_registry(targets)
        for path, number, reason in sorted(set(self.problems)):
            print(f'{path}:{number}: {reason}')
        return 1 if self.problems else 0


def main():
    parser = argparse.ArgumentParser(description='Check markdown links, anchors, Java identifiers, paths and scenes.')
    parser.add_argument('paths', nargs='*', help='tracked markdown/registry files to check; default is the whole doc set')
    parser.add_argument('--all', action='store_true', help='check the whole doc set even when paths are given')
    parser.add_argument('--check', action='store_true', help='exit 1 when a reference is dead')
    args = parser.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    checks = Checks()
    if args.paths and not args.all:
        targets = [path for path in dict.fromkeys(args.paths)
                   if path in checks.tracked_set and (path.endswith('.md') or path in (SCENE_NUMBERS, WATCH))]
    else:
        targets = [path for path in git_ls(*DOCS) if not path.startswith(GENERATED)]
    code = checks.run(targets)
    return code if args.check else 0


if __name__ == '__main__':
    sys.exit(main())

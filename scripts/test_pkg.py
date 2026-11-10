"""pkg.py landing: a lost push race rebases and retries, and a rerun continues the landing. Local repos only."""
import argparse
import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pkg

TABLE = '## 进行中\n\n| 条目 | 工作树 | 文件 |\n| --- | --- | --- |\n| base | `.worktrees/base` | a.py |\n'
ROW = '| test | `.worktrees/wp-test` | scripts/pkg.py |\n'
LANDED = '## 进行中\n\n| 条目 | 工作树 | 文件 |\n| --- | --- | --- |\n| base | `.worktrees/base` | a.py |\n'


def run(cwd, *args):
    return subprocess.run(['git', *args], cwd=cwd, check=True, text=True, encoding='utf-8', capture_output=True).stdout.strip()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8', newline='\n')


def commit(path, message, name, text):
    write(Path(path)/name, text)
    run(path, 'add', name)
    run(path, 'commit', '-q', '-m', message)


class LandingTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1]/'.local/tmp')
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.remote = self.root/'remote.git'
        run(self.root, 'init', '--bare', '-b', 'main', 'remote.git')
        self.main = self.clone('main')
        commit(self.main, 'base', 'docs/TODO.md', TABLE+ROW)
        run(self.main, 'push', '-q', 'origin', 'HEAD:main')

    def clone(self, name):
        path = self.root/name
        run(self.root, 'clone', '-q', str(self.remote), str(path))
        run(path, 'config', 'user.name', 'pkg test')
        run(path, 'config', 'user.email', 'pkg@example.invalid')
        return path

    def test_lost_push_race_rebases_the_landing_commits_and_retries(self):
        tree = self.clone('wt')
        commit(tree, 'package work', 'work.txt', 'work')
        commit(tree, 'TODO: wp-test landed', 'docs/TODO.md', LANDED)
        other = self.clone('other')
        commit(other, 'other package landed', 'other.txt', 'other')
        run(other, 'push', '-q', 'origin', 'HEAD:main')  # lands while our push is in flight
        pushed = pkg.push_landing(tree, 'wp-test')
        self.assertEqual(pushed, run(tree, 'rev-parse', 'HEAD'))
        self.assertEqual(run(self.remote, 'rev-parse', 'main'), pushed)
        self.assertEqual(run(self.remote, 'log', '--format=%s', 'main').splitlines(),
                         ['TODO: wp-test landed', 'package work', 'other package landed', 'base'])
        self.assertNotIn('wp-test', run(self.remote, 'show', 'main:docs/TODO.md'))
        self.assertEqual(run(self.remote, 'show', 'main:other.txt'), 'other')

    def test_rerun_continues_from_the_existing_landing_commit(self):
        run(self.main, 'fetch', '-q', 'origin')
        run(self.main, 'worktree', 'add', '-b', 'wp-test', str(self.main/'.worktrees/wp-test'), 'origin/main')
        tree = self.main/'.worktrees/wp-test'
        commit(tree, 'package work', 'work.txt', 'work')
        commit(tree, 'TODO: wp-test landed', 'docs/TODO.md', LANDED)
        self.assertTrue(pkg.row_removed_in_head(tree, 'wp-test'))
        self.assertFalse(pkg.row_removed_in_head(tree, 'wp-other'))
        with patch.object(pkg, 'MAIN', self.main), contextlib.redirect_stdout(io.StringIO()) as output:
            pkg.cmd_land(argparse.Namespace(name='wp-test', scene=[], dry_run=True))
        self.assertIn('row already removed', output.getvalue())
        self.assertIn('dry run: wp-test would land at', output.getvalue())


if __name__ == '__main__':
    unittest.main(verbosity=2)

"""Disposable content-addressed motion compilation stages (never runtime authority)."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def fingerprint(value):
    return hashlib.sha256(encoded(value)).hexdigest()


class MotionCache:
    def __init__(self, directory, scope, full=False):
        self.directory = Path(directory)
        self.scope = fingerprint(scope)
        self.full = full
        self.hits = self.misses = self.write_failures = 0

    def key(self, stage, name, inputs):
        return fingerprint([self.scope, stage, name, inputs])

    def get(self, key):
        path = self.directory/key[:2]/(key+'.json')
        if not self.full and path.exists():
            # Cache files are an external boundary: torn, stale or edited entries
            # must never become trusted compiler results.
            try:
                entry = json.loads(path.read_bytes())
                if entry['input'] == key and entry['sha256'] == fingerprint(entry['value']):
                    self.hits += 1
                    return entry['value']
            except (OSError, ValueError, KeyError, TypeError):
                # A file locked by antivirus is a miss, never a build failure.
                pass
        self.misses += 1
        return None

    def put(self, key, value):
        path = self.directory/key[:2]/(key+'.json')
        path.parent.mkdir(parents=True, exist_ok=True)
        data = encoded(dict(input=key, sha256=fingerprint(value), value=value))
        # Concurrent exporters can publish the same content without partial reads.
        fd, temporary = tempfile.mkstemp(dir=path.parent, suffix='.tmp')
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
            # Antivirus briefly locks fresh files on some machines. Caching is an
            # optimisation: retry briefly, then skip the entry instead of failing.
            for delay in (0.05, 0.1, 0.2, None):
                try:
                    os.replace(temporary, path)
                    break
                except OSError:
                    if delay is None:
                        self.write_failures += 1
                    else:
                        time.sleep(delay)
        finally:
            # A temp file the locks still hold stays behind; it is never read as an entry.
            try:
                Path(temporary).unlink(missing_ok=True)
            except OSError:
                pass

    def memo(self, stage, name, inputs, build):
        key = self.key(stage, name, inputs)
        value = self.get(key)
        if value is None:
            value = build()
            self.put(key, value)
        return value


def lore_inputs(cfg, name):
    """Recipe dependencies read by base inheritance, mirrors and aquatic stowing."""
    dependencies = {}
    def visit(ident):
        if ident in dependencies:
            return
        clip = cfg['clips'][ident]
        dependencies[ident] = clip
        if 'base' in clip:
            visit(clip['base'])
        if 'mirror' in clip:
            visit(clip['mirror']['of'])
        for variant in clip.get('variants', {}).values():
            if 'mirror' in variant:
                visit(variant['mirror']['of'])
    visit(name)
    # aquatic_tools.HOLD is used by stow_tracks; these are also the two
    # starting poses replayed by stow_gate. Track their recipes transitively.
    if any('hat' in c for c in dependencies.values()):
        visit('braid_item_hold')
        visit('boat_paddle')
    if name in ('braid_item_hold', 'boat_paddle'):
        visit('braid_item_hold')
    # These fields describe publication, not the sampled motion. In particular,
    # motion packages bump runtime_revision (and sometimes revision) alongside
    # their one-clip edits; neither is read by the compiler.
    metadata = ('clips', 'export_families', 'version', 'runtime_revision', 'revision', 'status', 'note')
    return [{k: v for k, v in cfg.items() if k not in metadata}, dependencies]

"""Per-entry registry sources; compatible JSON aggregates remain available to all readers."""
import hashlib
import json
from pathlib import Path
import re
import subprocess


def shared_checkout(root):
    """Main checkout owns machine-local tools and untracked original references."""
    return Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                                       cwd=root, text=True).strip()).parent


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def save(path, value):
    path = Path(path)
    # Serialize before touching the current file; atomically replace complete bytes.
    encoded = (json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode('utf-8')
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_bytes(encoded)
    temporary.replace(path)


def ident(value):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', value):
        raise ValueError('Invalid registry ID: '+value)
    return value


def import_registries(root, before=None):
    """Bootstrap once, or capture only changes made by a managed legacy exporter."""
    base = root/'art/registry'
    if before is None and base.exists():
        raise ValueError('Registry shards already exist; edit shards and run --registries. Aggregates are generated.')
    for name in ('runtime-sources', 'catalog'):
        path = root/'art'/(name+'.json')
        current = load(path)
        previous = json.loads(before['art/'+name+'.json']) if before else None
        if name == 'runtime-sources':
            for key, value in current.items():
                if key == 'models':
                    prior_models = previous.get('models', {}) if previous else {}
                    for model, record in value.items():
                        if previous is None or prior_models.get(model) != record:
                            save(base/'runtime/models'/f'{ident(model)}.json', record)
                    for model in prior_models.keys()-value.keys():
                        (base/'runtime/models'/f'{ident(model)}.json').unlink()
                    legacy = base/'runtime/models.json'
                    if legacy.exists():
                        legacy.unlink()
                    continue
                if previous is None or previous.get(key) != value:
                    save(base/'runtime'/f'{ident(key)}.json', value)
            if previous:
                for key in previous.keys()-current.keys():
                    (base/'runtime'/f'{ident(key)}.json').unlink()
        else:
            old = {c['id']: c for c in previous['categories']} if previous else {}
            for order, category in enumerate(current['categories']):
                folder = base/'catalog'/ident(category['id'])
                header = {k: v for k, v in category.items() if k != 'families'}
                prior = old.get(category['id'], {})
                if previous is None or header != {k: v for k, v in prior.items() if k != 'families'}:
                    save(folder/'category.json', dict(header, _order=order))
                families = {f['id']: f for f in prior.get('families', [])}
                for family_order, family in enumerate(category['families']):
                    if previous is None or families.get(family['id']) != family:
                        save(folder/'families'/f'{ident(family["id"])}.json', dict(family, _order=family_order))
                for key in families.keys()-{f['id'] for f in category['families']}:
                    (folder/'families'/f'{ident(key)}.json').unlink()
            for key in old.keys()-{c['id'] for c in current['categories']}:
                folder = base/'catalog'/ident(key)
                for path in (folder/'families').glob('*.json'):
                    path.unlink()
                (folder/'category.json').unlink()


def assembled(root):
    base = root/'art/registry'
    if not (base/'runtime').is_dir() or not (base/'catalog').is_dir():
        raise ValueError('Registry shards missing; first run export.py --import-registries')
    runtime = {p.stem: load(p) for p in sorted((base/'runtime').glob('*.json'))}
    models = sorted((base/'runtime/models').glob('*.json'))
    if models:
        runtime['models'] = {p.stem: load(p) for p in models}
        runtime = dict(sorted(runtime.items()))
    categories = []
    for path in sorted((base/'catalog').glob('*/category.json')):
        category = load(path)
        families = [load(p) for p in sorted((path.parent/'families').glob('*.json'))]
        families.sort(key=lambda f: (f.get('_order', 1000000), f['id']))
        category['families'] = [{k: v for k, v in f.items() if k != '_order'} for f in families]
        categories.append(category)
    categories.sort(key=lambda c: (c.get('_order', 1000000), c['id']))
    categories = [{k: v for k, v in c.items() if k != '_order'} for c in categories]
    return {'runtime-sources': runtime, 'catalog': {'categories': categories}}


def registries(root, check=False):
    for name, value in assembled(root).items():
        path = root/'art'/(name+'.json')
        if check:
            if load(path) != value:
                raise ValueError('Stale registry aggregate: '+str(path))
        else:
            save(path, value)


def prepare_export(root):
    """Legacy exporters read a freshly generated aggregate, including deletions."""
    registries(root)
    return {p: (root/p).read_bytes() for p in ('art/runtime-sources.json', 'art/catalog.json')}


def finish_export(root, before):
    """Capture exporter-produced metadata, then discard its aggregate formatting."""
    import_registries(root, before)
    registries(root)


def register(root, entry, dry_run=False):
    """Register independent sources only. Aggregates are rebuilt after merge/export.

    {"runtime": {"stable_id": {...}}, "catalog": [{"category": "motions",
      "family": {"id": "stable-id", "prefixes": [...]}}],
     "approval": {"id": "stable-id", "decision": "用户认可的内容", "files": [...]}}
    """
    if not entry or set(entry)-{'runtime', 'catalog', 'approval'}:
        raise ValueError('Entry must contain runtime, catalog and/or approval')
    base = root/'art/registry'
    writes = []
    for key, value in entry.get('runtime', {}).items():
        if key == 'models':
            writes.extend((base/'runtime/models'/f'{ident(model)}.json', record) for model, record in value.items())
            continue
        writes.append((base/'runtime'/f'{ident(key)}.json', value))
    for item in entry.get('catalog', []):
        folder = base/'catalog'/ident(item['category'])
        if not (folder/'category.json').exists():
            header = item['category_data']
            if header['id'] != item['category']:
                raise ValueError('Category ID mismatch')
            writes.append((folder/'category.json', header))
        family = item['family']
        path = folder/'families'/f'{ident(family["id"])}.json'
        if path.exists() and '_order' in load(path) and '_order' not in family:
            family = dict(family, _order=load(path)['_order'])
        writes.append((path, family))
    if 'approval' in entry:
        approval = entry['approval']
        if not approval['decision'].strip() or not approval['files']:
            raise ValueError('Approval needs decision text and actual source files')
        hashes = {}
        for name in approval['files']:
            source = (root/name).resolve()
            source.relative_to(root.resolve())
            hashes[source.relative_to(root.resolve()).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
        writes.append((root/'standards/yukkuri-library/provenance/selections'/f'{ident(approval["id"])}.json',
                       dict(status='accepted', decision=approval['decision'], files=hashes)))
    # Validate every input before touching any current entry.
    if not writes:
        raise ValueError('Entry contains no registrations')
    for path, value in writes:
        json.dumps(value, ensure_ascii=False)
    if dry_run:
        print(json.dumps([dict(path=path.relative_to(root).as_posix(), value=value)
                          for path, value in writes], ensure_ascii=False, indent=2))
        return
    for path, value in writes:
        save(path, value)
    print(f'Registered {len(writes)} source entries; export.py --registries rebuilds compatible aggregates.')

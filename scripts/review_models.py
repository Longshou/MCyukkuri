"""Resolve packaged review models in the game's splice order."""
from functools import lru_cache
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
REVISIONS = ('motion_corrections', 'house_ecology', 'toilet_contact', 'face_library')


def revisions(name):
    path = ASSETS/'yukkuri_presentation'/f'{name}.json'
    return read_revisions(path, path.stat().st_mtime_ns)


@lru_cache(maxsize=len(REVISIONS) + 1)
def read_revisions(path, modified):
    return json.loads(path.read_text(encoding='utf-8'))['models']


def compatible_revisions(ident, model):
    bones = [bone['name'] for bone in model['bones']]
    return [revision for name in REVISIONS if (revision := revisions(name).get(ident))
            and revision['bone_names'] == bones]


def runtime_inputs(ident):
    raw = (ASSETS/'yukkuri'/f'{ident}.json').read_bytes()
    model = json.loads(raw)
    fit = revisions('placement_fit').get(ident)
    if fit:
        lf = raw.replace(b'\r\n', b'\n')
        hashes = {hashlib.sha256(value).hexdigest() for value in (raw, lf, lf.replace(b'\n', b'\r\n'))}
        if fit['base_sha256'] not in hashes:
            fit = None
    return model, fit, compatible_revisions(ident, model)


def register_runtime(ident):
    from pose_client import request

    model, fit, chain = runtime_inputs(ident)
    # The pose core owns mesh and clip splicing. Release intermediate rigs as soon as they're spliced.
    for index, revision in enumerate(chain or [None]):
        result = request(dict(op='register', model=model, fit=fit, revision=revision))
        if index < len(chain) - 1:
            request(dict(op='release', id=result['id']))
        model, fit = result['model'], None
    return result

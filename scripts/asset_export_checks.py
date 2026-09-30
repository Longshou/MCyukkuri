"""Read-only, newline-compatible checks for deterministic asset exports.

Only JSON text line endings may vary between checkouts. Fingerprints keep their
recorded representation only when its bytes match the same LF/CRLF content.
PNG data, JSON values, whitespace and ordering remain exact.
"""
import hashlib
import json

from runtime_model_fingerprint import matches_model_fingerprint


def provenance(path):
    return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}


def fingerprint(path, data, previous=None):
    if previous and (hashlib.sha256(data).hexdigest() == previous or
                     (path.suffix == '.json' and matches_model_fingerprint(data, previous))):
        return previous
    return hashlib.sha256(data).hexdigest()


def fingerprints(root, files, previous):
    return {path.relative_to(root).as_posix(): fingerprint(
        path, data, previous.get(path.relative_to(root).as_posix()))
        for path, data in files.items()}


def check_output(path, expected):
    if not path.is_file():
        return False
    actual = path.read_bytes()
    if path.suffix == '.json':
        return actual.replace(b'\r\n', b'\n') == expected.replace(b'\r\n', b'\n')
    return actual == expected

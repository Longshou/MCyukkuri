"""Read-only, newline-compatible checks for deterministic asset exports.

Plain JSON exports allow only LF/CRLF differences; their values, whitespace and
ordering remain exact.  ``.bbmodel`` exports are parsed because they embed
encoder-controlled PNG data and floating-point geometry: the comparison ignores
only equivalent PNG encodings and a coordinate's 1e-15 final-digit drift.
Generated PNGs may explicitly retain an existing byte representation only after
their decoded mode, size and RGBA pixels have been compared.
"""
import base64
import hashlib
import io
import json
import math

from runtime_model_fingerprint import matches_model_fingerprint


def provenance(path):
    return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}


def _same_png_pixels(actual, expected):
    """Return whether two PNG encodings represent the same selected image."""
    try:
        from PIL import Image
        with Image.open(io.BytesIO(actual)) as before, Image.open(io.BytesIO(expected)) as after:
            return (before.format == after.format == 'PNG' and before.mode == after.mode and before.size == after.size and
                    before.convert('RGBA').tobytes() == after.convert('RGBA').tobytes())
    except (OSError, ValueError):
        return False


def preserve_equivalent_png(path, generated):
    """Keep a selected PNG's existing encoding only when its pixels are unchanged."""
    if not path.is_file():
        return generated
    existing = path.read_bytes()
    return existing if _same_png_pixels(existing, generated) else generated


def _matches_negative_zero_json(actual, expected):
    if type(actual) is not type(expected):
        return False
    if isinstance(actual, dict):
        return list(actual) == list(expected) and all(
            _matches_negative_zero_json(actual[key], expected[key]) for key in actual)
    if isinstance(actual, list):
        return len(actual) == len(expected) and all(
            _matches_negative_zero_json(before, after) for before, after in zip(actual, expected))
    if isinstance(actual, float):
        return actual == expected
    return actual == expected


def preserve_negative_zero_mesh_json(path, generated):
    """Retain a mesh JSON encoding when its sole byte drift is ``-0.0`` sign."""
    if not path.is_file():
        return generated
    existing = path.read_bytes()
    if (existing.replace(b'\r\n', b'\n').replace(b'-0.0', b'0.0') !=
            generated.replace(b'\r\n', b'\n').replace(b'-0.0', b'0.0')):
        return generated
    try:
        return existing if _matches_negative_zero_json(json.loads(existing), json.loads(generated)) else generated
    except (TypeError, ValueError, UnicodeDecodeError):
        return generated


def _png_data_uri_matches(actual, expected):
    prefix = 'data:image/png;base64,'
    if not (actual.startswith(prefix) and expected.startswith(prefix)):
        return False
    try:
        return _same_png_pixels(base64.b64decode(actual[len(prefix):]),
                                base64.b64decode(expected[len(prefix):]))
    except (ValueError, UnicodeError):
        return False


def _matches_bbmodel_value(actual, expected, coordinate=False):
    if type(actual) is not type(expected):
        return False
    if isinstance(actual, dict):
        if list(actual) != list(expected):
            return False
        return all(
            _matches_bbmodel_value(
                actual[key], expected[key],
                coordinate=(coordinate or
                            (key == 'vertices' and isinstance(actual[key], dict))))
            for key in actual)
    if isinstance(actual, list):
        return len(actual) == len(expected) and all(
            _matches_bbmodel_value(before, after, coordinate=coordinate)
            for before, after in zip(actual, expected))
    if isinstance(actual, str) and actual != expected:
        return _png_data_uri_matches(actual, expected)
    if coordinate and isinstance(actual, float):
        return math.isfinite(actual) and math.isfinite(expected) and abs(actual - expected) <= 1e-15
    return actual == expected


def matches_bbmodel_export(actual, expected):
    """Allow only encoder-equivalent texture data and coordinate tail noise."""
    try:
        return _matches_bbmodel_value(json.loads(actual), json.loads(expected))
    except (TypeError, ValueError, UnicodeDecodeError):
        return False


def fingerprint(path, data, previous=None):
    if previous and (hashlib.sha256(data).hexdigest() == previous or
                     (path.suffix in ('.json', '.py') and matches_model_fingerprint(data, previous)) or
                     (path.suffix == '.bbmodel' and path.is_file() and
                      matches_model_fingerprint(path.read_bytes(), previous) and
                      matches_bbmodel_export(path.read_bytes(), data))):
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
    if path.suffix == '.bbmodel':
        return matches_bbmodel_export(actual, expected)
    return actual == expected

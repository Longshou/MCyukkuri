#!/usr/bin/env python
"""Negative and positive fixture tests for scripts/check_prop_meshes.py."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import check_prop_meshes as cpm  # noqa: E402


def _make_quad(p0, p1, p2, p3, uvs, normal):
    verts = []
    for i0, i1, i2 in ((0, 1, 2), (0, 2, 3)):
        for idx in (i0, i1, i2):
            p = (p0, p1, p2, p3)[idx]
            u, v = uvs[idx]
            verts.extend([p[0], p[1], p[2], u, v, normal[0], normal[1], normal[2]])
    return verts


def _write_fixture(root, model_id, verts, img, placed=True, part_name='main'):
    assets = root / cpm.ASSETS_REL
    (assets / 'yukkuri_props').mkdir(parents=True, exist_ok=True)
    (assets / 'textures/props').mkdir(parents=True, exist_ok=True)
    if placed:
        (assets / 'blockstates').mkdir(parents=True, exist_ok=True)
        (assets / 'blockstates' / f'{model_id}.json').write_text('{"variants":{"":{}}}\n', encoding='utf-8')
    xs = verts[0::8]
    ys = verts[1::8]
    zs = verts[2::8]
    mesh = {
        'format': 1,
        'units_per_block': 16,
        'id': model_id,
        'texture_size': [img.width, img.height],
        'vertices': verts,
        'parts': [{'name': part_name, 'start': 0, 'count': len(verts) // 8, 'unit': 0}],
        'bounds': [[min(xs), max(xs)], [min(ys), max(ys)], [min(zs), max(zs)]],
    }
    (assets / 'yukkuri_props' / f'{model_id}.json').write_text(json.dumps(mesh), encoding='utf-8')
    img.save(assets / 'textures/props' / f'{model_id}.png')


def _banded_texture():
    """8x8 texture: row 0 opaque red, row 1 transparent, rows 2..7 solid blue."""
    img = Image.new('RGBA', (8, 8), (40, 80, 200, 255))
    for x in range(8):
        img.putpixel((x, 0), (200, 40, 40, 255))
        img.putpixel((x, 1), (0, 0, 0, 0))
    return img


class TestPropMeshLint(unittest.TestCase):
    def test_clean_control_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Single vertical quad at y=1..5, x=-2..2, z=0, sampling v=1.5 (texel centre)
            verts = _make_quad(
                (-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0),
                [(0.1, 1.5 / 8), (0.9, 1.5 / 8), (0.9, 1.5 / 8), (0.1, 1.5 / 8)],
                (0, 0, 1),
            )
            _write_fixture(root, 'clean_prop', verts, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'clean_prop'])
            self.assertEqual(rc, 0)

    def test_uv_on_texel_boundary_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Constant v = 1.0/8 sits on boundary between opaque row 0 and transparent row 1
            verts = _make_quad(
                (-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0),
                [(0.1, 1.0 / 8), (0.9, 1.0 / 8), (0.9, 1.0 / 8), (0.1, 1.0 / 8)],
                (0, 0, 1),
            )
            _write_fixture(root, 'bad_uv', verts, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'bad_uv'])
            self.assertEqual(rc, 1)

    def test_uv_on_uniform_texels_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Constant v = 4.0/8 sits between row 3 and row 4 which are identical blue -> no flicker
            verts = _make_quad(
                (-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0),
                [(0.1, 4.0 / 8), (0.9, 4.0 / 8), (0.9, 4.0 / 8), (0.1, 4.0 / 8)],
                (0, 0, 1),
            )
            _write_fixture(root, 'uniform_uv', verts, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'uniform_uv'])
            self.assertEqual(rc, 0)

    def test_coplanar_same_normal_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            q1 = _make_quad((-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0), uvs, (0, 0, 1))
            q2 = _make_quad((-1, 2, 0), (3, 2, 0), (3, 6, 0), (-1, 6, 0), uvs, (0, 0, 1))
            _write_fixture(root, 'bad_cop_same', q1 + q2, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'bad_cop_same'])
            self.assertEqual(rc, 1)

    def test_coplanar_opposite_normal_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            q1 = _make_quad((-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0), uvs, (0, 0, 1))
            q2 = _make_quad((-2, 1, 0), (-2, 5, 0), (2, 5, 0), (2, 1, 0), uvs, (0, 0, -1))
            _write_fixture(root, 'bad_cop_opp', q1 + q2, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'bad_cop_opp'])
            self.assertEqual(rc, 1)

    def test_separated_parallel_faces_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            q1 = _make_quad((-2, 1, 0.0), (2, 1, 0.0), (2, 5, 0.0), (-2, 5, 0.0), uvs, (0, 0, 1))
            q2 = _make_quad((-2, 1, 0.05), (2, 1, 0.05), (2, 5, 0.05), (-2, 5, 0.05), uvs, (0, 0, 1))
            _write_fixture(root, 'ok_sep', q1 + q2, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'ok_sep'])
            self.assertEqual(rc, 0)

    def test_ground_plane_face_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            q = _make_quad((-2, 0, -2), (2, 0, -2), (2, 0, 2), (-2, 0, 2), uvs, (0, -1, 0))
            _write_fixture(root, 'bad_ground', q, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'bad_ground'])
            self.assertEqual(rc, 1)

    def test_neighbour_block_plane_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            # Face flush on x = 8.0 of a 1-block placed prop
            q = _make_quad((8, 1, -2), (8, 1, 2), (8, 5, 2), (8, 5, -2), uvs, (1, 0, 0))
            _write_fixture(root, 'bad_neighbour', q, _banded_texture(), placed=True)
            rc = cpm.main(['--check', '--root', str(root), '--allowlist', 'none', '--model', 'bad_neighbour'])
            self.assertEqual(rc, 1)

    def test_allowlist_ratchet(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            uvs = [(0.1, 3.5 / 8), (0.9, 3.5 / 8), (0.9, 6.5 / 8), (0.1, 6.5 / 8)]
            q1 = _make_quad((-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0), uvs, (0, 0, 1))
            q2 = _make_quad((-2, 1, 0), (2, 1, 0), (2, 5, 0), (-2, 5, 0), uvs, (0, 0, 1))
            _write_fixture(root, 'ratchet', q1 + q2, _banded_texture(), placed=True)
            allow = root / 'allow.json'
            # 2 overlapping triangles -> 2 pairs? Actually 2 tris vs 2 tris = 2 pairs (each tri overlaps 1+1)
            allow.write_text(json.dumps({'models': {'ratchet': {'coplanar_overlap': 4}}}), encoding='utf-8')
            self.assertEqual(cpm.main(['--check', '--root', str(root), '--allowlist', str(allow), '--model', 'ratchet']), 0)
            allow.write_text(json.dumps({'models': {'ratchet': {'coplanar_overlap': 1}}}), encoding='utf-8')
            self.assertEqual(cpm.main(['--check', '--root', str(root), '--allowlist', str(allow), '--model', 'ratchet']), 1)


if __name__ == '__main__':
    unittest.main()

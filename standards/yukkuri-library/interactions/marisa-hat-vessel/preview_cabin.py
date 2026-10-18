"""Compatibility entry for the current hat-cargo/cabin offline preview.

The previous scene put two children beside full storage. The current shared
space rule requires those children to leave the cavity, so that obsolete scene
must not be regenerated. The authored preview now lives beside the cargo mesh.
"""
from pathlib import Path
import runpy
import sys

SOURCE = Path(__file__).resolve().parents[2] / 'drafts/care-props/preview_cargo.py'

if __name__ == '__main__':
    sys.path.insert(0, str(SOURCE.parent))
    runpy.run_path(str(SOURCE), run_name='__main__')

"""Compatibility entry for the hat-boat larder preview.

The crown larder (cargo.py) is previewed with the captain, crew and hull in
motions/variants/lore/aquatic_home_preview.py; this runs its larder sheet
(.local/previews/aquatic-home/larder.png).
"""
from pathlib import Path
import runpy
import sys

SOURCE = Path(__file__).resolve().parents[2] / 'motions/variants/lore/aquatic_home_preview.py'

if __name__ == '__main__':
    sys.argv = [str(SOURCE), '--only', 'larder']
    sys.path.insert(0, str(SOURCE.parent))
    runpy.run_path(str(SOURCE), run_name='__main__')

"""Compatibility entry for the current hat-boat cabin preview: the boat-as-home sheets (hull size, crown larder, crew
seats and poses) of motions/variants/lore/aquatic_home_preview.py, written to .local/previews/aquatic-home/."""
from pathlib import Path
import runpy
import sys

SOURCE = Path(__file__).resolve().parents[2] / 'motions/variants/lore/aquatic_home_preview.py'

if __name__ == '__main__':
    sys.path.insert(0, str(SOURCE.parent))
    runpy.run_path(str(SOURCE), run_name='__main__')

"""Run export_runtime_models.export() for house characters against the frozen pre-rounding inputs.

Writes the selected snapshot and game model of each house character. The ledger record is
saved to .local/house-runtime-records.json; copy it into art/runtime-sources.json and
art/source/standard-runtime/provenance.json, then run scripts/export_house_carry_motion.py
and scripts/export_house_face_motion.py --publish to refresh their manifests.

main() refuses while the rounded selection is active, and the live assembled/<species> is now the
rounded body. The house was authored from body-standard/inputs/<species>, so a symlinked library
view maps assembled/<species> there; recorded paths are rewritten to the real locations.
"""
import json, os, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT/'scripts'))
import export_runtime_models as erm
real = erm.LIB; view = ROOT/'.local/house-runtime-lib'  # temporary symlinked library view
(view/'assembled').mkdir(parents=True, exist_ok=True)
for name, target in [('assembled/marisa', real/'body-standard/inputs/marisa'), ('assembled/reimu', real/'body-standard/inputs/reimu'), ('ecology', real/'ecology')]:
    link = view/name
    if not link.is_symlink(): os.symlink(target, link)
erm.LIB = view
records = {}
for species in sys.argv[1:] or ['reimu', 'marisa']:
    ident, model, textures, record = erm.export(species, True)
    for key, value in record.items():
        if isinstance(value, str) and value.startswith('.local/house-runtime-lib/assembled/'):
            record[key] = 'standards/yukkuri-library/body-standard/inputs/' + value.split('/assembled/', 1)[1]
    records[ident] = record
    print(ident, len(model['weights']), 'vertices;', len(model['clips']), 'clips')
(ROOT/'.local/house-runtime-records.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')

import json
from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/assembled');r=json.loads((p/'animation-rig.json').read_text(encoding='utf-8'));bb=json.loads((p/'marisa-braids.bbmodel').read_text(encoding='utf-8'))
print([(e['name'],e['from'],e['to']) for e,g in zip(bb['elements'],r['groups']) if g=='adult' and any(x in e['name'] for x in ['cheek_slice_10','hair','crown'])][:16])
# Baseline artifacts for a bounded before/after collision comparison.
import shutil
out=Path('tools/temp/braid-clearance-before');out.mkdir(parents=True,exist_ok=True)
for n in ['animation-rig.json','marisa-braids.bbmodel']:shutil.copy2(p/n,out/n)

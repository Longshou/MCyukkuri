from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/build.py');s=p.read_text(encoding='utf-8').replace('-2.1*clearance','-2.8*clearance');p.write_text(s,encoding='utf-8')

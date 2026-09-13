from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/build.py');s=p.read_text(encoding='utf-8-sig').replace('bow=min(.16,dist*.025)','bow=min(.08,dist*.0125)').replace('(6.9,[.8,12.2,-5.5])','(6.9,[1.4,12.2,-5.5])');p.write_text(s,encoding='utf-8')

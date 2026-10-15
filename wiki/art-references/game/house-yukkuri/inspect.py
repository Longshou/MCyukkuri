from pathlib import Path
from html.parser import HTMLParser
import json
class Links(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=='a' and any(a.get('href','').lower().split('?')[0].endswith(x) for x in ['.jpg','.png','.jpeg']):self.links.append(a['href'])
p=Path(__file__).parent
s=(p/'comic.html').read_text(encoding='utf-8')
start=s.find('itemprop="articleBody"');s=s[start:s.find('article-footer',start)] if start>=0 else s
r=Links();r.feed(s)
links=list(dict.fromkeys(r.links));(p/'images.json').write_text(json.dumps(links),encoding='utf-8')
print('\n'.join(f'{i}: {x}' for i,x in enumerate(links)))

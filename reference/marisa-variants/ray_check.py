from PIL import Image
from pathlib import Path
import json,math
root=Path('standards/yukkuri-v1/subspecies')
im=Image.open(root/'tsumuri/retract-inside-quarter.png').convert('RGB')
gold=[(x,y) for y in range(70,260) for x in range(400,780) if (lambda c:c[0]>c[1]+8 and c[1]>c[2]+25)(im.getpixel((x,y)))]
px,py=gold[0];print('pixel',px,py,im.getpixel((px,py)))
w,h=im.size;scale=h/37;a=-.65;b=.3;c=math.cos(a);s=math.sin(a);ce=math.cos(b);se=math.sin(b)
right=[c,0,s];up=[-s*se,ce,c*se];eye=[s*ce,se,-c*ce]
world=[right[i]*(px+.5-w/2)/scale+up[i]*(h/2-py-.5)/scale+[0,10,0][i] for i in range(3)]
C=math.cos(math.radians(35));S=math.sin(math.radians(35))
def local(v,point=False):
 if point:v=[n-p for n,p in zip(v,[0,9.15,4.3])]
 return [v[0],C*v[1]+S*v[2],-S*v[1]+C*v[2]]
o=local(world,True);d=local(eye);hits=[]
for e in json.loads((root/'tsumuri/components.json').read_text())['elements']:
 if e['bone']!='shell':continue
 lo=[v-p for v,p in zip(e['from'],[0,9.15,4.3])];hi=[v-p for v,p in zip(e['to'],[0,9.15,4.3])]
 intervals=[sorted(((lo[i]-o[i])/d[i],(hi[i]-o[i])/d[i])) for i in range(3)]
 near=max(v[0] for v in intervals);far=min(v[1] for v in intervals)
 if near<far:hits.append((far,e['name']))
print('shell hits',sorted(hits,reverse=True)[:5])
print('origin',o,'mouth',[o[i]+d[i]*(-o[1]/d[1]) for i in range(3)])

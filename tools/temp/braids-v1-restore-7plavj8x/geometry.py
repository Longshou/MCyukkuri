import math

def vertices(e,texture_size=128):
 x,y,z=e['from'];X,Y,Z=e['to'];qs={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]};ns={'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]};out=[]
 for k,ps in qs.items():
  u,v,U,V=e['faces'][k]['uv'];uvs=[(u,v),(U,v),(U,V),(u,V)]
  for i in (0,1,2,0,2,3):out.extend([*ps[i],uvs[i][0]/texture_size,uvs[i][1]/texture_size,*ns[k]])
 if any(e.get('rotation',[0,0,0])):
  ax,ay,az=[math.radians(v*s) for v,s in zip(e['rotation'],[-1,-1,1])]
  cx,sx,cy,sy,cz,sz=math.cos(ax),math.sin(ax),math.cos(ay),math.sin(ay),math.cos(az),math.sin(az)
  matrix=[[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx],[sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx],[-sy,cy*sx,cy*cx]]
  for i in range(0,len(out),8):
   for offset,pivot in [(0,e.get('origin',[0,0,0])),(5,[0,0,0])]:
    v=[out[i+offset+j]-pivot[j] for j in range(3)]
    out[i+offset:i+offset+3]=[sum(row[k]*v[k] for k in range(3))+pivot[j] for j,row in enumerate(matrix)]
 return out

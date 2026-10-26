"""Shared-vertex art meshes, serialized to BBModel and legacy polygon geometry.

Polygon geometry is an interchange export, not a GeckoLib runtime asset.
"""
import math
from geometry import vertices as cube_vertices


def normal(points):
    a,b,c=points
    u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)]
    n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
    length=math.sqrt(sum(x*x for x in n))
    if length<1e-12:raise ValueError('Degenerate mesh face')
    return [x/length for x in n]


def mesh(name,points,triangles,uvs):
    return dict(name=name,type='mesh',origin=[0,0,0],rotation=[0,0,0],
        vertices={str(i):p for i,p in enumerate(points)},
        faces={str(i):dict(vertices=list(map(str,t)),uv={str(k):uv for k,uv in zip(t,uvs[i])},texture=0)
               for i,t in enumerate(triangles)})


def body_mesh(profile,skin):
    points=[];triangles=[];uvs=[]
    u,v,U,V=skin['faces']['north']['uv']
    for y,x,z,f in profile:
        # Broad front plane supports the existing eyes and cheeks.
        points.extend([[x,y,-.7*f],[x,y,.7*z],[.7*x,y,z],[-.7*x,y,z],
                       [-x,y,.7*z],[-x,y,-.7*f],[-.9*x,y,-f],[.9*x,y,-f]])
    for row in range(len(profile)-1):
        for j in range(8):
            a=row*8+j;b=row*8+(j+1)%8;c=b+8;d=a+8
            triangles.extend([(a,d,c),(a,c,b)])
            uvs.extend([[(u,V),(u,v),(U,v)],[(u,V),(U,v),(U,V)]])
    for row,reverse in [(0,False),(len(profile)-1,True)]:
        for j in range(1,7):
            t=[row*8,row*8+j,row*8+j+1]
            if reverse:t.reverse()
            triangles.append(t);uvs.append([(u,v),(U,v),(U,V)])
    return mesh('house_body_surface',points,triangles,uvs)


def vertices(e,texture_size=128):
    if e.get('type')!='mesh' or 'vertices' not in e or any('vertices' not in f for f in e.get('faces',{}).values()):return cube_vertices(e,texture_size)
    out=[]
    for f in e['faces'].values():
        points=[e['vertices'][k] for k in f['vertices']];n=normal(points)
        for k,p in zip(f['vertices'],points):out.extend([*p,*(v/texture_size for v in f['uv'][k]),*n])
    return out


def poly_mesh(e):
    keys=list(e['vertices']);ids={k:i for i,k in enumerate(keys)}
    result=dict(normalized_uvs=False,positions=list(e['vertices'].values()),normals=[],uvs=[],polys=[])
    for f in e['faces'].values():
        ni=len(result['normals']);result['normals'].append(normal([e['vertices'][k] for k in f['vertices']]))
        poly=[]
        for k in f['vertices']:
            ui=len(result['uvs']);result['uvs'].append(f['uv'][k]);poly.append([ids[k],ni,ui])
        result['polys'].append(poly)
    return result

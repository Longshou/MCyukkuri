"""Low polygon surfaces with closed outlines, shared rings and explicit attachment pivots."""
import math
from mesh_geometry import mesh


def solid(name, points, triangles, uv, bone):
    # Flat palette cells keep adjacent polygons continuous, without stretching line textures.
    e = mesh(name, points, triangles, [[uv, uv, uv] for _ in triangles])
    e['bone'] = bone
    return e


def ring_surface(name, rings, segments, uv, bone, closed=True):
    points = [p for ring in rings for p in ring]
    faces = []
    for j in range(len(rings)-1):
        for i in range(segments):
            a=j*segments+i; b=j*segments+(i+1)%segments; c=b+segments; d=a+segments
            faces.extend([(a,c,d),(a,b,c)])
    if closed:
        for row,reverse in [(0,True),(len(rings)-1,False)]:
            start=row*segments; center=len(points)
            points.append([sum(p[k] for p in rings[row])/segments for k in range(3)])
            for i in range(segments):
                tri=(center,start+i,start+(i+1)%segments)
                faces.append(tuple(reversed(tri)) if reverse else tri)
    # Rings are CCW viewed from above. Determine orientation using an interior point.
    center=[sum(p[k] for p in points)/len(points) for k in range(3)]
    fixed=[]
    for tri in faces:
        a,b,c=[points[i] for i in tri]; u=[b[k]-a[k] for k in range(3)];v=[c[k]-a[k] for k in range(3)]
        n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
        outward=[(a[k]+b[k]+c[k])/3-center[k] for k in range(3)]
        if sum(n[k]*outward[k] for k in range(3))<0:tri=tuple(reversed(tri))
        if sum(v*v for v in n)>1e-10:fixed.append(tri)
    return solid(name,points,fixed,uv,bone)


def ellipse(rx, rz, y, n=24, cx=0, cz=0, wave=0):
    return [[cx+(rx+wave*(1 if i%2 else -1))*math.cos(i*math.tau/n),
             y,cz+(rz+wave*(1 if i%2 else -1))*math.sin(i*math.tau/n)] for i in range(n)]


def body(profile, uv):
    rings=[]
    for y,x,z,f in profile:
        # The broad front supports the shared eyes. The sides and rear curve continuously.
        rings.append([[x,y,0],[.94*x,y,.60*z],[.70*x,y,.91*z],[.35*x,y,z],
                      [-.35*x,y,z],[-.70*x,y,.91*z],[-.94*x,y,.60*z],[-x,y,0],
                      [-.95*x,y,-.68*f],[-.85*x,y,-f],[.85*x,y,-f],[.95*x,y,-.68*f]])
    return ring_surface('predator_body_surface',rings,12,uv,'body_stretch')


def tube(name, start, end, radius, uv, bone, taper=None, sides=6):
    direction=[end[i]-start[i] for i in range(3)];length=math.sqrt(sum(v*v for v in direction))
    d=[v/length for v in direction];ref=[0,1,0] if abs(d[1])<.9 else [1,0,0]
    x=[d[1]*ref[2]-d[2]*ref[1],d[2]*ref[0]-d[0]*ref[2],d[0]*ref[1]-d[1]*ref[0]]
    q=math.sqrt(sum(v*v for v in x));x=[v/q for v in x]
    z=[d[1]*x[2]-d[2]*x[1],d[2]*x[0]-d[0]*x[2],d[0]*x[1]-d[1]*x[0]]
    rings=[]
    for p,r in [(start,radius),(end,taper if taper is not None else radius)]:
        rings.append([[p[k]+r*(x[k]*math.cos(i*math.tau/sides)+z[k]*math.sin(i*math.tau/sides)) for k in range(3)] for i in range(sides)])
    return ring_surface(name,rings,sides,uv,bone)


def prism(name, polygon, depth, uv, bone):
    """Extrude a simple XY silhouette; ear clipping retains the concave bat-wing scallops."""
    points=[list(p) for p in polygon]+[[p[0],p[1],p[2]+depth] for p in polygon]
    n=len(polygon)
    area=sum(polygon[i][0]*polygon[(i+1)%n][1]-polygon[(i+1)%n][0]*polygon[i][1] for i in range(n))
    ids=list(range(n)) if area>0 else list(reversed(range(n)))
    cross=lambda a,b,c:(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    triangles=[]
    remaining=ids[:]
    while len(remaining)>3:
        for j,b in enumerate(remaining):
            a=remaining[j-1];c=remaining[(j+1)%len(remaining)]
            if cross(points[a],points[b],points[c])<=1e-8:continue
            if any(min(cross(points[a],points[b],points[k]),cross(points[b],points[c],points[k]),cross(points[c],points[a],points[k]))>=-1e-8 for k in remaining if k not in (a,b,c)):continue
            triangles.append((a,b,c));remaining.pop(j);break
        else:raise ValueError('Cannot triangulate '+name)
    triangles.append(tuple(remaining))
    faces=[tuple(reversed(t)) for t in triangles]+[tuple(i+n for i in t) for t in triangles]
    for j,a in enumerate(ids):
        b=ids[(j+1)%n];faces.extend([(a,b,b+n),(a,b+n,a+n)])
    return solid(name,points,faces,uv,bone)


def crystal(name, x, y, z, uv, bone):
    p=[[x,y+.85,z],[x-.42,y,z],[x,y,z-.33],[x+.42,y,z],[x,y,z+.33],[x,y-1.0,z]]
    t=[(0,2,1),(0,3,2),(0,4,3),(0,1,4),(5,1,2),(5,2,3),(5,3,4),(5,4,1)]
    return solid(name,p,t,uv,bone)

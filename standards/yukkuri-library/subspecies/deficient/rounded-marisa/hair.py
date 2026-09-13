"""Sparse hair grows from the rounded scalp, independently of the removable hat."""
import math


def ribbon(source, body, atlas, start_y, end_y, lane, side=False, width=.42, thickness=.16):
    """Closed narrow ribbon on the actual front/side profile, with an embedded root."""
    vv=body.vertices(source,atlas)
    uvbox=[min(vv[k::8])*atlas for k in (3,4)]+[max(vv[k::8])*atlas for k in (3,4)]
    points=[];uv=[];triangles=[];centers=[]
    count=16
    for row in range(count+1):
        t=row/count;y=start_y+(end_y-start_y)*t
        rx,rz,front=body.profile_at(y)
        half=width*(.5-.27*t)
        embed=-.07 if row==0 else -.025
        if side:
            center=[lane*rx*.98,y,-front*.272]
            tangent=[0,0,half];normal=[lane,0,0]
        else:
            center=[lane*rx,y,-front]
            tangent=[half,0,0];normal=[0,0,-1]
        centers.append(center)
        for s,d in [(-1,embed),(1,embed),(1,thickness+embed),(-1,thickness+embed)]:
            points.append([center[k]+s*tangent[k]+d*normal[k] for k in range(3)])
            uv.append([uvbox[0] if s<0 else uvbox[2],uvbox[1]+(uvbox[3]-uvbox[1])*t])
    for row in range(count):
        for corner in range(4):
            a=row*4+corner;b=row*4+(corner+1)%4;c=b+4;d=a+4
            triangles.extend([(a,b,c),(a,c,d)])
    triangles.extend([(0,2,1),(0,3,2),(count*4,count*4+1,count*4+2),(count*4,count*4+2,count*4+3)])
    fixed=[]
    for tri in triangles:
        a,b,c=[points[i] for i in tri]
        u=[b[k]-a[k] for k in range(3)];v=[c[k]-a[k] for k in range(3)]
        normal=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
        mid=[sum(points[i][k] for i in tri)/3 for k in range(3)]
        row=min(count-1,min(tri)//4)
        center=[(centers[row][k]+centers[row+1][k])/2 for k in range(3)]
        # Use the local closed ribbon center, not the center of its whole curved path.
        center[0 if side else 2]+= (lane if side else -1)*(.045)
        if sum(normal[k]*(mid[k]-center[k]) for k in range(3))<0:tri=tuple(reversed(tri))
        fixed.append(tri)
    e=body.mesh(source['name'],points,fixed,[[uv[i] for i in tri] for tri in fixed])
    e.update(uuid=source['uuid'],bone='body_stretch',body_follow=True)
    return e

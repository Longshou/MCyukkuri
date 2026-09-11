"""Stepped conch tower with a shallow basal whorl and a seated, open mouth."""
import math

PIVOT = [0, 5.9, 1.75]
TILT = 25
ROLL = 0
ROTATION = [-TILT, 0, ROLL]
SHELL_SIZE = .90
RETRACT_SCALE = [1, .23, .78]
WHORL_POWER = 3.5


def square_arc(a):
    """A softened square arc between the previous round and square versions."""
    c,s=math.cos(a),math.sin(a)
    return math.copysign(abs(c)**(2/WHORL_POWER),c),math.copysign(abs(s)**(2/WHORL_POWER),s)


def build_shell(cube, ring):
    parts=[]
    # Preserve the crown/rear-hair contact independently of the new tower.
    parts += ring('shell_mouth_rim',8.0,7.65,-.15,.58,0,.68,'shell_light','shell',strips=24,power=4)
    parts += ring('shell_mouth_inner',7.35,7.03,.1,.20,0,.20,'shell_dark','shell',strips=24,power=4)
    # Each tuple is an actual whorl. The shallow basal chamber no longer forms
    # a same-width barrel under the spire. The yielding body flattens to enter.
    profiles=[(.38,2.70,10.70,10.25), (3.06,3.50,8.75,8.40),
              (6.54,3.45,6.80,6.55), (9.97,3.40,4.95,4.80),
              (13.35,3.05,3.10,3.00), (16.38,1.65,1.55,1.60)]
    for whorl,(y,h,rx,rz) in enumerate(profiles):
        for step in range(5):
            q=step/5
            taper=1-(.05 if whorl==0 else .14)*q
            if whorl==0 and step==0:
                rrx,rrz=9.0,8.6
            elif whorl==0 and step==1:
                rrx,rrz=10.15,9.7
            else:
                rrx,rrz=rx*taper,rz*taper
            mat='shell_lip' if step==0 else 'shell_light' if step==4 else 'shell'
            parts+=ring(f'shell_coil_{whorl}_{step}',rrx,rrz,y+h*q,h/5+.025,
                        0,.70 if whorl==0 else 30,mat,'shell',strips=16,power=WHORL_POWER)
        if whorl==0:
            parts+=ring('shell_chamber_ceiling',10.25,9.82,3.02,.15,0,30,'shell_lip','shell',strips=16,power=WHORL_POWER)
        for i in range(16):
            a=math.pi+i/15*math.pi
            dx,dz=square_arc(a)
            x=dx*rx*.965;z=dz*rz*.965
            parts.append(cube(f'shell_turn_seam_{whorl}_{i}',[x-.23,y+.30+i*.022,z-.23],
                              [x+.23,y+.43+i*.022,z+.23],'shell_lip','shell'))
    parts.append(cube('shell_spire_tip',[-.4,17.95,-.3],[.48,18.60,.65],'shell_light','shell'))
    # Eight spikes, each rooted on the narrower radius of its own turn.
    for n,(a,y,r) in enumerate([(-2.25,1.65,10.2),(-.9,1.65,10.2),
                                (-2.25,3.75,8.4),(-.9,3.75,8.4),
                                (-2.25,7.25,6.45),(-.9,7.25,6.45),
                                (-2.25,10.7,4.65),(-.9,10.7,4.65)]):
        for step in range(4):
            half=.80-step*.18
            rr=r+step*.25
            dx,dz=square_arc(a)
            x=dx*rr;z=dz*rr
            parts.append(cube(f'shell_spike_{n}_{step}',[x-half,y+step*.50,z-half],
                              [x+half,y+(step+1)*.50+.025,z+half],
                              'shell_light' if step else 'shell_lip','shell'))
    for p in parts:
        for key in ('from','to'):
            p[key]=[round(v*scale*SHELL_SIZE+offset,6) for v,scale,offset in zip(p[key],[1.07,1,1.07],PIVOT)]
        p['origin']=PIVOT[:]
        p['rotation']=ROTATION[:]
    return parts

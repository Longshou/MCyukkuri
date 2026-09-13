"""Inclined, open-bottom conch shell. No enclosing box or under-face walls."""
import math

PIVOT = [0, 6.65, 1.0]
TILT = 25


def build_shell(cube, ring):
    parts=[]
    # Model in a local upright frame, then incline every editable cuboid together.
    # The fitted mouth meets the crown at its front and the rear hair at its
    # back. Its rounded corners match the standard head; the larger chamber
    # flares outward above this collar, rather than using a loose oversized rim.
    parts += ring('shell_mouth_rim',8.0,7.65,-.15,.58,0,.68,'shell_light','shell',strips=24,power=4)
    parts += ring('shell_mouth_inner',7.35,7.03,.1,.20,0,.20,'shell_dark','shell',strips=24,power=4)
    # A roomy first whorl accepts the full-size head. Coils above it contract
    # into the angled spire; all low coils are hollow and have no bottom plate.
    profiles=[(0.38,2.0,8.55,8.12),(2.28,2.15,10.95,10.25),
              (4.33,2.15,10.8,10.1),(6.38,2.1,10.65,10.0),
              (8.38,1.95,10.45,9.8),(10.23,1.85,8.2,7.7),
              (11.98,1.60,4.9,4.7),(13.48,1.35,2.7,2.6)]
    for whorl,(y,h,rx,rz) in enumerate(profiles):
        for step in range(3):
            q=step/3
            # Keep the retreat chamber broad until the upper spire begins.
            taper=1+.20*q if whorl==0 else 1-(.025 if whorl<5 else .12)*q
            material='shell_lip' if step==0 else 'shell'
            parts+=ring(f'shell_coil_{whorl}_{step}',rx*taper,rz*taper,y+h*q,h/3+.025,
                        0,.90 if whorl<5 else 30,material,'shell',strips=24 if whorl==0 else 18,power=4 if whorl==0 else 2)
    parts+=ring('shell_chamber_ceiling',10.25,9.6,10.25,.3,0,30,'shell_lip','shell',strips=18)
    parts.append(cube('shell_spire_tip',[-.55,14.68,-.4],[.65,15.4,1.05],'shell_light','shell'))
    # Raised sloping ribs flow around the first turn, rather than closing its
    # mouth. Paired surfaces stay distinct in a Minecraft cuboid model.
    for turn in (1,2):
        for i in range(64):
            a=i/64*math.tau
            y=.85+turn*2.4+i/64*1.5
            rx=10.75;rz=10.0
            x=math.cos(a)*rx;z=math.sin(a)*rz
            parts.append(cube(f'shell_spiral_rib_{turn}_{i}',[x-.23,y-.12,z-.23],
                              [x+.23,y+.12,z+.23],'shell_lip','shell'))
    # Eight clear outward-facing spikes, tapering to small square tips.
    for n,(a,y,r) in enumerate([(-2.25,1.2,9.1),(-.9,1.2,9.1),
                                (-2.6,4.7,10.7),(-.55,4.7,10.7),
                                (-2.0,8.9,10.4),(-1.0,8.9,10.4),
                                (-2.25,12.1,3.9),(-.9,12.1,3.9)]):
        for step in range(4):
            half=.88-step*.20
            rr=r+step*.42
            x=math.cos(a)*rr;z=math.sin(a)*rr
            parts.append(cube(f'shell_spike_{n}_{step}',[x-half,y+step*.64,z-half],
                              [x+half,y+(step+1)*.64+.03,z+half],
                              'shell_light' if step else 'shell_lip','shell'))
    for p in parts:
        for key in ('from','to'):
            p[key]=[round(v*scale+offset,6) for v,scale,offset in zip(p[key],[1.07,1,1.07],PIVOT)]
        p['origin']=PIVOT[:]
        # Stored Blockbench / Bedrock axes, converted by the local preview just
        # like the standard animation exporter (X and Y change sign).
        p['rotation']=[-TILT,0,0]
    return parts

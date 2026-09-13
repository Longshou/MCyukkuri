from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/build.py')
s=p.read_text(encoding='utf-8')
s=s.replace("bone('hat',[0,10,-3],'adult')", "bone('hat',[0,10,-3],'adult')\nbone('hat_front',[0,10,-6],'hat')\nbone('bow',[0,12,-5],'hat')")
s=s.replace("element(e,'hat' if g in ('hat','bow') else 'adult')", "element(e,'hat_front' if e['name']=='brim_front' else 'bow' if g=='bow' else 'hat' if g=='hat' else 'adult')")
a=s.index("    else:\n        # Approach outside the silhouette")
b=s.index("    for side in ['left','right']:o.update(reach",a)
s=s[:a]+s[b:]
s=s.replace('def state(action,t):\n    targets=', 'def ordinary_state(action,t):\n    targets=',1)
s=s.replace('original_state=state\n','').replace("return original_state(action,t)","return ordinary_state(action,t)").replace("original_state('wave_goodbye',0)","ordinary_state('wave_goodbye',0)")
s=s.replace("p=curve([(0,[0,0,-7]),(2,[0,0,-7]),(3.8,[0,6.5,-8]),(5.1,[0,11,-8]),(6.2,[0,10.123,-7.5])],t)","p=curve([(0,[0,0,-7]),(2,[0,0,-7]),(3.8,[0,6.5,-8]),(5.1,[0,11,-8]),(6.2,[0,10.123,-7.5])],t)\n    o['hat_front']={'scale':[1,1,1+3.65*smooth((t-3.5)/1.5)]}")
s=s.replace('"""A fixed-length, outward bending quadratic hair strand (no human hand mesh)."""','"""Outward bending strand, with modest reach extension for carrying poses."""')
# Add end loops using existing hair UV; U-shaped locks grasp props without fingers.
pos=s.index('\npalette=')
s=s[:pos]+'''
+for side,sign in [('left',-1),('right',1)]:
+    p=rest_ends[side];bone('coil_'+side,p,'grip_'+side)
+    hair=next(e for e in source_elements if e['name']==f'side_tip_{sign}')
+    for i,(lo,hi) in enumerate([([-.72,-.3,-.5],[-.43,.35,.35]),([.43,-.3,-.5],[.72,.35,.35]),([-.72,-.4,-.6],[.72,-.15,-.3])]):
+        e=copy.deepcopy(hair);e['name']=f'grasp_{side}_{i}';e['from']=add(p,lo);e['to']=add(p,hi);element(e,'coil_'+side)
+'''.replace('\n+','\n')+s[pos:]
s=s.replace("for side in ['left','right']:o.update(reach(side,targets[side]))", "for side in ['left','right']:\n        o.update(reach(side,targets[side]))\n        amount=1 if contacts.get(side) else .000001\n        o['coil_'+side]={'scale':[amount]*3}\n    if action=='care_hat':\n        tidy=smooth((t-5.2)/1.3)*(1-smooth((t-6.5)/.4))\n        o['bow']={'rotation':[0,0,tidy*3*math.sin(t*10)]}")
s=s.replace("meta['contacts'][side]='child' if 2<=t<=6.2 else None", "meta['contacts'][side]='child' if 2<=t<=6.2 else None\n        o['coil_'+side]={'scale':[1]*3 if 2<=t<=6.2 else [.000001]*3}")
p.write_text(s,encoding='utf-8')

"""Character-local UV painting following deibu-mature's part-box approach.

The original atlas is untouched. Preserve expression intent and paint dry lids,
short dry marks in the existing face UV rectangles. No lower-face contour line.
"""
from PIL import Image, ImageDraw


def face_boxes(model):
    boxes={};size=model.get('texture_size',128);vertices=model['vertices']
    for part in model['parts']:
        if not part['name'].startswith('face_'):continue
        values=vertices[part['start']*8:(part['start']+part['count'])*8]
        # Cuboid faces (house/bean/predator) share an invisible side-UV pixel.
        # Only front-facing triangles define the expression island.
        front=[]
        for k in range(0,len(values),24):
            tri=values[k:k+24]
            if sum(tri[j+7] for j in (0,8,16))<-.6:front.extend(tri)
        if front:values=front
        boxes[part['name']]=tuple(round(v*size) for v in [min(values[3::8]),min(values[4::8]),max(values[3::8]),max(values[4::8])])
    return boxes


# The stem-drain stages show these atlases at rest (PresentationArbiter: stage 1 'teary', stages 2-3 'neutral',
# with the drain stage as the dry grade): a hollowed-out, starving face that worsens per stage (project design).
DRAINED={('teary',1):1,('neutral',2):2,('neutral',3):3}
# HairWoundAssets.DRAIN_TINT: the skin under these decals is greyed by the stage tint.
DRAIN_TINT=[(240,238,240),(222,220,224),(204,202,210)]


def _skin(atlas):
    counts={}
    for r,g,b,a in atlas.getdata():
        if a>200:counts[(r,g,b)]=counts.get((r,g,b),0)+1
    return max(counts,key=counts.get)


def _shade(colour,stage,amount):
    tint=DRAIN_TINT[stage-1]
    return tuple(round(c*t/255*(1-amount)) for c,t in zip(colour,tint))+(255,)


def _blocks(part,cells,colour,unit):
    """Draws a 28x24 mouth-grid pattern (2-texel art pixels) at the box's own scale."""
    draw=ImageDraw.Draw(part)
    for x,y in cells:draw.rectangle([round(x*unit),round(y*unit),round((x+2)*unit)-1,round((y+2)*unit)-1],fill=colour)


def _drained(atlas,boxes,stage,sibling):
    """Sunken, dull, drooping eyes; worried brows; no smile, no blush; darker hollows under the eyes and cheeks."""
    skin=_skin(atlas)
    teary=sibling('teary') if sibling and stage>1 else None
    for name,box in boxes.items():
        w,h=box[2]-box[0],box[3]-box[1]
        if name.startswith('face_brow_') and teary is not None:
            atlas.paste(teary.crop(box),box[:2])
        elif name.startswith('face_eye_'):
            part=atlas.crop(box);px=part.load()
            cells=[(x,y) for y in range(h) for x in range(w) if px[x,y][3]>=128]
            if not cells:continue
            outline=min((px[c] for c in cells),key=lambda c:sum(c[:3]))
            bright=[c for c in cells if sum(px[c][:3])>600]
            body=[px[c] for c in cells if c not in bright and px[c]!=outline]
            iris=max(set(body),key=body.count) if body else outline
            if stage==3:iris=tuple(round(v*.55+g*.45) for v,g in zip(iris[:3],(118,108,104)))+(255,)
            if stage>1:
                # A dull eye: the catch-light is gone.
                for c in bright:px[c]=iris
                # Heavy lids sloping down to the outer corner (the left box is the face's left eye on screen): sad, not a glare.
                rows=sorted({y for _,y in cells});xs=[x for x,_ in cells];left,right=min(xs),max(xs)
                inner,outer=(right,left) if name.endswith('left') else (left,right)
                top=rows[0]+len(rows)*(.3 if stage==2 else .5);drop=len(rows)*(.3 if stage==2 else .25)
                def lid(x):return round(top+drop*abs(x-inner)/max(1,right-left))
                for x,y in cells:
                    if y<lid(x):px[x,y]=(0,0,0,0)
                    elif y==lid(x):px[x,y]=outline
                step=1 if outer>inner else -1
                if 0<=outer+step<w and lid(outer)+1<h:px[outer+step,lid(outer)+1]=outline
            atlas.paste(part,box[:2])
        elif name.startswith('face_tear_'):
            part=atlas.crop(box) if stage==1 else Image.new('RGBA',(w,h))
            # Hollows under the eyes, just below the lower lid.
            draw=ImageDraw.Draw(part);bag=_shade(skin,stage,[.22,.32,.42][stage-1])
            row=8 if stage==1 else 6
            draw.line([(4,row),(5,row+1),(9,row+1),(10,row)],fill=bag,width=1)
            if stage==3:draw.line([(5,row+2),(9,row+2)],fill=_shade(skin,stage,.2),width=1)
            atlas.paste(part,box[:2])
        elif name.startswith('face_blush_'):
            # Pale: no blush; sunken cheeks as short hollow strokes.
            part=Image.new('RGBA',(w,h));draw=ImageDraw.Draw(part);hollow=_shade(skin,stage,.3)
            if stage==3:draw.line([(2,0),(3,2),(3,3)],fill=hollow,width=1)
            if name.endswith('right'):part=part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            atlas.paste(part,box[:2])
        elif name=='face_mouth' and stage>1:
            part=atlas.crop(box);px=part.load()
            cells=[(x,y) for y in range(h) for x in range(w) if px[x,y][3]>=128]
            if not cells:continue
            line=min((px[c] for c in cells),key=lambda c:sum(c[:3]))
            fangs=any(sum(px[c][:3])>650 for c in cells)
            part=Image.new('RGBA',(w,h));unit=w/28
            if stage==2:
                # A small down-turned mouth.
                _blocks(part,[(10,6),(12,6),(14,6),(16,6),(8,8),(18,8)],line,unit)
            else:
                # Slack and slightly open from exhaustion.
                _blocks(part,[(11,4),(13,4),(15,4),(9,6),(17,6),(9,8),(17,8),(11,10),(13,10),(15,10)],line,unit)
                _blocks(part,[(11,6),(13,6),(15,6),(11,8),(13,8),(15,8)],(92,48,46,255),unit)
            if fangs:_blocks(part,[(10,8),(16,8)] if stage==2 else [(11,6),(15,6)],(250,248,244,255),unit)
            atlas.paste(part,box[:2])
    return atlas


def apply_style(atlas,boxes,expression,grade,sibling=None):
    """sibling(name) returns the same model's atlas of another expression (or None)."""
    atlas=atlas.copy().convert('RGBA')
    if (expression,grade) in DRAINED:return _drained(atlas,boxes,DRAINED[expression,grade],sibling)
    crease=[(126,81,43,105),(91,61,38,145),(58,44,33,180)][grade-1]
    for name,box in boxes.items():
        w,h=box[2]-box[0],box[3]-box[1]
        if name.startswith('face_blush_'):
            part=Image.new('RGBA',(w,h));draw=ImageDraw.Draw(part)
            # A few short, disconnected dry strokes. Curving these toward the
            # mouth outlines a second chin instead of suggesting burned skin.
            if grade>=2:draw.line([(2,0),(2,1)],fill=crease,width=1)
            if grade==3:draw.line([(6,2),(6,3)],fill=crease,width=1)
            if name.endswith('right'):part=part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            atlas.paste(part,box[:2])
        elif name.startswith('face_eye_'):
            part=atlas.crop(box)
            if grade>=2 and expression in ['neutral','mature','smug','confused','angry','puff','surprise']:
                if grade==2:
                    # Keep the round pupil/iris and shorten the original open
                    # eye vertically; white slit + vertical bar reads as a glare.
                    short=part.resize((w,8),Image.Resampling.NEAREST)
                    part=Image.new('RGBA',(w,h));part.paste(short,(0,3))
                    draw=ImageDraw.Draw(part)
                    draw.line([(2,4),(4,3),(9,3),(12,4)],fill=(76,62,48,220),width=1)
                else:
                    # Exhausted closed lids: no bright sclera or staring pupil.
                    part=Image.new('RGBA',(w,h));draw=ImageDraw.Draw(part)
                    draw.line([(2,6),(4,7),(7,7),(10,6),(12,5)],fill=(92,76,58,230),width=1)
                    if name.endswith('right'):part=part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            draw=ImageDraw.Draw(part)
            if grade>=2:draw.line([(1,7),(1,8)],fill=crease,width=1)
            atlas.paste(part,box[:2])
        elif name=='face_mouth':
            part=atlas.crop(box);draw=ImageDraw.Draw(part)
            # Dry the existing lip pixels only; no detached line below the mouth.
            for py in range(h):
                for px in range(w):
                    r,g,b,a=part.getpixel((px,py))
                    if a and r>g*1.2:
                        amount=[.18,.38,.55][grade-1]
                        part.putpixel((px,py),(round(r*(1-amount)),round(g*(1-amount*.5)),round(b*(1-amount*.35)),a))
            atlas.paste(part,box[:2])
    return atlas

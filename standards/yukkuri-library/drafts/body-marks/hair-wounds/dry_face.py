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


def apply_style(atlas,boxes,expression,grade):
    atlas=atlas.copy().convert('RGBA')
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

"""Character-local UV painting following deibu-mature's part-box approach.

The original atlas is untouched. Preserve expression intent and paint dry lids,
short dry marks in the existing face UV rectangles. No lower-face contour line.
"""
from PIL import Image, ImageDraw


def face_boxes(model):
    boxes={};size=model['texture_size'];vertices=model['vertices']
    for part in model['parts']:
        if not part['name'].startswith('face_'):continue
        values=vertices[part['start']*8:(part['start']+part['count'])*8]
        boxes[part['name']]=tuple(round(v*size) for v in [min(values[3::8]),min(values[4::8]),max(values[3::8]),max(values[4::8])])
    return boxes


def apply_style(atlas,boxes,expression,grade):
    atlas=atlas.copy().convert('RGBA')
    eye=(63,45,32,255);iris=(91,66,43,255);white=(183,164,126,255)
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
                part=Image.new('RGBA',(w,h));draw=ImageDraw.Draw(part)
                top=3 if expression=='surprise' else 5
                bottom=8 if grade==2 else 7
                draw.polygon([(2,top),(11,top+1),(12,bottom),(3,bottom)],fill=white)
                draw.rectangle((6,top,8,bottom),fill=iris)
                draw.line([(7,top),(7,bottom)],fill=eye,width=1)
                lid=[(1,top-1),(5,top),(12,top+1)] if expression in ['angry','puff'] else [(1,top),(4,top-1),(9,top),(12,top+1)]
                draw.line(lid,fill=eye,width=1)
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

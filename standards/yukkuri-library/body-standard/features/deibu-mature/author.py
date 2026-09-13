"""Character-local pixel recipe; keeps shared expressions and frozen inputs intact."""
from PIL import Image,ImageDraw

def apply_style(atlas, boxes, expression, recipe):
    colors={k:tuple(recipe[k]) for k in ['eye','iris','sclera','crease','lip','lower_lip']}
    closed=expression in ['sleep','sleepy','happy','yawn']
    angry=expression in ['angry','puff']
    startled=expression in ['surprise']
    for name,box in boxes.items():
        if not name.startswith(('face_eye_','face_brow_','face_blush_')) and name!='face_mouth':continue
        w,h=box[2]-box[0],box[3]-box[1]
        part=Image.new('RGBA',(w,h));d=ImageDraw.Draw(part)
        if name.startswith('face_eye_'):
            if closed:
                d.line([(2,6),(5,7),(10,7),(12,5)],fill=colors['eye'],width=1)
                d.line([(4,10),(9,10)],fill=colors['crease'],width=1)
            else:
                top=3 if startled else 5
                d.polygon([(2,top),(11,top+1),(12,7),(10,8),(4,8),(2,7)],fill=colors['sclera'])
                d.rectangle((6,top,8,7),fill=colors['iris'])
                d.line([(7,top),(7,7)],fill=colors['eye'],width=1)
                lid=[(1,4),(5,5),(12,6)] if angry else [(1,5),(4,4),(9,4),(12,5)] if not startled else [(2,3),(5,2),(10,3),(12,4)]
                d.line(lid,fill=colors['eye'],width=2)
                d.line([(3,8),(6,9),(10,9)],fill=colors['crease'],width=1)
                d.point((0,7),fill=colors['crease'])
                d.point((1,4),fill=colors['eye'])
        elif name.startswith('face_brow_'):
            d.line([(3,3),(5,2),(9,2),(12,3)],fill=colors['eye'],width=1)
            if angry:d.line([(7,3),(12,4)],fill=colors['eye'],width=1)
        elif name.startswith('face_blush_'):
            # Muted cheek folds replace the two bright pink blush patches.
            d.line([(2,1),(3,2),(4,4)],fill=colors['crease'],width=1)
        elif name=='face_mouth':
            if expression not in ['neutral','mature','smug','angry','sleep','sleepy','confused','puff']:
                # Preserve open/crying/chewing shapes without the infant eye style.
                continue
            d.line([(5,12),(8,10),(18,10),(22,12)],fill=colors['lip'],width=1)
            d.line([(11,13),(16,13)],fill=colors['lower_lip'],width=1)
        if name.endswith('right'):part=part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        atlas.paste(part,(box[0],box[1]))
    return atlas

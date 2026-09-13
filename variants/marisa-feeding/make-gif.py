from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parent
frames=[];palette=None
for p in sorted((ROOT/'render-frames').glob('*.png')):
    im=Image.open(p).convert('RGB');im.thumbnail((720,520))
    if palette is None:palette=im.quantize(colors=128,method=Image.Quantize.MEDIANCUT)
    frames.append(im.quantize(palette=palette,dither=Image.Dither.NONE))
frames[0].save(ROOT/'feeding.gif',save_all=True,append_images=frames[1:],duration=80,loop=0,optimize=True,disposal=1)
print('Saved feeding.gif')

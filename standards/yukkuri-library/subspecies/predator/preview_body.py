"""Render current shared anatomy offline; use --node for the local runtime."""
from pathlib import Path
import argparse,subprocess
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[4]
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--node',default='node');args=parser.parse_args()
    out=ROOT/'.local/previews/predator-body';out.mkdir(parents=True,exist_ok=True)
    for ident in ('remilia','flandre','marisa'):
        (out/f'{ident}.rgba').write_bytes(Image.open(ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/neutral.png').convert('RGBA').tobytes())
    subprocess.run([args.node,str(Path(__file__).with_suffix('.cjs'))],check=True)
    image=Image.new('RGB',(1440,1170),(236,232,221));draw=ImageDraw.Draw(image);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    for row,(ident,label) in enumerate([('remilia','蕾米莉亚'),('flandre','弗兰'),('marisa','魔理沙／水生共用身体')]):
        for col,(view,caption) in enumerate([('rear','油菊'),('mamu','马姆马姆伸出'),('peni','佩妮佩妮伸出')]):
            tile=Image.frombytes('RGBA',(480,360),(out/f'{ident}-{view}.rgba').read_bytes());image.paste(tile,(col*480,row*390+30),tile)
            draw.text((col*480+12,row*390+5),f'{label} · {caption}',font=font,fill=(40,40,40))
    image.save(out/'current.png')
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>当前共享身体</title><h1>当前共享身体</h1><p>复用标准身体组件；离线预览不代表游戏验收。</p><img src="current.png" style="max-width:100%">',encoding='utf-8')
    for temp in out.glob('*.rgba'):temp.unlink()

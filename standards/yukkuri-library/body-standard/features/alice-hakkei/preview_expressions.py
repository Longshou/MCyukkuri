"""Render Alice's current expression coverage with the CPU rasterizer; no browser."""
from pathlib import Path
import importlib.util
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[2]
ROOT = LIB.parents[1]
OUT = ROOT / '.local/previews/alice'


def main():
    spec = importlib.util.spec_from_file_location('alice_preview', HERE / 'preview.py')
    preview = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preview)
    model, _ = preview.entry('alice')
    out = OUT / '_expressions'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'model.json').write_text(json.dumps(model), encoding='utf-8')
    entries = []
    presets = json.loads((LIB / 'expressions/index.json').read_text(encoding='utf-8'))['presets']
    for path in sorted((LIB / 'assembled/alice/textures').glob('*.png')):
        preset = json.loads((LIB / presets[path.stem]).read_text(encoding='utf-8'))
        entries.append((path.stem, preset['label'], path))
    runtime = ROOT / 'src/main/resources/assets/mcyukkuri/textures/entity/alice'
    for name, label in [('breeding_period', '状态：普通'), ('state_recover', '状态：恢复'), ('cheek_puff', '鼓颊')]:
        entries.append((name, label, runtime / (name + '.png')))
    for name, _, path in entries:
        (out / (name + '.texture')).write_bytes(Image.open(path).convert('RGBA').tobytes())
    script = """
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=process.argv[2],dir=__dirname;
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const model=JSON.parse(fs.readFileSync(path.join(dir,'model.json'),'utf8'));
const pose=new RuntimePose(model,1,null,null);
for(const name of JSON.parse(fs.readFileSync(path.join(dir,'names.json'),'utf8'))){
  pose.expression(name);
  const pixels=raster.renderModel(pose.frame('idle',0),fs.readFileSync(path.join(dir,name+'.texture')),320,300,0,.12,11.8,{centerY:8.4,textureSize:256});
  fs.writeFileSync(path.join(dir,name+'.rgba'),Buffer.from(pixels));
}
"""
    (out / 'render.cjs').write_text(script, encoding='utf-8')
    (out / 'names.json').write_text(json.dumps([e[0] for e in entries]), encoding='utf-8')
    subprocess.run(['node', str(out / 'render.cjs'), str(ROOT)], check=True)
    font = preview.font
    tiles = {name: Image.frombytes('RGBA', (320, 300), (out / (name + '.rgba')).read_bytes()) for name, _, _ in entries}

    def sheet(selected, columns, title, filename):
        canvas = Image.new('RGB', (columns * 320, 90 + ((len(selected)+columns-1)//columns)*340), '#f1ede3')
        draw = ImageDraw.Draw(canvas)
        draw.text((24, 15), title, font=font(28), fill='#292a25')
        draw.text((24, 54), '当前模型离线渲染 · 保留表情形状、高光、闭眼与灰色眼 · 造型待确认', font=font(18), fill='#555555')
        labels = {name: label for name, label, _ in entries}
        for i, name in enumerate(selected):
            x, y = i % columns * 320, 90 + i // columns * 340
            tile = tiles[name]
            canvas.paste(tile, (x, y), tile)
            draw.text((x+12, y+296), labels[name] + ' · ' + name, font=font(16), fill='#292a25')
        canvas.save(OUT / filename)

    sheet(['neutral', 'angry', 'teary', 'open_drool', 'smug', 'surprise', 'sleepy', 'sleep',
           'flushed_tongue', 'squeezed_cry_tears', 'breeding_period', 'state_recover'],
          4, '爱丽丝 · 新发型、发箍与蓝眼表情适配', 'expressions.png')
    sheet([e[0] for e in entries], 6, f'爱丽丝 · {len(entries)} 张表情图集覆盖', 'all-expressions.png')
    page = OUT / 'index.html'
    html = page.read_text(encoding='utf-8')
    marker = '<!-- expression-coverage -->'
    html = html.split(marker)[0].removesuffix('</body>')
    page.write_text(html + marker + '<h2>表情适配</h2><img src="expressions.png" style="max-width:100%">'
                    '<p><a href="all-expressions.png">全部表情图集</a></p></body>', encoding='utf-8')
    # Remove only this renderer's scratch outputs inside the topic folder.
    assert out.resolve().parent == OUT.resolve()
    for path in out.iterdir():
        path.unlink()
    out.rmdir()
    print(OUT / 'expressions.png', len(entries), 'atlases')


if __name__ == '__main__':
    main()

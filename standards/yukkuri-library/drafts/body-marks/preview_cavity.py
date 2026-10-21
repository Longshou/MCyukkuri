"""Offline preview of the exported cavity profile and core, without launching the game."""
from pathlib import Path
import argparse
import json
import subprocess

import numpy as np
from PIL import Image
import preview as source

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    work = ROOT / '.local/body-cavity-runtime'
    work.mkdir(parents=True, exist_ok=True)
    texture = ROOT / 'src/main/resources/assets/mcyukkuri/textures/entity/reimu/neutral.png'
    # Use the same selected neutral texture as the existing material preview.
    if not texture.exists():
        model = json.loads((ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri/reimu.json').read_text(encoding='utf-8'))
        raise FileNotFoundError(f'Missing selected neutral texture: {texture}; model keys={list(model)[:5]}')
    raw = work / 'reimu.rgba'
    raw.write_bytes(Image.open(texture).convert('RGBA').tobytes())
    jobs = []
    labels = [('whole', '整只比例', '真实皮面落点拟合大创口', 'intact', 11.7, 8.5, .36),
              ('close', '中枢近看', '面皮断面、腔壁与体内唯一中枢', 'intact', 4.25, 4.2, .36),
              ('side', '侧向遮挡', '腔壁遮住部分中央中枢', 'intact', 4.25, 4.2, 1.0),
              ('broken', '中枢裂损', '沿用原稿裂损材质', 'broken', 4.25, 4.2, .36),
              ('empty', '取出后空腔', 'CoreTaken 后不再绘制核心', 'empty', 4.25, 4.2, .36)]
    for key, _, _, state, span, cy, az in labels:
        jobs.append(dict(model='reimu', width=720, height=660, az=az, el=.12, span=span, cy=cy,
                         cx=0 if key == 'whole' else 1.8, cz=0 if key == 'whole' else -2,
                         cavityRuntime=state, texture=str(raw), out=str(work / key)))
    job_file = work / 'jobs.json'
    job_file.write_text(json.dumps(jobs), encoding='utf-8')
    subprocess.run([args.node, str(HERE / 'surface.cjs'), str(job_file)], check=True)
    cards = []
    for (key, title, subtitle, *_), job in zip(labels, jobs):
        rgba = np.fromfile(work / f'{key}.rgba', dtype=np.uint8).reshape(job['height'], job['width'], 4)
        cards.append((title, subtitle, Image.fromarray(rgba)))
    output = ROOT / '.local/previews/body-core-runtime'
    output.mkdir(parents=True, exist_ok=True)
    source.sheet('当前选用重伤创腔', '读取当前导出的开口、腔壁层级与中枢网格，按真实皮面离线拟合。', cards, 3,
                 footer='离线静态预览 / 像素裁切对应开口轮廓 / Java 动态蒙皮、三角裁切和游戏深度遮挡待试玩').save(output / 'current.png')
    (output / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>当前重伤创腔</title>'
                                      '<body style="margin:0;background:#f4f0e6"><img style="width:100%" src="current.png" alt="当前重伤创腔">', encoding='utf-8')
    print(output / 'current.png')


if __name__ == '__main__':
    main()

"""Offline current soft pet-nest preview; no game or browser required."""
from pathlib import Path
from render_preview import Source, render

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / '.local/previews/pet-cushion'

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    picture, _ = render(Source(ROOT), [('yukkuri_nest', 99, 0, 0, 0, 1)],
                        az=220, el=32, ppu=28, size=(720,480), bg=(.93,.94,.91,1))
    picture.save(OUT/'current.png')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>浅软布窝</title><h1>浅软布窝</h1><p>浅凹窝心、草莓色软布、奶油色绗缝与低入口；低矮轮廓对应真实碰撞。</p><img src="current.png" alt="当前浅软布窝">',encoding='utf-8')

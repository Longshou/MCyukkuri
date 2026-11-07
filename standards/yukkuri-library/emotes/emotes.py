"""Emote symbols: twelve 16x16 pixel icons shown above a yukkuri's head (project design, pending user review).

Each icon is hand-drawn below as 16 rows of 16 characters: `.` is transparent, `k` is an explicit outline pixel and
every other letter is a flat fill from the icon's palette. The 1 px dark outline is added around the fills
(8-neighbour), so the rows only carry the fill shapes. Direction follows the image liaison's concept sheet
(`.local/generated-images/emotes/sheet.png`), redrawn at 16 px rather than downscaled.

Outputs: `assembled/<id>.png` (one icon), `assembled/atlas.png` (4x3 cells of 16 px, row-major in ICONS order) and
`assembled/emotes.json` (id, meaning, suggested use, atlas cell); the atlas and the registry are copied to the runtime
folder `textures/emote/`. Generated files are never edited by hand.

    python standards/yukkuri-library/emotes/emotes.py [--check]
"""
from pathlib import Path
import argparse, io, json
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = HERE/'assembled'
RUNTIME = ROOT/'src/main/resources/assets/mcyukkuri/textures/emote'
SIZE, COLUMNS = 16, 4
OUTLINE = (27, 21, 48)

ICONS = [
    dict(id='sweat', meaning='冷汗、慌张、尴尬', use='被拒绝、搬不动、做错事、受惊后松一口气',
         palette=dict(B=(58, 155, 240), L=(198, 236, 255), D=(28, 104, 196)),
         rows=['................',
               '................',
               '.......BB.......',
               '.......BB.......',
               '......BBBB......',
               '......BBBB......',
               '.....BBBBBB.....',
               '.....BLBBBB.....',
               '....BLBBBBBB....',
               '....BLBBBBBB....',
               '....BBBBBBDB....',
               '....BBBBBBDB....',
               '.....BBBBDB.....',
               '......BBBB......',
               '................',
               '................']),
    dict(id='anger', meaning='生气、不满', use='被抢食物、被打扰、争地盘、被冒犯',
         palette=dict(R=(238, 43, 43), D=(168, 20, 28)),
         rows=['................',
               '.....R....R.....',
               '....RR....RR....',
               '....RR....RR....',
               '.RRRRD....DRRRR.',
               '.RRRDD....DDRRR.',
               '................',
               '................',
               '................',
               '................',
               '.RRRDD....DDRRR.',
               '.RRRRD....DRRRR.',
               '....RR....RR....',
               '....RR....RR....',
               '.....R....R.....',
               '................']),
    dict(id='zzz', meaning='睡觉、困倦', use='入睡、打盹、冬眠',
         palette=dict(W=(255, 255, 255), P=(160, 206, 255)),
         rows=['................',
               '................',
               '........WWWWWW..',
               '...........WW...',
               '..........WW....',
               '.........WW.....',
               '........WW......',
               '........WWWWWW..',
               '................',
               '................',
               '..PPPP..........',
               '....P...........',
               '...P............',
               '..PPPP..........',
               '................',
               '................']),
    dict(id='exclaim', meaning='吃惊、发现', use='发现食物或天敌、受惊、注意到玩家',
         palette=dict(Y=(255, 214, 58), O=(240, 90, 30)),
         rows=['................',
               '................',
               '......YYYO......',
               '......YYYO......',
               '......YYYO......',
               '......YYYO......',
               '......YYYO......',
               '.......YO.......',
               '.......YO.......',
               '................',
               '................',
               '......YYYO......',
               '......YYYO......',
               '......OOOO......',
               '................',
               '................']),
    dict(id='question', meaning='疑惑、不明白', use='找不到东西、目标消失、看见陌生物品',
         palette=dict(C=(64, 214, 240), D=(30, 128, 214)),
         rows=['................',
               '................',
               '......CCCC......',
               '.....CCCCCD.....',
               '....CC....CD....',
               '....CC....CD....',
               '..........CD....',
               '.........CD.....',
               '........CD......',
               '.......CD.......',
               '................',
               '................',
               '.......CD.......',
               '.......DD.......',
               '................',
               '................']),
    dict(id='note', meaning='开心、哼歌', use='吃饱、玩耍、散步、唱歌',
         palette=dict(P=(255, 92, 138), O=(255, 140, 42), M=(96, 62, 128)),
         rows=['................',
               '................',
               '.........OOOO...',
               '.....OOOOOOOO...',
               '.....OOOO...M...',
               '.....M......M...',
               '.....M......M...',
               '.....M......M...',
               '.....M....PPM...',
               '.....M...PPPP...',
               '...PPM...PPPP...',
               '..PPPP....PP....',
               '..PPPP..........',
               '...PP...........',
               '................',
               '................']),
    dict(id='heart', meaning='喜欢、亲近、恋爱', use='贴贴、求偶、亲子相聚、收到礼物',
         palette=dict(R=(238, 43, 59), W=(255, 255, 255), D=(168, 18, 42)),
         rows=['................',
               '................',
               '................',
               '...RRR....RRR...',
               '..RRWRR..RRRRR..',
               '..RWRRRRRRRRRR..',
               '..RRRRRRRRRRRR..',
               '..RRRRRRRRRRRD..',
               '...RRRRRRRRRD...',
               '....RRRRRRRD....',
               '.....RRRRRD.....',
               '......RRRD......',
               '.......RD.......',
               '................',
               '................',
               '................']),
    dict(id='gloom', meaning='沮丧、失落', use='失去家或孩子、被拒绝求偶、挨饿',
         palette=dict(G=(98, 88, 214), V=(168, 158, 245)),
         rows=['................',
               '................',
               '..GG...GG...GG..',
               '..GG...GG...GG..',
               '..GG...GG...GG..',
               '..GG...GG...GG..',
               '..GG...GG...GG..',
               '..VV...GG...VV..',
               '..V....GG...VV..',
               '.......GG....V..',
               '.......VV.......',
               '.......VV.......',
               '.......V........',
               '................',
               '................',
               '................']),
    dict(id='sparkle', meaning='得意、满足、发现宝物', use='收到宝物、梳洗干净、出生、成长',
         palette=dict(Y=(255, 216, 58), W=(255, 251, 224), O=(240, 160, 32)),
         rows=['................',
               '................',
               '.......YY.......',
               '.......YY.......',
               '.......YY.......',
               '......YYYO......',
               '.....YYWWYO.....',
               '..YYYYWWWWYYYO..',
               '..YYYYWWWWYOOO..',
               '.....YYWWYO.....',
               '......YYOO......',
               '.......YO.......',
               '.......YO.......',
               '.......OO.......',
               '................',
               '................']),
    dict(id='steam', meaning='气呼呼、闹别扭', use='生气跺脚、抗议、拒绝后赌气',
         palette=dict(W=(255, 255, 255), S=(196, 206, 222)),
         rows=['................',
               '................',
               '......WWW.......',
               '...WW.WWWWW.....',
               '..WWWWWWWWWW....',
               '..WWWWWWWWWW.W..',
               '..WWWWWWWWWWWW..',
               '...WWWWWWWWWWW..',
               '..WWWWWWWWWWWW..',
               '..WWWWWWWWWWWS..',
               '..SWWWWWWWWWSS..',
               '...SSWWSSWSS....',
               '....SS..SS......',
               '................',
               '................',
               '................']),
    dict(id='shiver', meaning='发冷、害怕发抖', use='受冻、淋雨、看见天敌、挨骂',
         palette=dict(L=(143, 212, 255), B=(61, 143, 224)),
         rows=['................',
               '................',
               '.....LB....LB...',
               '....LB....LB....',
               '...LB....LB.....',
               '..LB....LB......',
               '...LB....LB.....',
               '....LB....LB....',
               '.....LB....LB...',
               '....LB....LB....',
               '...LB....LB.....',
               '..LB....LB......',
               '...LB....LB.....',
               '....LB....LB....',
               '................',
               '................']),
    dict(id='idea', meaning='想到办法', use='找到绕过手段、想到用帽子或木棍、发现新路线',
         palette=dict(Y=(255, 216, 58), W=(255, 251, 230), G=(185, 190, 200)),
         rows=['................',
               '................',
               '......YYYY......',
               '....YYYYYYYY....',
               '...YWWYYYYYYY...',
               '...YWYYYYYYYY...',
               '...YYYYYYYYYY...',
               '...YYYYYYYYYY...',
               '....YYYYYYYY....',
               '.....YYYYYY.....',
               '......YYYY......',
               '.....GGGGGG.....',
               '.....kkkkkk.....',
               '......GGGG......',
               '................',
               '................']),
]


def draw(icon):
    rows, palette = icon['rows'], icon['palette']
    assert len(rows) == SIZE and all(len(r) == SIZE for r in rows), icon['id']
    used = {c for r in rows for c in r} - {'.', 'k'}
    assert used == set(palette), (icon['id'], used, set(palette))
    assert 2 <= len(palette)+1 <= 4, icon['id']
    image = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    px = image.load()
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == 'k':
                px[x, y] = OUTLINE+(255,)
            elif c != '.':
                px[x, y] = palette[c]+(255,)
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = x+dx, y+dy
                        assert 0 <= nx < SIZE and 0 <= ny < SIZE, (icon['id'], 'outline leaves the cell', x, y)
                        if rows[ny][nx] == '.':
                            px[nx, ny] = OUTLINE+(255,)
    return image


def outputs():
    files = {}
    atlas = Image.new('RGBA', (SIZE*COLUMNS, SIZE*((len(ICONS)+COLUMNS-1)//COLUMNS)), (0, 0, 0, 0))
    registry = dict(format=1, cell_size=SIZE, columns=COLUMNS, rows=atlas.height//SIZE, emotes=[])
    for i, icon in enumerate(ICONS):
        image = draw(icon)
        col, row = i % COLUMNS, i//COLUMNS
        atlas.paste(image, (col*SIZE, row*SIZE))
        files[OUT/f"{icon['id']}.png"] = png(image)
        registry['emotes'].append(dict(id=icon['id'], meaning=icon['meaning'], use=icon['use'], cell=[col, row]))
    text = (json.dumps(registry, ensure_ascii=False, indent=2)+'\n').encode('utf-8')
    for folder in (OUT, RUNTIME):
        files[folder/'atlas.png'] = png(atlas)
        files[folder/'emotes.json'] = text
    return files


def png(image):
    buffer = io.BytesIO()
    image.save(buffer, 'PNG', optimize=True)
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--check', action='store_true', help='fail if any output differs from the drawn rows')
    args = parser.parse_args()
    stale = []
    for path, data in outputs().items():
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        raise SystemExit('stale emote outputs:\n'+'\n'.join(str(p.relative_to(ROOT)) for p in stale))


if __name__ == '__main__':
    main()

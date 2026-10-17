"""Before/after preview sheets for the nests (pet bed, wild leaf nests, predator nest) and goods, with adult and child Reimu for scale.
Output: .local/previews/props-v2/. Usage: python preview_nests.py [pet wild stages predator goods]
BEFORE needs MCYUKKURI_BEFORE = path of a checkout of the previous revision."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from preview_nests_lib import *

SZ = (520, 400)
CH = 'yuk:reimu:neutral'
CHD = 'yuk:reimu_deibu:neutral'


def pet_bed():
    rows = []
    for src, tag, fl in ((BEFORE, '前', 1.9), (AFTER, '后', 2.0)):
        r = []
        for mode, lab in (('none', '空垫'), ('adult', '成体 1.0'), ('big', '成体 1.2'), ('child', '子体 0.5')):
            items = [('yukkuri_nest', 8, 0, 0, 0, 1)] + occupants(fl, mode)
            r.append(tile(src, items, f'{tag} 宠物软垫 {lab}', size=SZ, ppu=16))
        r.append(tile(src, [('yukkuri_nest', 8, 0, 0, 0, 1), (CHD, 8, 0, fl, 0, 1.5, 0)], f'{tag} 宠物软垫 灵胖 1.5', size=SZ, ppu=16))
        rows.append(r)
    sheet(rows, 'pet_bed.png', '人造垫：商店宠物软垫（前＝当前主线，后＝本次）。成体宽12.8、个体最大1.2倍=15.4、灵胖1.5倍=19.2；后版内部宽16.4，外径24', 5, size=SZ)


def shared_mask(shared, bed):
    """Same layer mask as client/ShelterRenderer.sharedLeafLayers; bed = (x, z), north is z - 1."""
    x, z = bed
    n, e, s, w = (x, z - 1) not in shared, (x + 1, z) not in shared, (x, z + 1) not in shared, (x - 1, z) not in shared
    m = 1 | (2 if n else 0) | (4 if e else 0) | (8 if s else 0) | (16 if w else 0)
    for flag, bit in ((n and e, 5), (s and e, 6), (s and w, 7), (n and w, 8), (n and not w, 9), (n and not e, 10), (e and not n, 11),
                      (e and not s, 12), (s and not e, 13), (s and not w, 14), (w and not s, 15), (w and not n, 16)):
        if flag:
            m |= 1 << bit
    return m


def wild_beds():
    rows = []
    single = shared_mask({(0, 0)}, (0, 0))
    ground = ('ground:17', 0, 0, 0, 0, 1)
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        r = []
        mask = 31 if src is BEFORE else single
        for mode, lab in (('none', '空'), ('adult', '成体 1.0'), ('child', '子体 0.5')):
            r.append(tile(src, [ground, ('forest_burrow', ('mask', mask), 0, 0, 0, 1)] + occupants(0, mode), f'{tag} 单床叶巢 {lab}', size=SZ, ppu=19))
        cells = [(0, 0), (1, 0), (0, 1), (1, 1)]
        fam = [('forest_burrow', ('mask', 31 if src is BEFORE else shared_mask(set(cells), c)), (c[0] - .5) * 16, 0, (c[1] - .5) * 16, 1) for c in cells]
        occ = [(CH, 8, sx, 0, sz, s, 180) for sx, sz, s in ((-8, -8, 1.0), (8, -8, .5), (-8, 8, .5), (8, 8, 1.0))]
        r.append(tile(src, [('ground:24', 0, 0, 0, 0, 1)] + fam + occ, f'{tag} 家族共巢 2x2（亲2+子2）', size=SZ, ppu=11))
        rows.append(r)
    sheet(rows, 'wild_nests.png', '野外油库里自建：叶床（forest_burrow）。前面(+z)为入口侧，沿口较低；后/侧较高；相邻床位连成一圈', 4, size=SZ)


def stages():
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        r = [tile(src, [('ground:8', 0, 0, 0, 0, 1), ('bedding_bundle', 0, 0, 0, 0, 1)], f'{tag} 携带的一份垫料', size=SZ, ppu=36)]
        for m, lab in ((1, '阶段1 零散几片叶'), (3, '阶段2 粗略成圈'), (7, '阶段3 成巢')):
            r.append(tile(src, [('ground:11', 0, 0, 0, 0, 1), ('personal_leaf_nest', ('mask', m), 0, 0, 0, 1)] + occupants(0, 'child'), f'{tag} 个人小叶巢 {lab} + 子体0.5', size=SZ, ppu=26))
        r.append(tile(src, [('ground:11', 0, 0, 0, 0, 1), ('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1), (CH, 8, 0, 0, 0, .8, 180)], f'{tag} 个人小叶巢 + 子体0.8', size=SZ, ppu=26))
        rows.append(r)
    sheet(rows, 'wild_stages.png', '野外自建巢的建造阶段（搬运 -> 零散几片叶 -> 粗略成圈 -> 成巢）；子体 0.5 / 0.8', 5, size=SZ)


def predator():
    rows = []
    r = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        r.append(tile(src, [('predator_nest', 8, 0, 0, 0, 1)], f'{tag} 捕食种巢 空', size=SZ, ppu=16))
        r.append(tile(src, [('predator_nest', 8, 0, 0, 0, 1), ('yuk:remilia:neutral', 8, 0, 2.4, 0, 1.0, 0)], f'{tag} 捕食种巢 + remilia 成体', size=SZ, ppu=16))
    r.append(tile(AFTER, [('predator_nest', 8, 0, 0, 0, 1), ('yuk:remilia:neutral', 8, 0, 2.4, 0, .5, 0)], '后 捕食种巢 + 子体 0.5', size=SZ, ppu=16))
    rows.append(r)
    sheet(rows, 'predator_nest.png', '野外自建：捕食种枝巢（predator_nest，方块碰撞高 2.4，站立面 y=2.4）', 5, size=SZ)


def goods():
    names = [('oil_grain_budget', 0), ('oil_grain_regular', 0), ('oil_grain_premium', 0), ('food_scrap', 8), ('yukkuri_waste', 1), ('leaked_filling', 8),
             ('ceramic_toilet', 0), ('covered_toilet', 0), ('newspaper_mat', 0), ('soil_mat', 0), ('yukkuri_carrier', 0), ('wash_cloth', 0), ('yarn_ball', 0),
             ('spicy_grain', 0), ('food_mash_serving', 0), ('yukkuri_toilet', 0)]
    rows = []
    SZ2 = (330, 260)
    for k in range(0, len(names), 4):
        r = []
        for n, u in names[k:k + 4]:
            r.append(tile(BEFORE, [(n, u, 0, 0, 0, 1)], f'前 {n}', size=SZ2, ppu=28))
            r.append(tile(AFTER, [(n, u, 0, 0, 0, 1)], f'后 {n}', size=SZ2, ppu=28))
        rows.append(r)
    sheet(rows, 'goods_noise.png', '周边道具：色板平涂 -> 同色板 16px 像素噪点材质（1 texel = 1/16 格，与原版一致）', 8, size=SZ2)


if __name__ == '__main__':
    which = sys.argv[1:] or ['pet', 'wild', 'stages', 'predator', 'goods']
    for w in which:
        {'pet': pet_bed, 'wild': wild_beds, 'stages': stages, 'predator': predator, 'goods': goods}[w]()
        print('done', w)

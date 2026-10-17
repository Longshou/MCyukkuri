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


def wild_beds():
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        r = []
        for mode, lab in (('none', '空'), ('adult', '成体 1.0'), ('big', '成体 1.2'), ('child', '子体 0.5')):
            r.append(tile(src, [('forest_burrow', ('mask', 31), 0, 0, 0, 1)] + occupants(0, mode), f'{tag} 单床叶巢（四边有沿）{lab}', size=SZ, ppu=16))
        fam = [('forest_burrow', ('mask', m), sx, 0, sz, 1) for sx, sz, m in ((-8, -8, 19), (8, -8, 7), (-8, 8, 25), (8, 8, 13))]
        occ = [(CH, 8, sx, 0, sz, s, yaw) for sx, sz, s, yaw in ((-8, -8, 1.0, 0), (8, -8, .5, 0), (-8, 8, .5, 0), (8, 8, 1.0, 0))]
        r.append(tile(src, fam + occ, f'{tag} 家族共巢 2x2（亲2+子2）', size=SZ, ppu=11))
        rows.append(r)
    sheet(rows, 'wild_nests.png', '野外油库里自建：叶床（forest_burrow，共巢床位，16x16 格，沿口在外侧方块空间）', 5, size=SZ)


def stages():
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        r = [tile(src, [('bedding_bundle', 0, 0, 0, 0, 1)], f'{tag} 携带的一份垫料', size=SZ, ppu=36)]
        for m, lab in ((1, '阶段1 底垫'), (3, '阶段2 铺开'), (7, '阶段3 压沿完成')):
            r.append(tile(src, [('personal_leaf_nest', ('mask', m), 0, 0, 0, 1)] + occupants(0, 'child'), f'{tag} 个人小叶巢 {lab} + 子体0.5', size=SZ, ppu=22))
        r.append(tile(src, [('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1), (CH, 8, 0, 0, 0, .8, 0)], f'{tag} 个人小叶巢 + 子体0.8', size=SZ, ppu=22))
        rows.append(r)
    sheet(rows, 'wild_stages.png', '野外自建巢的建造阶段（搬运 -> 底垫 -> 铺开 -> 压沿）；子体 0.5 / 0.8', 5, size=SZ)


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

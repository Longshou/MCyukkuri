# 仰卧固定台

方块 `mcyukkuri:restraint_frame` 的原生方块模型、贴图与皮带贴合数据。制作源为 `build.py`，从仓库根目录运行 `<py> art/props/restraint-table/build.py` 写出运行资源与 `manifest.json` 指纹，加 `--check` 只比较；两种情况都检查同平面重叠面（z-fighting）与全部形态的合拢皮带。`<py> art/props/restraint-table/preview.py` 出离线预览到 `.local/previews/restraint-table/`。物品模型、物品栏图标与配方仍由[接触器具制作源](../../../standards/yukkuri-library/drafts/operation-tools/README.md)写出，手里显示本方块模型。

| 部分 | 外形 |
| --- | --- |
| 台面 | 16×16、2 像素厚的深色木板（四块南北向木板），台面高 5 像素，与碰撞和躺卧高度一致 |
| 台脚与护角 | 四根 3×3 包铁短脚，在碰撞底座范围内；四角铁护角 |
| 皮带 | 两条 2 像素宽红褐皮带，中线 z = 4 与 z = 12；西侧固定端压在侧面铁板下，东侧侧面是铁带扣 |
| 空台 | 皮带从西侧铁板翻过台边横放在台面上，带孔的皮带头停在东边缘前，未扣上 |

贴图为 `textures/block/restraint_table_{top,side,leg,iron,strap}.png`，取接触器具的同一色阶（`icons.py` 的 `WOOD`、`IRON`、`STRAP`）。`models/block/restraint_frame.json` 是空台；`restraint_frame_occupied.json` 只留铁板、带扣和台体，供有个体时换用。

## 合拢皮带

有个体时皮带要按个体大小贴身绕过，静态模型做不到，由客户端渲染画出（尚未接入；接入前有个体时仍显示空台模型）。个体躺向固定为头朝南或北，身体左右沿东西方向。`restraint_table/strap_fits.json` 按运行模型 ID（不显示饰物时用 `<ID>_bare`）给两条皮带线：`y` 为模型高度，`width` 为贴身宽度，`hull` 为皮带宽度范围内可见网格截面的凸包 `(x, z)`，单位为模型单位、仰卧转动之前。两条线避开脸：一条在眉上刘海，一条在嘴下身体下沿。

`build.closed_straps` 是参考做法，预览与检查都用它：截面随身体按渲染变换放到台上并外扩 0.12 像素，在皮带所在竖直面里与西侧铁板出口、东侧带扣上沿和两条台边一起取凸包，从带扣经上方到铁板的一段即皮带内表面；离开身体的部分回到固定点的 z，宽度从固定点的 2 像素过渡到身体上的贴身宽度（下限 0.6 像素）。检查覆盖全部运行模型（含与不含饰物）、0.1 到 1.5 倍和两个躺向。

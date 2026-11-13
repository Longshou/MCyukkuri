# 街边垃圾桶

原创镀锌铁皮带盖垃圾桶，原生方块模型（元素与 16 像素贴图），颜色取共用色板 `art/props/palette.json` 的 `steel` 明暗阶。制作源为 `build.py`，从仓库根目录运行 `<py> art/props/street-bin/build.py` 写出运行资源与 `manifest.json` 指纹，加 `--check` 只比较。

| 状态 | 模型 | 外形 |
| --- | --- | --- |
| 立着 `tipped=false` | `models/block/garbage_bin.json` | 10×12×10 的桶身、两道外凸箍、略宽的盖子与提手，高 15 像素 |
| 倒着 `tipped=true` | `models/block/garbage_bin_tipped.json` | 桶身横躺，桶口（深色内里露出垃圾）朝 `facing`，盖子竖靠在桶旁，桶口前散两团纸 |

贴图为 `textures/block/garbage_bin_{side,band,lid,bottom,mouth,litter}.png`：桶身竖向瓦楞、箍与盖沿、同心压纹盖面、桶底、桶口与散落纸团。碰撞在 `human/GarbageBinBlock`，与模型同一组长方体随朝向旋转。方块、物品模型、战利品表与合成表（铁锭 ×7 加铁粒）都由 `build.py` 写出。玩法见[翻垃圾](../../../docs/FEATURES.md#油库里的身份)。

# 先天不足形态

本包制作灵梦与魔理沙的稀疏毛发、面部不对称和豆形细长形态。先天形态与种族、命名亚种、后天伤口分别登记；多毛灵梦不与豆形形态叠加。身体保持无腿、无直立胴体的油库里轮廓。

制作参数在 [recipes.json](recipes.json)，豆形脸部与头发适配分别由 [bean_face.py](bean_face.py) 和 [bean_hair.py](bean_hair.py) 生成；包内快照及来源指纹见 [hair-fit-manifest.json](hair-fit-manifest.json)。本包读取当前游戏模型与动作骨架，生成自己的 `assembled/` 和接入快照，不重写基础模型及行走曲线。游戏导出入口为 `scripts/export_subspecies.py`；豆形定向导出使用 `python scripts/export_bean_assets.py`。

各形态的外观依据见[参考核读](REFERENCES.md)，具体参数见[稀毛与歪脸灵梦](rounded-reimu/README.md)、[稀毛与歪脸魔理沙](rounded-marisa/README.md)、[豆形灵梦](rounded-reimu/README.md)及[豆形魔理沙](rounded-marisa/README.md)。接入快照和来源指纹见 [hair-fit-manifest.json](hair-fit-manifest.json)。

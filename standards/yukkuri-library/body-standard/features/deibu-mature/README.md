# 灵胖成熟面容候选

此面容待用户确认。保留1.5倍圆润体型，修改本角色14张表情图集中的眼睛、眉、嘴与脸颊区域；几何、骨架和动作指纹均不变。

`recipe.json` 保存颜色和方向，`author.py` 按既有UV绘制，基础表情和冻结输入保持原样。`candidate.json` 记录本轮指纹与像素变动范围。重建：`python standards/yukkuri-library/body-standard/build.py --character reimu_deibu`（需先完整构建一次以生成 `previews/current/preview-data.json`）。

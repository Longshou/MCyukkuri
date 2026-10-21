# 爱丽丝基础造型

本包在圆润身体和骨架上替换爱丽丝的头发、发箍与虹膜。参数、认可状态与指纹见 [candidate.json](candidate.json)。八景原作和参考资料位于 `wiki/art-references/`，不进入运行资源。

造型依据为浅柠檬金短发、红色白边发箍和蓝色虹膜。头发为七束错落刘海、内收的波波侧发、分束后发；红色发箍位于发际，前后缘分别使用不同尺寸的白蕾丝。配色与部件尺寸以登记和制作源为准。

睁眼且非灰眼状态的虹膜为蓝色，高光保持白色；闭眼、空白眼和灰眼不变。蓝眼位于爱丽丝专用图集，不改共享表情部件或其他角色。发箍部件使用 `alice_band_`、`alice_lace_` 前缀；徽章挂点为 `alice_band_red_2`。头发部件名含 `hair` 或 `bang`，供秃发效果按左右隐藏。

制作与装配入口为 [author.py](author.py) 和 [sync_inputs.py](sync_inputs.py)；模型构建使用 `python standards/yukkuri-library/body-standard/build.py --character alice`。游戏贴图派生同步使用 `python scripts/export_alice_appearance.py`，只核对时追加 `--check`。预览入口为 [preview.py](preview.py)，表情预览为 [preview_expressions.py](preview_expressions.py)，输出位于 `.local/previews/alice/`。

# 稀有种与变体派生包（rare-forms）

妖梦、红眼妖梦、长尾橙、圆尾橙、被炉魔理沙五种形态，以及妖梦叼的木棍道具「觀樓劍」。依据和游戏规则见 [docs/RARE_FORMS.md](../../../../docs/RARE_FORMS.md)。

- `recipe.json`：制作源。包括配色映射（妖梦的头发和发带）、蕾丝收起、蝴蝶结方块、红眼虹膜、尾巴变换、被炉方块和调色、徽章位置，以及觀樓劍的尺寸与配色。
- `build.py`：以基底种族的选定运行模型（`art/source/rounded-runtime/<基底>/runtime.model.json`）和游戏贴图为输入，生成 `assembled/<形态>/` 下的 `runtime.model.json`、全部表情贴图和 `derived.json`（记录做了哪些操作及指纹）。派生时保持基底的顶点数和部件顺序。
- 修改造型：改 `recipe.json`，然后依次运行
  ```
  python standards/yukkuri-library/subspecies/rare-forms/build.py
  python scripts/export_rare_forms.py
  ```
  两个命令都可以加 `--check` 只做核对。基底的选定运行模型更新后，也要重新跑这两步。
- 本机预览：`.local/previews/youmu/`（不入库）。

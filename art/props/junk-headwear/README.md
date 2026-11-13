# 垃圾头饰

失去饰物的油库里戴的三种垃圾头饰（纸盒、瓶盖、冰淇淋筒）和通用物品 `junk_headwear` 的图标。玩法见[功能说明](../../../docs/FEATURES.md)的垃圾头饰条目。全部为本项目原创，无外部参考图。

- 制作源 [build.py](build.py)：用照料道具网格工具 `standards/yukkuri-library/drafts/care-props/kit.py` 制作三件低多边形道具，各自带手绘贴图（每单位 2 像素）。原点在头顶接触点，+Y 向上，正面朝 -Z，16 单位为一格。三件都要一眼看出是垃圾，并且比原饰物寒酸：
  - 纸盒：倒扣的小点心纸盒，右前角被压塌，盒底（现在朝上）微塌，接缝上贴着一条起了角的旧封箱胶带。正面印着褪色的蓝标签，一角已磨掉，侧面有一道印刷细线和水渍，开口边发黑。左侧一片撕破的旧盒盖向外翘起。
  - 瓶盖：倒扣的皇冠瓶盖，十四道压褶，一侧被开瓶器撬弯，有一道压褶外翻。红漆褪色，压褶棱上的漆已磨掉，带锈斑，内侧是旧垫片。
  - 冰淇淋筒：倒扣的威化蛋筒，筒尖被压弯，开口处缺一口。开口边上留着干掉的草莓冰淇淋，还有当初正拿时流下的几道痕（现在朝向筒尖），并有裂纹和污迹。
- 座位：游戏在佩戴者的五官骨（`features_squash`，戴帽形态中是帽骨的父骨）坐标系里画道具，再套用道具网格 `anchors` 里以运行模型命名的座位（位置、旋转、缩放；`PropModels.Anchor`）。构建时对每个运行模型先拼入位置适配，再按 `living.json` 与 `subspecies.json` 隐去饰物部件：缩放取裸头半宽与普通体 6.4 之比（限 0.3–1），再套用道具自己的歪戴角度，最后压低到最低点陷入头发 0.2 单位（按缩放）。新增或修改运行模型后须重新构建。`--report` 逐形态打印头顶高度、座位、接触间隙和悬空点数。遗体与活体使用同一座位。
- 物品图标：16×16 原版风格，画法同[照料补给物品图标](../supply-icons/README.md)，内容是三件垃圾：左边是贴着封箱胶带、印蓝标签的纸盒，右上斜放着开口朝外的空蛋筒，右下是红漆白字的瓶盖。
- 输出：`yukkuri_props/junk_<外观>.json` 与 `junk-index.json`，`textures/props/junk_<外观>.png`，`textures/item/junk_headwear.png`，`models/item/junk_headwear.json`。可编辑模型在 `assembled/`，指纹记录在 [provenance.json](provenance.json)。
- 命令：`<py> art/props/junk-headwear/build.py [--check] [--report]`，也可经 `scripts/export.py --changed` 运行。离线审图用 `<py> art/props/junk-headwear/sheets.py [forms|hero|motion|props|icon]`，输出到 `.local/previews/junk-headwear/`，不入库。

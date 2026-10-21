# 家油 · 魔理沙与灵梦

造型依据见 [REFERENCES.md](REFERENCES.md)，尺寸和装配参数见 [config.json](config.json)，来源指纹见 [assembled/provenance.json](assembled/provenance.json)。制作源为 `reference_design.py`、`mesh_geometry.py`、`lifecycle.js` 和 `viewer.js`；`build.py` 生成 `assembled/` 模型及 `index.html` 预览。

身体设计尺寸为宽 4.32、高 3.82、深 2.93 模型单位，最宽截面位于离地 1.12 单位处。身体是共享顶点的连续三角面，共 96 顶点、188 三角面；前部在离地 0.35 以下收拢，保留低多边形折面与落地圆角。脸部承托宽度覆盖眼睛和腮红。

头发沿卵形宽度和深度逐层收拢，连接圆顶、太阳穴与后脑。后发下缘距地面灵梦 1.84、魔理沙 1.72 单位，侧后转角略短以露出下腹。两侧发束和饰物前移、外移以露出短尾；魔理沙帽下发冠不超过 3.50 单位。两种家油共享身体网格，油菊使用贴合后腹的独立曲面；灵梦蝴蝶结外移，飘带按后发深度贴合并保留纵向 UV。

家油魔理沙使用左侧单辫和另一侧短侧发，夹取、搬运、放下由辫梢挂点完成；家油灵梦使用两束短发夹取。佩尼佩尼按身体宽度 4.32 与标准身体宽度 12 的比例 0.36 缩放，根部外露并与腹面相交；麻姆麻姆有独立挂点、后腹斜率和 0.10 单位行程，收回时埋入身体，伸出后仅露贴面标记。

`HEAD_OFFSET`、`HAT_OFFSET`、`RUMP_OFFSET`、`PENI_*` 和 `MAMU_*` 定义挂接与伸缩；脸部以 `FACE_ANCHOR` 为中心，`FACE_SCALE=0.33`。常态两个伸缩部件完全收回，切换观察对象、生活片段、重播和恢复场景会清除伸出状态；手动“收回”仍播放收回动画。控制只允许伸出一个部件，暂停生活片段时仍可操作。

预览是独立渲染器和生态原型，尚未接入 Minecraft 实体、服务器 AI、游戏存档或多人同步。多对象生活路线由 JavaScript 驱动，不烘焙为单体动画；普通宠物入室不触发家油分类，普通种茎生和胎生不调用此模块。`.geo.json` 使用旧式 `poly_mesh` 交换格式，不能视作可直接运行的 Bedrock 或 GeckoLib 资源。

从仓库根目录运行 `python standards/yukkuri-library/ecology/house-yukkuri/build.py` 重建完整预览；只更新角色可用 `--characters-only`。`validate.py`、`verify-motion.cjs` 和 `verify-anatomy.cjs` 对应几何及动作数据检查。编辑源文件后重新构建，不直接改生成的 `assembled/` 和 `index.html`。

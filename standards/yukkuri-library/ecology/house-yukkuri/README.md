# 家油 · 魔理沙与灵梦

[打开三维预览](index.html) · [参考核读](REFERENCES.md) · [设计参数](config.json)

家油保留较高的卵形体态，身体使用 10 个方块，以旋转斜面连接底缘和收窄的头顶，消除多层台阶。头发沿卵形适配，五官、辫子、帽子和蝴蝶结仍保留标准形状、UV 与贴图。已恢复油臀、油菊和七个标准伸缩部件方块。

魔理沙由 1,687 个方块降至 **83 个**，灵梦由 1,703 个降至 **107 个**，对应 498 / 642 个四边面（包含内部与透明面），合计减少约 94%。两角色使用 11 种统一表情；逐像素对比确认与标准导出相同。上一版的大圆眼、自绘嘴形、短侧发和细分饰品已撤下。

预览提供双角色、单角色、普通成体对比与巢卵观察，以及五种生活片段。支持旋转、缩放、拖动时间、暂停和家具显隐。孵化后幼体保留独立身份，移除巢不会删除已经出生的幼体。

这些是**美术资产与浏览器生态原型**，尚未接入 Minecraft 实体、服务器 AI、游戏存档和多人同步；18 秒、三枚卵是演示安排。普通宠物进入室内不会触发家油分类，普通种茎生和胎生也不调用本模块。

## 编辑与重建

家油专用身体和装配参数在 `reference_design.py`；其余几何通过角色的组件清单从当前标准导出中读取，不保存另一套饰品或表情源。`HEAD_OFFSET`、`FACE_OFFSET`、`RUMP_OFFSET` 与 `PENI_OFFSET` 控制挂接位置，标准组件缩放为 0.24。头发几何适配收尖轮廓，其余五官、饰品及伸缩部件保留标准形状与 UV；标准分层油臀合并成两个方块，保留完整油菊 UV。标准角色源仍由本库 `characters/`、`components/`、`motions/` 和 `expressions/` 维护。来源及设计文件哈希登记于 [provenance.json](assembled/provenance.json)。

先在角色源有修改时运行本库的 `build.py`；随后运行：

```powershell
python standards/yukkuri-library/ecology/house-yukkuri/build.py
python standards/yukkuri-library/ecology/house-yukkuri/validate.py
node standards/yukkuri-library/ecology/house-yukkuri/verify.cjs
```

`reference_design.py` 管理身体斜面、组件装配和动作适配；`config.json` 登记尺寸与 11 个统一表情 ID；`build.py` 从标准导出读取全部饰品和表情纹理，生成模型与道具。`lifecycle.js` 管理显式时间状态，`viewer.js` 管理预览交互。修改源文件后重新构建，生成的 `assembled/` 和 `index.html` 用于导出与查看。

## 导出

- [家油魔理沙](assembled/house_marisa.bbmodel)、[家油灵梦](assembled/house_reimu.bbmodel)：可编辑 BBModel、Bedrock 几何、动画、绑定骨架和 11 种标准表情图集；九骨骼为世界位移、主体、头部、五官、左右辫子、油臀与两个伸缩部件。走动与受惊动作复用素材库的上下形变、摇摆及世界位移曲线。两个伸缩部件复用统一控制器，默认收回、互斥切换，暂停生活片段时仍可操作；同时导出四段伸出／收回动画。幼体预览不响应成体部件控制。
- [棉絮巢](assembled/lint_nest.bbmodel)、[卵](assembled/house_egg.bbmodel)、[壳片](assembled/house_eggshell.bbmodel)与另外三种道具：同目录提供 PNG、BBModel、几何与绑定骨架。
- 生活片段的多对象路线与孵化事件由 JavaScript 驱动，未烘焙为单体动画；导入游戏需移植服务端状态和场景协调。单体模型动画已经导出。

坐标每格 16 单位、+Y 向上、−Z 为正面。浏览器和结构验证结果见 `verification.json`、`browser-verification.json`。未在 Blockbench 界面或 Minecraft 中做导入验收。


# 家油 · 魔理沙与灵梦

[打开三维预览](index.html) · [参考核读](REFERENCES.md) · [设计参数](config.json)

当前为卵形调整 v4，**造型待确认**。身体最宽处位于离地 1.45 单位、约 37% 身高处；底缘圆收，上部逐段收窄。主体采用八边截面填充与连续斜面，脸部只保留承托标准五官的浅平面。身体尺寸仍为宽 3.28、高 3.95、深 2.36 单位。

两种家油共用 71 个身体方块；魔理沙合计 144 个方块，灵梦合计 168 个，对应 864 / 1,008 个四边面（包含内部与透明面）。头发、五官、辫子、帽子、蝴蝶结、油臀与伸缩部件沿用 v3 的几何、UV 和挂接位置，11 种表情贴图及动作不变。为形成腹部与肩部的连续轮廓，身体分段数高于 v3。

预览提供双角色、单角色、普通成体对比与巢卵观察，以及五种生活片段。支持旋转、缩放、拖动时间、暂停和家具显隐。孵化后幼体保留独立身份，移除巢不会删除已经出生的幼体。

这些是**美术资产与浏览器生态原型**，尚未接入 Minecraft 实体、服务器 AI、游戏存档和多人同步；18 秒、三枚卵是演示安排。普通宠物进入室内不会触发家油分类，普通种茎生和胎生也不调用本模块。

## 编辑与重建

家油专用身体和装配参数在 `reference_design.py`。`BODY_PROFILE` 记录各高度的半宽、半深；斜向实体连接相邻截面，内收方块填充各段内部。其余几何通过角色的组件清单从当前标准导出中读取，不保存另一套饰品或表情源。`HEAD_OFFSET`、`FACE_OFFSET`、`RUMP_OFFSET` 与 `PENI_OFFSET` 控制挂接位置，标准组件缩放为 0.24。标准角色源仍由本库 `characters/`、`components/`、`motions/` 和 `expressions/` 维护。来源及设计文件哈希登记于 [provenance.json](assembled/provenance.json)。

仅调整家油造型时，以下命令只导出静态美术文件并刷新预览数据，保留既有道具和普通成体对照数据，不启动渲染器、服务器或浏览器：

```powershell
python standards/yukkuri-library/ecology/house-yukkuri/build.py --characters-only
```

标准角色源发生变化时，需要在指定环境先更新基础库，再重新导出完整场景。下列完整构建和验证命令仅供指定环境使用，本机不执行；浏览器验收由用户在允许的环境中操作：

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

坐标每格 16 单位、+Y 向上、−Z 为正面。v4 已完成静态导出；造型、浏览器表现、Blockbench 导入及 Minecraft 运行均待验收。`verification.json`、`browser-verification.json` 和本目录已有 PNG 截图属于 v3 历史证据，不能说明 v4 的效果；当前造型以 `index.html` 和 `assembled/` 为准。新的结构检查结果写入 `reports/current/verification.json`。

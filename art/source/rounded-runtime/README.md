# 圆润体型的 Minecraft 接入

2026-09-13 追加：稀毛与歪脸魔理沙另由[不足形态定向包](../../../standards/yukkuri-library/subspecies/deficient/rounded-marisa/README.md)采用圆润身体；主体与贴图保存在原亚种接入目录，本包挂件索引同时管理其两套帽饰。下方“六种不足油沿用”属于追加修正前的范围，当前仅两种灵梦不足形态和两种豆油继续保留各自身体。

2026-09-13，按“实装更新并检查所有机制”选用。当前包含灵梦、魔理沙、橙、爱丽丝、帕秋莉、壳魔理沙、多毛灵梦、灵胖和多毛灵胖九套运行模型；两种家油、两种捕食种和六种不足油沿用各自接入包。圆润身体基线已经用户确认；本轮后发修正版、灵胖成熟面容和小蝴蝶结的美术确认状态仍按标准库登记，游戏接入不等于确认新版造型。

[当前动作与表情预览](previews/current/index.html)读取选用的游戏网格。[完整20形态展示](../../../standards/yukkuri-library/body-standard/previews/current/all-models/index.html)另包含不在本包改形范围内的形态。实际客户端和机制检查见[本轮验收](../../../docs/validation/personality-deibu/current/README.md)。

## 来源与复现

- `manifest.json` 逐项登记源模型、数字骨架、快照、贴图、动作和运行目标及指纹。各角色 `selected/` 保留本轮标准导出的原样快照，旁边的运行模型与骨架是游戏适配结果。
- `baseline/` 是更新前的不可变输入，用于旧伤口三角面迁移；不能用当前导出覆盖它。`yukkuri_wounds/legacy/` 的打包数据只保留迁移必需的旧表面位置。
- `scripts/export_rounded_runtime.py` 保留标准库连续身体及后发混合权重，补齐通用动作并去掉游戏中不应由动画重复驱动的水平位移。壳种的侧弯采用小幅整体倾斜，步态放慢。
- 灵胖制作源是1.5倍实体；导出先归一化，游戏统一施加1.5倍形态比例。多毛灵胖保留多毛网格，并选用小蝴蝶结与成熟面容。碰撞、成长、挂件与出生出口共同使用该比例。
- `scripts/rounded_wound_surface.cjs` 生成配套伤口表面；`scripts/export_rounded_attachments.py` 生成脱落饰物、徽章挂点和额头茎根；随后运行 `export_badges.py --mounts-only`、`export_family_assets.py` 和标准库 `drafts/operation-tools/build.py` 同步挂件。
- `scripts/build_rounded_preview.py` 生成本包预览入口。旧七模型导出器已加阻止覆盖的检查；旧亚种导出器不再重建已经另行选用的壳种、多毛与不足油。

标准库制作源与本包快照职责独立。再次选用新版时须重新导出伤口、饰物、束具和预览，再按新指纹检查；不能只更新模型 JSON。

后续豆油辫子／帽檐与捕食种尖牙修订的四套帽饰也由本包挂件导出器派生；运行索引共13套实际帽饰，主体模型仍由各自素材包维护。最新四套配套与定向实机记录见[本轮复验](../../../docs/validation/bean-braid-fangs/current/README.md)。

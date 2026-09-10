# 美术与动作资料库

新增保存：[普通魔理沙七项辫子互动 v1](../../standards/yukkuri-library/interactions/marisa-braids/README.md)，包含绷直辫身、脸侧避让及转向自己后放低护理帽子的确认版本。[独立快照索引](../../standards/yukkuri-library/interactions/marisa-braids/approved-snapshots.json)；不计入下方历史 variants 统计。

当前可编辑复用标准：[去重素材库](../../standards/yukkuri-library/index.html)。本页压缩包是历史制作与候选快照，不按记录条数计算当前独立动作，也不再从中复制已有标准动作。


新增：[标准灵梦茎生 v1](../../standards/yukkuri-v1/actions/README.md)已获用户确认，源文件及离线预览以独立哈希快照保存于标准包的 `actions/snapshots/`。此快照不计入下方旧版 variants 资料库的数量统计。

按用途分类的源文件快照。保留采用中和未淘汰的动作版本；明确弃用的方案不再保存或恢复。同名动作不会覆盖另一模型的曲线。
原工作目录路径保留，可直接打开预览；克隆后先运行恢复命令，即可重建这些目录。

```powershell
python tools/project-library/manage.py verify
python tools/project-library/manage.py restore --out ../MCyukkuri-art-restored
```

恢复目标必须是不存在的新目录，工具不会覆盖已有工程。完整流程见 [资产保存流程](../../docs/ASSET_WORKFLOW.md)。

保存 2396 个源文件、39 条动画 JSON 导出记录和 117 条模型内嵌动作记录（包含重复与历史版本，两种记录不能相加当作独立动作数），另有互动场景状态机与表情脚本。

## 基础造型

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 魔理沙 · B 躯干长度稍增高与逃跑修正 | 已制作，待实体接入 | 0 / 10 | [完整源文件](models/marisa-b-taller/ab79bcc23ead89e0a8b2.zip) | [打开预览](../../variants/marisa-b-taller/index.html) |
| 魔理沙 · 选定基础体型 | 选定基础 | 0 / 0 | [完整源文件](models/marisa-body/2db7f097db6080486b5f.zip) | [打开预览](../../variants/marisa-body/preview.html) |
| 灵梦 · 基础造型 | 选定基础 | 0 / 0 | [完整源文件](models/reimu-hakkei/097196c0c9e07cc4c69c.zip) | [打开预览](../../variants/reimu-hakkei/preview.html) |

## 角色动作

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 魔理沙 · 已选 B 体型三动作与自由视角 | 已制作，待实体接入 | 0 / 5 | [完整源文件](motions/marisa-b-actions/fcbc74a312adb1d15c08.zip) | [打开预览](../../variants/marisa-b-actions/index.html) |
| 魔理沙 · 伸长与半速体操 | 已制作，待实体接入 | 4 / 4 | [完整源文件](motions/marisa-exercise/c3d0d8a1848bee9dcd98.zip) | [打开预览](../../variants/marisa-exercise/preview.html) |
| 魔理沙 · 排嗯嗯与馅料 | 已制作，待实体接入 | 1 / 1 | [完整源文件](motions/marisa-poop/3d8026ac17f0d6302603.zip) | [打开预览](../../variants/marisa-poop/preview.html) |
| 魔理沙 · 噗咕鼓腮 | 已制作，待实体接入 | 5 / 5 | [完整源文件](motions/marisa-puff/8393ef5db08e4498f028.zip) | [打开预览](../../variants/marisa-puff/preview.html) |
| 魔理沙 · 独立臀部甩动 | 已制作，待实体接入 | 1 / 1 | [完整源文件](motions/marisa-rump/c59af35ee4592144d1f8.zip) | [打开预览](../../variants/marisa-rump/preview.html) |
| 灵梦 · 完整体操与分段动作 | 已制作，待实体接入 | 5 / 19 | [完整源文件](motions/reimu-exercise/7df1e95e7373d4c9b716.zip) | [打开预览](../../variants/reimu-exercise/preview.html) |

## 表情与脸部试验

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 表情组合 · 四套方案 | 候选保留 | 0 / 0 | [完整源文件](expressions/diary-combinations/37507b0a88fa502a6248.zip) | [打开预览](../../variants/diary-combinations/index.html) |
| 神态与刘海 · A–D 试验 | 候选保留 | 0 / 0 | [完整源文件](expressions/expression-trials/115289e04ad7cfe852c2.zip) | [打开预览](../../variants/expression-trials/index.html) |
| 魔理沙 · 分层表情工坊 | 已制作，待实体接入 | 4 / 8 | [完整源文件](expressions/marisa-expressions/025f7af676ddb79313ff.zip) | [打开预览](../../variants/marisa-expressions/preview.html) |
| 噗咕眼形 · A | 选定基础 | 5 / 5 | [完整源文件](expressions/marisa-puff-eye-a/2b7478b29b64ad5284cf.zip) | [打开预览](../../variants/marisa-puff-eye-a/preview.html) |
| 噗咕眼形 · A 完整眼线 | 候选保留 | 5 / 5 | [完整源文件](expressions/marisa-puff-eye-a-clear/2c67cf693cff08febd9c.zip) | [打开预览](../../variants/marisa-puff-eye-a-clear/preview.html) |

## 互动与生活场景

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 魔理沙 · 进食、食盆与油粮 | 已制作，待实体接入 | 4 / 4 | [完整源文件](scenes/marisa-feeding/564ff16dd045e4e11f66.zip) | [打开预览](../../variants/marisa-feeding/preview.html) |
| 双魔理沙 · 噗咕较量与逃跑 | 已制作，待实体接入 | 0 / 0 | [完整源文件](scenes/marisa-puff-encounter/26b33bc184272c39765d.zip) | [打开预览](../../variants/marisa-puff-encounter/index.html) |

## 历史造型与动作方案

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 魔理沙 · 躯干加长、臀部尺寸保持 | 候选保留 | 0 / 20 | [完整源文件](history/marisa-body-length-trials/8dd59f50647e7ecffac5.zip) | [打开预览](../../variants/marisa-body-length-trials/index.html) |
| 魔理沙 · 憨厚造型留档 | 历史保留 | 0 / 0 | [完整源文件](history/marisa-gentle/cc03ba0ba1566d4c76b1.zip) | [打开预览](../../variants/marisa-gentle/preview.html) |
| 魔理沙 · 八景神态方案 | 历史保留 | 0 / 0 | [完整源文件](history/marisa-hakkei/0cdd891e814ee869b306.zip) | [打开预览](../../variants/marisa-hakkei/preview.html) |
| 魔理沙 · 体型与臀部比例 A–E | 候选保留 | 0 / 25 | [完整源文件](history/marisa-rump-size-trials/6822e5badc228bbaab4f.zip) | [打开预览](../../variants/marisa-rump-size-trials/index.html) |
| 伸长 · 初版 | 历史保留 | 2 / 2 | [完整源文件](history/marisa-stretch/06839d47e67fbb422374.zip) | [打开预览](../../variants/marisa-stretch/preview.html) |
| 伸长 · 原作比例方案 | 历史保留 | 2 / 2 | [完整源文件](history/marisa-stretch-source/c375911eea821876bb00.zip) | [打开预览](../../variants/marisa-stretch-source/preview.html) |
| 魔理沙 · 早期移动方案 | 历史保留 | 1 / 1 | [完整源文件](history/marisa-waddle/eedf2140f51365b58856.zip) | [打开预览](../../variants/marisa-waddle/preview.html) |

## 共享生成与预览工具

| 项目 | 状态 | 动作记录 JSON / 模型内嵌 | 源文件包 | 原工作目录 |
| --- | --- | --- | --- | --- |
| 早期模型、预览运行库与生成工具 | 共享工具 | 0 / 0 | [完整源文件](shared/project-support/9cd57bb7927e01b06084.zip) | — |

## 道具

[独立馅料模型](../props/filling/README.md) · [食盆与油粮模型](../props/food-bowl/README.md)

包内保留可编辑模型、几何、动画 JSON、rig、贴图、表情、行为脚本、生成/验证工具及预览。原作提取图层、逐帧渲染与本地参考另存于 `archives/art-preservation/`，不进入这些源文件包。

候选/历史状态只用于归档，不代表用户选定或完成游戏实体接入。

## 冻结待调整

[六项基础互动](scenes/basic-interactions/FROZEN.md)：按用户要求冻结，保留成果，待其他任务完成后调整再决定纳入；不进入默认构建与恢复。

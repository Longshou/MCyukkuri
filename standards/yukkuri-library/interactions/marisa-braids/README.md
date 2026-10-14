# 普通魔理沙 · 单辫互动

当前为 **v2 单辫版**（2026-10-14，待确认）。魔理沙标准改为自身左侧一条粗辫（见[单辫修订](../../body-standard/features/marisa-single-braid/README.md)）后，七项动作改由这条辫子单独完成，另一侧的短侧发不参与抓握。模型使用当前圆润普通魔理沙。

v1（2026-09-11 确认的双辫版）保留为历史快照：[动作登记](saved-action.json) · [独立快照与 SHA-256 清单](approved-snapshots.json)。快照包含当时的模型、动画、预览、验证结果及重建所需文件，后续编辑不会改写它。`verification.json`、`clearance-comparison.json` 是 v1 的浏览器检查记录，不适用于 v2。

[打开七项互动预览](index.html)：单个静态文件，直接双击打开。可旋转、缩放、暂停、逐阶段定位、调速与重播。

| 动作 | v2 编排 |
| --- | --- |
| 捡拾石头、挥舞宝剑、拔草 | 节拍与道具轨迹沿用 v1，按圆润体型等比换算；辫梢卷握道具 |
| 取下帽子 | 辫梢扣住帽檐右前沿，抬离头顶后绕抓握点向前转出、托稳，再转回戴正 |
| 护理帽子 | 扣住帽檐取下，在身前高处转向，帽结朝向自己后放到地面；松开后擦拭帽带、整理蝴蝶结，再扣住同一处帽檐托起、转回、戴好（19.6 秒） |
| 挥“手”道别 | 辫子绕辫根扬起，来回摆动后垂下，配合点头 |
| 托子油上帽子 | 子油在右前方等候；辫子卷住子油身体，抬高越过帽檐，放到向前展开的帽檐上 |

辫身以绷直为主：靠轴向伸缩和浅弧到达接触点，卷握只在辫梢；探出、收回和越过头顶时围绕辫根转动，不让辫身从辫根处翻折。帽子只有一个抓握点，转动都绕这一点进行，帽子转向在头顶前上方完成，帽檐不碰头。辫根位置、辫长、分节、发结与发尾位置及帽檐抓握点均从 `assembled/marisa` 实测，标准造型修改后重建即可跟随。

| 文件 | 用途 |
| --- | --- |
| `recipes.json` | 七项动作的时长、阶段与道具 |
| `build.py` | 伸辫求解、帽子单点抓握、道具轨迹和导出；动作曲线的编辑源 |
| `render_checks.py` | 不经浏览器渲染各阶段关键帧，输出 `checks/<动作>.png` |
| `check_motion.py` | 数值检查辫身伸缩、抓握误差、相邻帧实际转角、帽檐离头高度与结束状态，写 `checks/motion-checks.json` |
| `viewer.html` | 预览模板，使用统一库的姿态采样和渲染器 |
| `assembled/marisa-braids.bbmodel` | 含魔理沙、道具、子油及七条动画的可编辑场景；圆润身体、五官和头发为网格 |
| `assembled/marisa-braids.geo.json` | 同一场景的 Bedrock 几何，网格部件以 `poly_mesh` 导出 |
| `assembled/marisa-braids.animation.json` | 七条非循环动画，20 Hz 采样，线性插值 |
| `assembled/animation-rig.json` | 预览骨架、动作轨道和逐顶点刚性绑定 |
| `assembled/provenance.json` | 所用标准模型与骨架的 SHA-256、辫子与帽檐实测参数 |

统一库或魔理沙造型更新后重建（Python 需要 Pillow）：

```powershell
python standards/yukkuri-library/interactions/marisa-braids/build.py
python standards/yukkuri-library/interactions/marisa-braids/check_motion.py
python standards/yukkuri-library/interactions/marisa-braids/render_checks.py
```

`verify.cjs` 是 v1 的浏览器验证脚本，使用 Playwright 驱动浏览器，本轮未运行。

这里完成的是动作、模型与道具预览，尚未接入 Minecraft 实体 AI、物品栏、方块破坏、碰撞物理、存档或联机。导出为完整演示场景，打开模型后请选择动画查看；静态绑定姿态可能显示全部道具。接入引擎时角色与独立物品应拆为实体，抓握期间使用 `grip` 挂点，交接后转给地面或帽檐挂点，并由服务端控制拾取、拔草与子油乘坐状态。

托子油结束时子油留在帽檐上，重播会重置到地面；护理后灰尘保持清除，拔出的草留在旁边；其余动作收辫恢复。常态与其他动作使用原帽檐尺寸。

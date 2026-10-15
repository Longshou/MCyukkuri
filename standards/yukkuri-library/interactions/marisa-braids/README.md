# 普通魔理沙 · 单辫互动

当前为 **v2 单辫版**，2026-10-14 用户保存，[动作登记](saved-action-v2.json)记录所用模型、源文件、导出与检查结果的指纹及提交，Git 历史保存原始字节。魔理沙标准改为自身左侧一条粗辫（见[单辫修订](../../body-standard/features/marisa-single-braid/README.md)）后，七项动作改由这条辫子单独完成，另一侧的短侧发不参与抓握。模型使用当前圆润普通魔理沙。

v1（2026-09-11 确认的双辫版）保留[动作登记](saved-action.json)，独立快照与浏览器检查记录已删除。

`build.py` 在本机生成七项互动预览 `index.html`（不入库），可旋转、缩放、暂停、逐阶段定位、调速与重播。

| 动作 | v2 编排 |
| --- | --- |
| 捡拾石头、挥舞宝剑、拔草 | 节拍与道具轨迹沿用 v1，按圆润体型等比换算；辫梢卷握道具 |
| 取下帽子 | 辫梢扣住帽檐右前沿，抬离头顶后绕抓握点向前转出、托稳，再转回戴正 |
| 护理帽子 | 扣住帽檐取下，在身前高处转向，帽结朝向自己后放到地面；松开后在蝴蝶结旁擦拭帽带、整理蝴蝶结，再扣住同一处帽檐托起、转回、戴好（20.4 秒） |
| 挥“手”道别 | 辫子绕辫根扬起，来回摆动后垂下，配合点头 |
| 托子油上帽子 | 子油在右前方等候；辫子卷住子油身体，抬高越过帽檐，放到向前展开的帽檐上 |

辫身以绷直为主：靠轴向伸缩和浅弧到达接触点，卷握只在辫梢；探出、收回和越过头顶时围绕辫根转动，不让辫身从辫根处翻折。帽子只有一个抓握点，转动都绕这一点进行，帽子转向在头顶前上方完成，帽檐不碰头。辫根位置、辫长、分节、发结与发尾位置及帽檐抓握点均从 `assembled/marisa` 实测，标准造型修改后重建即可跟随。

| 文件 | 用途 |
| --- | --- |
| `recipes.json` | 七项动作的时长、阶段与道具 |
| `build.py` | 伸辫求解、帽子单点抓握、道具轨迹和导出；动作曲线的编辑源 |
| `render_checks.py` | 不经浏览器渲染各阶段关键帧，输出 `reports/current/<动作>.png` |
| `check_motion.py` | 数值检查辫身伸缩、抓握误差、相邻帧实际转角、帽檐离头高度与结束状态，写 `reports/current/motion-checks.json` |
| `clearance.py` | 测量辫子进入身体与帽子、帽子进入头部、子油与道具进入身体的深度，写 `reports/current/clearance.json` |
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
python standards/yukkuri-library/interactions/marisa-braids/clearance.py
python standards/yukkuri-library/interactions/marisa-braids/render_checks.py
```

检查与渲染结果写入 `reports/current/`，只在本机，不入库。

`verify.cjs` 是 v1 的浏览器验证脚本，使用 Playwright 驱动浏览器，本轮未运行。

这里完成的是动作、模型与道具预览，尚未接入 Minecraft 实体 AI、物品栏、方块破坏、碰撞物理、存档或联机。导出为完整演示场景，打开模型后请选择动画查看；静态绑定姿态可能显示全部道具。接入引擎时角色与独立物品应拆为实体，抓握期间使用 `grip` 挂点，交接后转给地面或帽檐挂点，并由服务端控制拾取、拔草与子油乘坐状态。

## 穿插

2026-10-14 按“可接受少量穿模，但尽量调整得更自然”调整，随 v2 一起保存：

- 辫子伸向帽檐时，若直线路径会从帽檐下穿过，先沿帽檐外沿绕行再到达抓握点；探出时先向外侧张开再转向目标。
- 摘帽后托住帽子的位置、帽子在身前转向的路线与擦拭位置都离开头部和帽带，擦拭在蝴蝶结旁进行，不再穿过蝴蝶结。
- 挥手的高低两个位置都移到身体外侧；托子油时先把子油向前抬出，再升到帽檐高度，不擦过脸和帽檐。
- 松开帽子和子油后的收辫分别放慢到 1.8 秒和 2.0 秒，探出改为平滑起步。

`clearance.py` 以 10 Hz 采样，深度单位为模型像素（1 格 = 16），辫根连接处 1.6 以内不计。当前结果：帽子、子油和道具都不进入身体，帽子取下期间不进入头部；辫子贴身深度 0.012，与静止姿态相同；残留的是辫子在挥手、托子油和松开帽子后扫过帽檐下沿时约 0.41 的轻擦，按可接受范围保留。

托子油结束时子油留在帽檐上，重播会重置到地面；护理后灰尘保持清除，拔出的草留在旁边；其余动作收辫恢复。常态与其他动作使用原帽檐尺寸。

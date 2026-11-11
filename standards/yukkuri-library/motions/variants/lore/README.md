# 世界观动作

本包为生态行为和关系演出提供动作片段。来源分组与设计边界见 [Wiki 总入口](../../../../../wiki/README.md)；`recipe.json` 是动作 ID、时长、循环、变换、表情编排、形态门控与出处的唯一配置源。Wiki 无明确依据的动作标为项目设计。

动作按主题分为日常、社交与情绪，宠物店与考试，生育与接生，以及水生魔理沙帽船行为。服务端行为选择阶段和播放时钟；客户端只采样对应片段，不以动画驱动业务状态。具体触发见玩法专题：[宠物店](../../../../../docs/FEATURES.md#宠物店徽章与宠物培育)、[生育与接生](../../../../../docs/FEATURES.md#消耗品与胎生)、[水边生活](../../../../../docs/FEATURES.md#水生魔理沙)。

无颈圆团子的动作以压扁、拉伸、小位移、转向和表情表达；整体俯仰与侧倾合计应控制在约 12°。表情片段由 `expression_spans` 编排，哭脸等服务端状态可按 `expression_yield` 优先显示。魔理沙辫子随身体动作的规则见 [单辫动作](../contact-safe/README.md#魔理沙辫子跟随)。`hand` 骨只在它带的确实是辫子（`braid_lock_*`）时做扑水、扶帽等手势；同一骨在其他形态上带的是散侧发、前发或马尾，这些头发不单独摆动，只随身体。

## 制作与导出

`author.py` 求值动作，`hat.py` 求解帽子、帽子实体位置和辫子接触。家族选择由 `recipe.json` 的 `export_families` 维护；新增家族只加数据。片段写 `base: <片段>` 即为该片段的参数预设：继承其配方（可逐级继承）、只写覆盖项，并沿用宿主片段的求值代码，如 `rain_huddle_left`、`cling_right/left`（贴靠，`shiver` 为 0）、`nod`（`bow` 的 `repeat` 次快速点头）。`base-motions` 家族导出动作库基本层的 lore 片段：`lunge_ram`（前冲撞击，标记 `launch`／`impact`／`settled`，位移只在身体范围内，行进归运动层）与上述预设。`life` 包含寻子呼唤 `call_child`、醒来伸懒腰 `wake`、吃饱瘫软 `meal_content` 与含泪噗咕 `puku_teary`，按配方 `height_keys` 等关键帧求值，宽度随高度保体积，可选 `swell_keys` 在其上整体放大。统一入口见[脚本说明](../../../../../scripts/README.md)。运行资源与指纹由 `art/source/motion-corrections/manifest.json` 登记。

```powershell
<py> scripts/export.py --family weather --family life
<py> scripts/export.py --rebuild-motion
<py> scripts/export.py --check
```

水生帽船的木棍、辫兜、嘴咬、翻覆、自救与船上日常片段由 `aquatic_tools.py` 编排（`export.py --family aquatic`）：除骨骼轨道外，片段还导出 `markers`（如 `power_start`／`power_end`）、`contacts`（骨骼与静止坐标点，或 `mouth`）与循环片段的 `base_pose`；游戏与 `aquatic_paddle_preview.py` 读同一份导出数据，后者画 `boat_paddle` 在帽船形态中的关键姿势（侧视、正视）与辫节前后弯曲带，`--form` 选形态；`aquatic_strokes_preview.py` 把 `bank_beckon`（平岸与水面）和 `boat_paddle`（帽船水线）的入水、拉水、出水、回程画在一张（`.local/previews/water-strokes/current.png`）。划水由 `aquatic_tools.stroke_gate` 在导出时把关：骨骼轨道与木棍关键帧循环首尾一致、辫子不比静止时更深地压进身体、木棍不进身体；辫子拨水（`bank_beckon` 岸边、`water_beckon` 游在帽旁、`boat_paddle_braid` 坐在帽船里无棍划）辫梢整个拉水半程在片段 `stroke.water` 水面下（`"hull"` 取帽船水线），`contact`／`stroke` 两个标记就是辫梢实际入水与出水的时刻（差不过一个采样），游戏只在两者之间推帽；岸边拨水另要身体最低点落在岸顶（`root_support: "bank"`，不算辫子）、不陷进岸或水，辫子不进岸。这些片段的辫子由 `aquatic_tools.py` 自己铺好，`contact-safe/author.py` 的通用辫子跟随不再叠到它们上；`scripts/export_motion_corrections.py` 从同一份源编译整个资源，两者的 `--check` 和修订串一致。片段可在配方里写 `braid_path`：按时间给出辫梢末端在座位坐标里的目标点（模型单位，随身体偏航与平移转动），由 `braid_chain.arc` 按游戏同样的弯法解出整条辫子；`"stroke"` 让辫梢走片段 `stroke` 块的划水轨迹（`aquatic_tools.stroke`，与木棍桨叶同一条：拉水半程在水下，回程出水、贴水面摆回），目标点在这一划的坐标里（-z 朝水或朝帽），辫根一节按身体压扁的倒数缩放、保持原长（`bank_beckon` 岸边拨水；`water_beckon` 游在帽旁拨水，`stroke.water` 取身体约一半在水下的水线）；`[t, "rest"]` 取共享的 `boat_braid.rest`（项目设计：睡莲叶帽船里船长把辫子搭出帽檐、辫梢在帽檐外的水面上方，帽底留给孩子和储粮）。`braid_curve: "loop"` 让循环片段的辫梢目标走一条穿过各关键点的闭合曲线（不在关键点停顿，循环接缝处不折）。无棍划水 `boat_paddle_braid`（项目设计，用户 2026-11-05 反馈 8：辫子要真沾到水）沿用 `boat_paddle` 的身体与 `power_start`／`power_end`：坐在帽底辫子够不到帽檐外的水，拉水时身体向辫侧侧倾（`lean`，俯仰按 `pitch_share` 缩小，合计不过 12°）、略向辫侧和前方挪（`lean_out`，后发不贴帽壁），辫子绷过帽檐、沿自身拉长至多 `lean_stretch`（与取戴帽拉长辫子同法，发梢保持原大）；循环接缝（t=0）是 `boat_braid.rest` 的船上静置辫、身体直立，漂流转向前后衔接不跳；`hull_gate` 只核对辫子不进船体。贴帽动作按 `interactions/aquatic-marisa-hat/layout.py` 的船尾座位检查（船长左右转 15°，容差 0.1）。片段里的 `props.paddle` 键仍在（游戏不读，`aquatic_paddle_preview.py` 用它画木棍），划桨时游戏画的是辫子握住的真实原版木棍，`paddle_stick` 道具不在导出中。

持物与多只运输（项目设计）：`mouth_release` 是灵梦把口中幼油放出的节拍（`open` 张口、`release` 幼油出唇松开、`closed` 闭口）；持物是通用能力，魔理沙用辫梢攥住真实物品（`braid_item_hold` 陆上只作辫子与物品叠加；船上的叠加就是 `boat_paddle` 的首帧握棍姿，划桨、转向漂流与坐着共用一握；`braid_item_pick` 的 `contact`、`braid_item_place` 的 `release` 为交接点），导出 `props.item`（与划桨同一握点和木棍坐标系）与 `contacts.braid_grip`，由 `aquatic_tools.held_gate` 把关：木棍不进身体、帽子、地面或船体，辫子不比静止时更深压进身体；其他油库里用嘴（`mouth_item_pick` 的 `bite`、`mouth_item_place` 的 `release`）。辫子自己在干活的片段（配方 `braid_use`：取戴帽、扶帽、理帽、拨水、游泳与水中扑腾、无棍划水、勾檐翻帽、推出帽檐、辫兜与辫托孩子、子油拍水学划）不叠持物姿态，导出 `braid_use`；手里的物品若片段没有自己的 `props.item` 就收在帽里、不画；一次性片段用 `braid_busy`（此前从持物姿态过渡到片段的辫子）与 `braid_free`（此后回到持物姿态）两个标记交接。水边自己摘帽、戴帽（`hat_off_self_aq`／`hat_on_self_aq`，配方 `hat.stick`：按时间 `[t, x, y, z]` 给出木棍长端在戴着的帽子坐标里的方向）木棍不收进帽里：辫梢照旧攥着它去勾帽檐，木棍顺着辫梢勾住的那段帽檐贴着、随帽子转动——戴着时朝辫侧外伸，放到水上翻过来后朝水面外伸、不压进水里，两者之间在帽子翻转途中于辫梢里换向（`aquatic_tools.stow_tracks` 导出片段自己的 `props.item`，握点与 `braid_item_hold` 相同），交接窗口里游戏把木棍与辫子按同一比例从持物姿态过渡过来、再过渡回去；`stow_gate` 按游戏的合成（陆上 `braid_item_hold`、船上 `boat_paddle` 两种持物姿态，水生帽子放大与随辫梢移动）逐 tick 核对木棍不穿过身体（含脸与头发）和帽子、不从脸前横过（项目设计，用户 2026-11-05 反馈 2）。`boat_puff` 是坐在帽船里的鼓气威吓，辫子保持船上静置。`carry_hold_preview.py` 把灵梦放出、橙两尾各托一只和魔理沙岸上持棍画在一张（`.local/previews/art-a2-carry/current.png`，可传输出目录）。

会操帽的个体主动划水用 `water_swim`（项目设计，用户 2026-11-04“开头像抽搐”）：首帧就是 `water_plunge` 的末帧、从静止起划，每圈辫子先前伸、再在拉水半程（`power_start`..`power_end`）往后下方拨，身体随之小幅前冲。`water_struggle` 用自身节拍表现原地挣扎或挣扎着游，`water_gasp` 留给真正力竭。

预览场景由同目录 `births.py` 生成；它们只展示动作与接触编排，不运行游戏碰撞、AI 或成功率判定。

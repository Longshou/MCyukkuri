# 世界观动作

本包为生态行为和关系演出提供动作片段。来源分组与设计边界见 [Wiki 总入口](../../../../../wiki/README.md)；`recipe.json` 是动作 ID、时长、循环、变换、表情编排、形态门控与出处的唯一配置源。Wiki 无明确依据的动作标为项目设计。

动作按主题分为日常、社交与情绪，宠物店与考试，生育与接生，以及水生魔理沙帽船行为。服务端行为选择阶段和播放时钟；客户端只采样对应片段，不以动画驱动业务状态。具体触发见玩法专题：[宠物店](../../../../../docs/FEATURES.md#宠物店徽章与宠物培育)、[生育与接生](../../../../../docs/FEATURES.md#消耗品与胎生)、[水边生活](../../../../../docs/FEATURES.md#水生魔理沙)。

无颈圆团子的动作以压扁、拉伸、小位移、转向和表情表达；整体俯仰与侧倾合计应控制在约 12°。表情片段由 `expression_spans` 编排，哭脸等服务端状态可按 `expression_yield` 优先显示。魔理沙辫子随身体动作的规则见 [单辫动作](../contact-safe/README.md#魔理沙辫子跟随)。`hand` 骨只在它带的确实是辫子（`braid_lock_*`）时做扑水、扶帽等手势；同一骨在其他形态上带的是散侧发、前发或马尾，这些头发不单独摆动，只随身体。

## 制作与导出

`author.py` 求值动作，`hat.py` 求解帽子、帽子实体位置和辫子接触。`--export-birth`、`--export-distress`、`--export-comfort`、`--export-carry` 与 `--export-badge` 分别更新对应片段；完整导出使用 `python scripts/export_motion_corrections.py`。运行资源与指纹由 `art/source/motion-corrections/manifest.json` 登记。

```powershell
python standards/yukkuri-library/motions/variants/lore/author.py --export-birth
python standards/yukkuri-library/motions/variants/lore/author.py --export-distress
python standards/yukkuri-library/motions/variants/lore/author.py --export-comfort
python standards/yukkuri-library/motions/variants/lore/author.py --export-carry
python standards/yukkuri-library/motions/variants/lore/author.py --export-badge
python standards/yukkuri-library/motions/variants/lore/author.py --export-aquatic
```

水生帽船的木棍、辫兜、嘴咬、翻覆、自救与船上日常片段由 `aquatic_tools.py` 编排（`--export-aquatic`）：除骨骼轨道外，片段还导出 `markers`（如 `power_start`／`power_end`）、`contacts`（骨骼与静止坐标点，或 `mouth`）与循环片段的 `base_pose`；游戏与 `aquatic_paddle_preview.py` 读同一份导出数据，后者画 `boat_paddle` 在帽船形态中的关键姿势（侧视、正视）与辫节前后弯曲带，`--form` 选形态；`aquatic_strokes_preview.py` 把 `bank_beckon`（平岸与水面）和 `boat_paddle`（帽船水线）的入水、拉水、出水、回程画在一张（`.local/previews/water-strokes/current.png`）。两条划水由 `aquatic_tools.stroke_gate` 在导出时把关：骨骼轨道与木棍关键帧循环首尾一致、辫子不比静止时更深地压进身体、木棍不进身体；拨水时身体最低点落在岸顶（`root_support: "bank"`，不算辫子）、不陷进岸或水，辫子不进岸，辫梢整个拉水半程在水面下。这些片段的辫子由 `aquatic_tools.py` 自己铺好，`contact-safe/author.py` 的通用辫子跟随不再叠到它们上；`scripts/export_motion_corrections.py` 从同一份源编译整个资源，两者的 `--check` 和修订串一致。片段可在配方里写 `braid_path`：按时间给出辫梢末端在座位坐标里的目标点（模型单位，随身体偏航与平移转动），由 `braid_chain.arc` 按游戏同样的弯法解出整条辫子；`"stroke"` 让辫梢走片段 `bank` 块的划水轨迹（`aquatic_tools.stroke`，与木棍桨叶同一条：拉水半程在水下，回程出水、贴水面摆回），目标点在岸的坐标里（-z 朝水），辫根一节按身体压扁的倒数缩放、保持原长（`bank_beckon`，岸边拨水）；`[t, "rest"]` 取共享的 `boat_braid.rest`（项目设计：睡莲叶帽船里船长把辫子搭出帽檐、辫梢在帽檐外的水面上方，帽底留给孩子和储粮）。贴帽动作按 `interactions/aquatic-marisa-hat/layout.py` 的船尾座位检查（船长左右转 15°，容差 0.1）。片段里的 `props.paddle` 键仍在（游戏不读，`aquatic_paddle_preview.py` 用它画木棍），划桨时游戏画的是辫子握住的真实原版木棍，`paddle_stick` 道具不在导出中。

预览场景由同目录 `births.py` 生成；它们只展示动作与接触编排，不运行游戏碰撞、AI 或成功率判定。

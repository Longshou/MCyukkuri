# 辫子动作制作技能

凡新做或修改辫子（及其他种族相应发束）动作、接触、持物或选片的包，开工前读本页，按本页选做法与检查，收工时把新经验补进[补充规则](#补充规则)。造型来源见[魔理沙单辫](body-standard/features/marisa-single-braid/README.md)，通用约束见[制作约定](AGENTS.md)，制作流程见[美术与动作制作者](../../docs/roles/art.md)。数值以下列源文件为准，本页与源冲突时以源为准并当场改正本页。

## 1. 骨架与几何

- 辫子在魔理沙自身左侧（模型 `+X`），挂在 `hand_right` 骨上；另一侧 `hand_left` 是不编织的短侧发。离线预览方位角 0 时辫子在画面左侧。
- 骨链：`hand_right`（辫根与第一节）→ `hand_right_braid_1`～`_5`（各带一节编织段，关节在相邻两节重叠中点）→ `hand_right_tip`（散开发尾）；`_5` 同时带发结与蝴蝶结。网格部件都以 `braid_lock_` 开头，发尾是 `braid_lock_tuft*`。静止时各骨不变换。
- 接触点是发尾网格离辫根最远的顶点（游戏 `BraidPose.resolve`、制作 `braid_keys.Chain.end` 同法），不是 `hand_right_tip` 骨的枢轴；导出的 `tip` 轨道就是这一点，幼油、食物、木棍都按它画。
- 标准体辫长约 9.7 模型单位、粗约 2.5，辫根在帽檐下。辫长从辫根到静止辫梢量（`braid_rescue.json` 的 `braid_root`／`braid_tip`），服务端按 `growthScale × scale / 16` 换算成格；各节长度固定，任何解算都不拉长辫子。唯一例外是片段显式声明的拉长（无棍划水的 `lean_stretch`、取戴帽），发梢保持原大。
- 辫根随身体动：身体侧倾、压扁会移动辫根，`reach_lean_root` 是低位俯身时的辫根；够取判定按片段实际所处的姿态取辫根。
- 非魔理沙多骨发束链（`body-standard/features/hair-chains/author.py` 导出，保持静止外廓与旧骨索引不变）：
  - 灵梦（`reimu`、`reimu_face`、`reimu_deibu`）：左右鬓发各 6 骨链 `hand_{side}` → `hand_{side}_lock_1`（`y=7.80`）→ `lock_2`（`6.55`）→ `lock_3`（`5.25`）→ `lock_4`（`4.00`，刚性携带纸筒与筒内发束防穿筒）→ `hand_{side}_tip`（`1.75`）；各关节采用以枢轴为中心的分段 smoothstep 蒙皮（`y ≥ 8.42` 刚性归根骨，`y ≤ 4.00` 刚性归纸筒骨），防止大角度弯曲时发根内拉变细。
  - 帕秋莉（`patchouli`）：左右长脸颊发束各 6 骨链 `hand_{side}` → `hand_{side}_lock_1..4`（`y=7.45/5.50/3.55/1.90`）→ `hand_{side}_tip`（`y=0.80`，携带发结与发尾簇）。

| 形态 | 辫子 | 能做的辫子动作 |
| --- | --- | --- |
| `marisa` | 标准骨链 | 全部（托幼、水生、帽船、取戴帽等组 `marisa_braid`／`aquatic_marisa`／`marisa_hat` 只编到这一形态） |
| `marisa_face` | 标准骨链 | 跟随、送嘴进食（`braid_feeders`）、翻垃圾桶（`bin_nose_in`）、水中扑腾（`water_flail`／`water_struggle`） |
| `marisa_tsumuri` | 标准骨链，无帽 | 跟随、送嘴进食、接生片段、翻垃圾桶、水中扑腾；缩入时头发后仰外张 |
| `marisa_kotatsu` | 标准骨链 | 只跟随；被炉围裙（`x=8.71..9.59`、`y=0.15..10.70`）挡住辫根前摆与外摆，送嘴进食、翻垃圾桶与水中扑腾门槛不过 |
| `marisa_sparse` | 一撮贴在头皮上的短辫，不挂骨链 | 无，随皮肤变形，只用嘴 |
| `marisa_bean` | 按头部比例缩小的骨链 | 只跟随；辫根（`y=12.66`）高于帽檐底（`12.54`），静止 `braid_hat=+0.069`，主动摆辫必超 0.1 门槛 |
| `house_marisa` | 自身缩小骨链，夹点都在发尾骨 | 家油专用夹取、搬运、放下（`scripts/runtime_house_interactions.py`）与水中扑腾（`water_flail`／`water_struggle`） |
| 捕食种 | 辫链已剥除，`hand_right_tip` 直接挂在 `hand_right` | 无 |

## 2. 制作方式与垂坠手感

所有主动摆辫与长发束动作必须遵守**垂坠感 + 自然弯曲 + 末端发力**：发根受重力制约只作小幅偏转（一般 `root_max ≤ 18°`），弯曲沿中下段逐节展开并带行波滞后，发力集中在发梢末端（拍水、勾檐、掏翻、拨扫），禁止从发根整根僵硬直抬成触角。

一个片段的辫子／发束只有一个制作者；同一片段不叠两种做法。先按下表选，组装做不到才用更定制的一种。

| 做法 | 用在 | 入口 |
| --- | --- | --- |
| 跟随 | 辫子不干活，只随身体摆动 | `contact-safe/braid_chain.follow`；clip 列在 `contact-safe/recipe.json` 与 lore `recipe.json` 的 `braid_follow`，或家族 `braid_follow: true` |
| 垂坠发链（`hair_chain_keys`） | 拍水扑腾、灵梦／帕秋莉翻桶、翻正推帽、推出帽檐等动态甩拍与软弯 | lore `author.py:hair_chain_tracks`，配方 `hair_chain_keys`（小根角 `root_keys` + 按节递增权重与行波滞后 `lag` 的 `drive_keys` + 重力下垂 `sag` + 末端 `tip_keys` 发力） |
| 关键姿态（`braid_keys`） | 魔理沙辫梢在身旁的地面、帽檐、食物、幼油、桶口上做精确接触 | lore `braid_keys.py`，配方 `braid_keys`（配合 `root_weight` 非对称根手柄保持上段垂坠，及逐键 `tip_curl` 末端勾卷） |
| 目标点轨迹（`braid_path`） | 辫梢按时间走到给定点：拨水、船上姿态、托幼上头、持物 | lore `aquatic_tools.py`，配方 `braid_path`（可配 `braid_drape` 改用垂坠贝塞尔引导线替代等曲率圆弧） |
| 手骨手势 | 仅限小幅根部姿态与情绪展示：落水冲击、喘息、情绪垂发、挥手 | lore `author.py` 的 `flourish_tracks`、`display`、`flourishes.braid_wave` |

另有两类专用解算：取帽、戴帽、扶帽、水生护帽由 `lore/hat.py` 把辫子弯到帽檐握点；持棍划桨由 `paddle_shared`（`curl`、`aim`、`braid_raise`）铺辫。

### 跟随

- 辫根的摆动作为行波沿骨链传下：每节比上一节晚 `lag_seconds`，相对上一节弯曲不超过 `max_bend_degrees`，两者按 [辫根, 发尾] 渐变（根硬滞后短、尾软滞后长，发尾共晚约 0.32 秒）；偏离竖直时每节下垂 `sag_degrees`。参数在 `contact-safe/recipe.json` 的 `braid_chain`。
- 受力窗口 `load`（`start`／`end`／`ramp`／`tension`）让辫子在拉水时收直，出水后放开。
- 一次性片段在最后 `settle_seconds` 内收回滞后；静止与保持姿势不产生滞后。
- `braid_follow`（`contact-safe`）另求最小外摆角：辫子不比静止多进皮肤 0.02 以上、辫梢离地至少 0.03，外摆上限 30°。
- 片段自己已摆了中间节的，跟随保留原样；带 `braid_keys` 或 `braid_path` 的片段不进跟随。

### 关键姿态（`braid_keys`）

- 每个键 `[t, "rest"]` 或 `[t, {"tip", "root", "arrive", "tip_curl"?}]`：`tip` 是接触点位置，`root` 是辫子离开辫根的方向（保持接近竖直向下、略向外展让开脸颊，如 `[0.24, -1.0, -0.14]`），`arrive` 是到达辫梢的方向，`tip_curl` 是加在 `hand_right_tip` 上的末端勾卷欧拉角，模型坐标。整条辫沿贝塞尔引导线铺下，各节保持原长；片段级 `root_weight`（约 `1.2–1.45`）加长根部手柄使上段自然下垂、弯曲集中在中下段，引导线根控制点下限锁在地面 `y=0.2` 防止下冲折返。
- 键间的 `tip`、`root`、`arrive`、`tip_curl` 走 C1 三次曲线，只在标 `stop` 的键（保持与首尾）速度为零；不标 `stop` 的键辫子连续经过、不停顿不回折。
- 从垂直静止抬起或落回静止时，引导线会折回自身导致关节翻转：这一段的键标 `turn`，改为各骨从上一键的铺放姿态逐骨转到本键（最短弧），离开静止时辫梢先动、辫根后动，回到静止时相反；`swap_lead` 交换先后（贴身低伸而不是先在脸旁卷起时用）。
- `braid_frame: "body"` 让键写在身体自身坐标里，随根骨转身与侧倾；身体在辫子干活时转身的片段用它。
- 配方 `mirror` 只是倒放另一个片段的键与身体；需要不同路线（如放下不能原路退回）时另写一段键。
- 片段导出 `tip` 轨道（每个采样的接触点），游戏按它放幼油或画食物。

### 目标点轨迹（`braid_path`）

- 按时间给辫梢末端在座位坐标里的目标点（模型单位，随身体偏航、平移转动），`braid_chain.arc` 用游戏同一套圆弧解算铺整条辫。
- `"stroke"` 让辫梢走片段 `stroke` 块的划水轨迹（与木棍桨叶同一条）；`[t, "rest"]` 取 `boat_braid.rest`（船上把辫子搭出帽檐、辫梢在檐外水面上方）；`braid_curve: "loop"` 让循环片段的目标走闭合曲线，接缝不折；键后加 `"turn"` 与关键姿态同义。

### 手骨手势

- 只给真正带 `braid_lock_*` 的手骨加手势（`braid_carried`）；同一骨在别的形态上带侧发、前发、马尾，不做手势，只随身体（种族适配见第 5 节）。
- 情绪类用配方 `display`：`braid`（`forward_keys`、`out_keys`、`wags`、`whip`、`lag`，摆动按 `lag` 沿各节延后）、`sideburn`（灵梦侧发）、`tail`（橙的两尾）。
- 整体只转辫根时辫子是一根僵直的杆：需要弯曲、卷起或真接触的动作不要用这一做法。

## 3. 运行时

### 能力与占用（服务端）

- `ContactGeometry.braided` 决定谁有辫子可用（魔理沙变种）；某个片段在某形态上可用另看 `ClipMarkers.tipped(资产ID, 片段)`（只有导出了 `tip` 的形态为真），不可用时走嘴的路线。
- 够取：`braidGap`／`braidGrip` 取辫根（直立或低位俯身）到目标包围盒最近点的直线距离减辫长，并要求视线通畅；站位按转向目标后的真实辫根和真实支撑面量。准入与执行用同一个函数和同一个站位，不各算一份。
- `motor/Braid.use` 是辫子此刻拿着什么的唯一权威：`YOUNG`（夹着幼油）、`FOOD`（送嘴途中）、`HAT`（取戴帽）、`STICK`（持棍）、`FREE`、`NONE`。新动作要占用辫子，先在这里登记新的占用来源，不在行为里另设标志。
- 辫子拨水 `motor/action/Beckon`：够取 3 格，拉帽速度是木棍的 0.35，只在片段 `contact` 与 `stroke` 两个标记之间推帽。
- 服务端日志里 `reach=braid hold=brim` 表示辫子托到帽檐；排查辫子没动先看服务端是否请求了该片段，再看客户端收到的动画 ID。

### 选片（`PresentationArbiter`）

- 身体正在执行的动作（Grab、Release、Pick 等）自带片段时，惊慌、溺水、焦躁等接管分支都要先发布该动作的片段与起点（`actionPose`），只换表情与行为，不用通用步态盖掉；只有水中挣扎保留挣扎片段。
- 进食的辫子片段由 `braidMealClip` 指定，表情按片段时间线取样。

### 客户端绘制

- 渲染器播服务端同步的动画 ID；辫子的动作完全来自片段时，客户端没有别处能动它。
- 托到帽檐 `braid_brim_lift`／`braid_brim_lower` 是制作片段，`ParentCarryPresentation.liftClip` 对帽檐返回空，不再程序弯辫。
- 程序弯辫只剩 `BraidPose.reach`：救援取幼（`BraidFetch` 的 `REACH`／`HOLD`）、低位俯身、`braid_head_take` 与辫子夹着的幼油。解算是 `motor/geom/BraidChain`：圆弧、各节保长、相邻两节转角不超过 40°，过近继续折叠、过远伸直，弯向模型前方并略向下；权重按片段 `contact` 标记缓入。
- 帽子在辫梢上只在片段 `grip`～`release` 之间（`setHatInBraid`），两端停在片段给的位置，不同时出现在两处。
- 持物：`braid_item_hold` 是陆上持物叠加姿态（船上用 `boat_paddle` 首帧）；配方 `braid_use` 列出辫子自己在干活的片段，这些片段不叠持物，物品若片段没有自己的 `props.item` 就收在帽里不画；一次性片段用 `braid_busy`／`braid_free` 标记与持物姿态交接。没有持物片段的形态把物品画在嘴上（`braidKeepsStack`）。
- 避墙：`WallYield` 让空闲的辫子绕辫根转到贴脸颊为止；正在干活（夹取、够取、持物、托帽、`braid_use` 片段）的辫子只被压平，不改姿势。
- 身体朝向用服务端同步的 `BODY_YAW`，辫侧放下点才画在真实的辫侧。

## 4. 接触与入水

- 帽檐：辫子太短太粗，勾不过自己的帽檐。托幼油上帽檐时辫梢从辫侧地面兜起、在身体与帽子外侧走弧线、停在帽檐外沿与帽檐齐平，幼油自己迈上帽檐；放下另走一条路线（先在脸颊外侧卷起，再向外向下一大弧），不原路退回。
- 头顶：辫子在辫侧头外上方的中途点（`braid_head_take`／`braid_head_set` 的过头键）转向，再落到头顶；从静止直接转到头顶会扫过额头与帽子。头顶座位只在没戴帽时提供。
- 帽子：单一握点，帽子绕握点转，帽檐避开头部；持棍时木棍顺着辫梢勾住的那段帽檐贴着、随帽转（配方 `hat.stick` 按时间给方向，戴着朝辫侧外伸，翻过来放在水上朝水面外伸、不入水）。
- 嘴：辫长只够到辫侧嘴角外约 2.6 单位处，再近就切过脸角；送嘴的交接是食物从辫梢滑进嘴里，不把辫梢硬塞到唇上。
- 地面：拾取点是片段 `pick` 时刻的 `tip`，比嘴能叼到的位置远；站位按辫梢倒推，不按嘴的够取。辫子不低于地面（`braid_floor`）。
- 水：拨水片段的整个拉水半程辫梢在 `stroke.water` 水面下（`"hull"` 取帽船水线），`contact`／`stroke` 两标记就是实际入水与出水时刻（误差不超过一个采样）；岸边拨水的身体最低点落在岸顶（`root_support: "bank"`，不算辫子），辫子不进岸。帽船里坐着够不到檐外的水，靠向辫侧侧倾、略挪、绷过帽檐并有限拉长。
- 帽船：`hull_gate` 用蒙皮后的辫子和木棍轴核对每种帽船形态与船长偏转取样，任一采样穿入船体即失败；持棍划桨的辫子从辫根垂下、各节向外弯，辫梢伸出帽檐，不从头顶僵直抬起成倒 U。
- 穿插容差：制作门槛 `TOUCH` 为 0.1 单位；辫子进身体按超出静止接触的量算，帽子按各帽件的凸体深度算，托着的幼油用最小与最大两种帽檐幼油圆柱核对身体与帽子。

## 5. 种族适配

- 按用户 2026-11-27 验收口径，**翻垃圾桶与水中扑腾的发束／尾部适配严格按种族分工**：
  - **魔理沙**（`marisa`、`marisa_face`、`marisa_tsumuri`；`house_marisa` 仅水中扑腾）：7 骨单辫链（`braid_chain`），`bin_nose_in` 用 `braid_keys`（`root_weight` + `tip_curl`），`water_flail`／`water_struggle` 用 `hair_chain_keys`。被炉／稀发／豆形魔理沙留在嘴／纯身体路线。
  - **灵梦**（`reimu`、`reimu_face`、`reimu_deibu`）：6 骨纸筒鬓发链（`sideburn`），`bin_nose_in` 与 `water_flail`／`water_struggle` 均用 `hair_chain_keys`（小根角 `root_max ≤ 10°`、上段逐节缓弯、纸筒节刚性、发尾发力）。`house_reimu`、`reimu_bean`、`reimu_sparse`、`reimu_wasa*` 留在嘴／纯身体路线。
  - **帕秋莉**（`patchouli`）：6 骨长脸颊发束链（`cheek_lock`），`bin_nose_in` 与 `water_flail`／`water_struggle` 均用 `hair_chain_keys`（根部贴帽檐下方几乎不动 `root_max ≤ 10°`，中下段弯出、末端发力）。
  - **橙**（`chen`、`chen_longtail`、`chen_roundtail`）：默认翻桶用嘴（`bin_nose_in`）、扑腾用纯身体（`water_flail`／`water_struggle`）；**偶尔**按胆量随机（25%–40%）改播双尾专用片段 `bin_nose_in_tail`／`water_flail_tail`／`water_struggle_tail`（仅驱动 `chen_tail_left`／`chen_tail_right`，侧发 `loose_hair` 保持自然不乱动）。
  - **其余全部种族**（爱丽丝 `alice`、妖梦 `myon`／`myon_redeye`、蕾米莉亚 `remilia`、芙兰朵露 `flandre` 等）：翻垃圾桶只用嘴，水中扑腾只用纯身体，不做发束或翅膀扑腾／翻桶适配。
- 部位分类器 `author.py:appendage_group(model, bone)` 只将上述四类真正参与动作的部位归为 `braid_chain`、`sideburn`、`cheek_lock`、`chen_tail`，其余发束与翅膀一律归为 `loose_hair`（不参与主动驱动）。`hair_chain_keys` 与 `appendage_keys` 编译时统一过 `appendage_gate`（身体、头饰、地面、扑腾入水深度、身体局部系眼部不遮挡、单步转角 ≤ 40°、循环闭合，并打印 `root_max` 与 `distal_max`）；魔理沙的 `hair_chain_keys` 片段同步过 `braid_keys.continuity` 并导出 `tip` 轨道。
- 魔理沙各形态的取舍见第 1 节的表；把片段推广到新形态前在该形态上跑门槛，过不了就留在嘴或纯身体路线并写明原因。

## 6. 已踩过的坑

- 接管分支盖掉动作片段：惊慌步态盖住了托幼片段，辫子不动。任何新增或修改的接管分支都要先发布身体动作自己的片段。
- 删掉程序路线后片段成为唯一来源：之后任何选片遮挡都会让辫子完全不动；改选片要用托幼、接生等辫子场景冒烟确认日志里片段实际切换。
- 引导线折回自身：从垂直静止抬起时关节每采样翻转上百度、末端多转一整圈。用 `turn` 键；导出按游戏的逐通道欧拉插值核对，单节一个采样转动不超过 40°、通道不跨半圈。
- 直线转向穿脸：静止直接转到头顶扫过额头。转向先到辫侧头外上方的中途点。
- 倒 U 与单铰链：持棍辫根抬得太高成倒 U；辫梢瞄准只转末两节像一个铰链。辫根下垂、逐节外弯，瞄准份额铺到全部关节并逐节延后。
- 均匀滞后像一根弧：滞后与弯曲上限必须从根到尾渐变。
- 跟随叠到已铺好的辫子上：关键姿态与目标点片段排除在跟随之外。
- 交接时物品跟着辫梢扫过脸：持物交接要么整段收着，要么片段有自己的 `props.item` 并过 `stow_gate`。
- 帽子翻过来后木棍入水：木棍方向按时间分段给出，并核对最低点高于水面。
- 无辫形态物品消失：判断“辫子收着物品”只用一个函数（`braidKeepsStack`），无辫形态画在嘴上。
- 客户端朝向落后：用服务端同步的身体朝向，否则辫侧放下点画到身后。
- 共用骨架带进辫链：重新制作其他种族时剥掉辫链，否则编译崩溃或侧发被当辫子弯。
- 垂发前摆过大盖住眼睛：情绪前摆先看正面渲染条。
- 送嘴贴脸：嘴边键离开脸颊，`pose_check` 辫子与身体为 0；帽子净空在门槛边缘的片段要在报告里写明。
- 片段尾段压住下一表情：辫子进食片段结束前清掉进食状态，后续表情才能出来。
- 分家族导出与全量导出字节不一致：改完用 `export.py --rebuild-motion` 重建，再 `--check`。
- 够取量错站位：辫梢够取在转身后、从实际站位量；准入与执行同一几何。
- `braid_path` 目标离辫根过近时向内鼓进脸颊：`braid_chain.arc` 始终向前略下拱出余长，前探勾檐（如 `water_hat_climb`）时若目标离辫根明显短于辫长，中段会鼓进 `+X` 脸颊；把前探与勾檐关键点保持在外侧且距辫根约 8.5–9.8 模型单位（近绷直），即可避开脸颊并通过 `pose_check` 0 穿插。
- 引导线两头切向对向折成发卡：`braid_keys` 的 `root` 与 `arrive` 反平行或对向会让中段单步翻转上百度；高抬与拍水保持两头均偏下（`root.y < 0`、`arrive.y < 0`）且 `P0→P1→P2→P3` 沿外展轴单调递增，单步可压在 10° 内。
- 变体 `braid_keys` 被旧分支抢走或叠手势：`author.py` 的 `state()` 只把 `braid_brim_lift`／`braid_brim_lower` 交给 `BRAID_KEYS['state']`，有自写身体的新 `braid_keys` 片段要自己处理身体且从 `flourish_tracks` 移出，避免双写 `hand_right`。
- 推广到非标准形态漏查静止帽檐深度：`braid_keys.gate` 按含 `body_surface` 的部件找身体皮肤；先量静止 `braid_hat`（`marisa_bean` 静止已 `+0.069`、主动摆辫即超 `TOUCH`），过不了就留在身体路线。
- `RamContact`（`Shove`）把辫子顶点算进最前伸 `-Z`：用 `Shove` 的辫子片段（如 `bin_nose_in`）把辫梢世界 `-Z` 控制在站位到目标面距离内，否则电机反推身体后退。
- `braid_path` 不约束辫根出射方向导致辫根穿帽：`braid_chain.arc` 抬高或前探目标时会直接转动 `hand_right` 根骨，使 `braid_lock_root` 穿入自身帽檐与帽座（`brim_center`／`hat_base`，`water_hat_climb` 曾达 +0.52 单位）；且 `braid_path` 片段不走 `braid_keys.gate`。需要高位前探勾檐的动作改用 `braid_keys`（`braid_frame: "body"`），把 `root` 方向保持向下略外展（如 `[0.55, -1.0, -0.1]`），让辫根先从帽檐下垂出再向前弯曲，并用 `braid_keys.gate` 核对 `braid_hat <= 0.1`。
- 单骨长发束水中扑腾下沉超深与帽檐冲突：灵梦、爱丽丝／妖梦、帕秋莉与捕食种的静止侧发下沿低至 `y=0.00..1.90`，叠加扑腾时身体下沉（`-0.4`）与侧倾（`-6.5°`），外张角不足会把发梢压进水线 `1.8` 以下；扑腾需保持较大外张（`Rz ≥ 54°..64°`），帕秋莉因发顶紧贴睡帽檐（`y=10.02` vs `9.78`）须同时把外张压在 `≤ 67°`、前倾 `≤ 10°`；捕食种侧发向后延伸至 `z=+4.1`，负 `Rx` 会把后段抬进帽边，须保持 `Rx ≥ 0`。
- 橙的长尾与圆尾几何差异：`chen_longtail` 尾长至 `z=21.38`，扑腾时身体抬头（`+7°`）会把尾部大幅下压，必须全程抬尾（`Rx ≤ -6°..-16°`）并在翻桶时减小转体偏航（`96°`）以控制 `RamContact` 最前 `-Z` 在 `11.6` 内；`chen_roundtail` 球尾前缘伸到骨枢轴前方（`z=4.50 < 6.27`），过度负 `Rx` 反而压低前缘，且埋在臀窝内的根塞（`rest_z < pivot_z`）与露在体外的尾身须分开核对。
- 非凸／开口头饰穿插误报：蕾米莉亚／芙兰朵露的圆形帽边（`predator_cap_ruffle`）与灵梦后垂丝带（`bow_tail_mid_*`）不是单盒体，直接用三角有符号距离会在开口面背侧产生十余单位误报，用世界轴 AABB 又会在身体偏航／侧倾时膨胀并吞进圆边外的空角；`appendage_gate` 在身体根骨局部系内做 AABB 预筛并核对实际表面顶点距离（`< 0.45`），杜绝误报同时抓住真实穿帽。
- 辫根直抬成触角与单骨硬转：旧版 `water_flail`／`water_struggle` 的 `arrive.y < 0` 令辫梢上翘、根部斜抬 > 30°，灵梦与帕秋莉旧单骨更是从根部硬转 60–80°；改用多骨链 `hair_chain_keys`（根角 ≤ 20°、弯曲集中在中下段并带行波 `lag` 与重力 `sag`、末端 `tip_keys` 甩拍）和 `braid_keys` 的 `root_weight`（`1.2–1.45`）+ `tip_curl`，彻底消除触角感。
- 贝塞尔根手柄下冲与反平行折返：`braid_keys.Chain.lay` 若 `root_weight` 过大（`1.8–2.0`），低位目标会让第一控制点钻到地面下形成折返，而把该控制点的 y 下限错误锁到 `min(r.y, tip.y)` 又会把高位托举（`braid_brim_lift`）顶进帽檐；下限只锁在地面 `y=0.2`，同时保持 `root` 与 `arrive` 朝同侧外展（`root.x > 0, arrive.x > 0`，绝不反平行）、出入 `rest` 加 `turn`，单步角即可稳定在 30° 以内。
- 新加发束子骨必须追加到 `rig.bones` 末尾：在 `hair-chains/author.py` 切分灵梦／帕秋莉发束时，把新增骨全部追加在骨架末尾，不移动既有骨索引，避免破坏 `reimu_face` 等共享权重供体的索引映射。
- 跨整段线性蒙皮叠加单关节大弯角导致发束根部内拉变细：旧版灵梦 4 骨鬓发在 `[6.65, 9.328]` 整段线性混入 `lock_1` 权重，且扑腾时 `hand` 转 `21°`、`lock_1` 单节转 `45–47°`，把 `lock_1` 枢轴上方顶点反向拉向头内，令根部截面缩至 `0.78–0.83x`、内侧拉伸 `+49%` 并在太阳穴露缝；改为灵梦 6 骨链（`lock_1..4` 分布在 `7.80/6.55/5.25/4.00`）配以关节枢轴为中心的分段 smoothstep 蒙皮（`hair-chains/author.py:_joint_intervals`，灵梦与帕秋莉共用，根骨顶部刚性不反向受拉、纸筒段 `y ≤ 4.00` 刚性防穿筒）并把扑腾根角压至 `≤ 10°`、单节弯角压至 `≤ 27°`，根部截面恢复 `0.94–1.00x` 且静止轮廓零变化。
- `aquatic_tools.braid_rotation` 对左右 `hand_*` 对称套用大幅甩动会把魔理沙非辫子侧短鬓发（`hand_left`）当成第二条辫子乱甩，且只转根骨会把 7 骨单辫举成硬杆（如旧 `boat_thrown` 的 `side*(64+...)`）；魔理沙主动甩辫片段须把 `hand_right` 交给 `hair_chain_keys`（小根角垂坠、行波滞后与末端发力，经 `appendage_gate` 与 `braid_keys` 连续性核对），`hand_left` 仅保留小幅重力／惯性跟随。

## 7. 检查方法

命令在仓库根目录运行，`<py>` 见根目录约定，用法详见[脚本说明](../../scripts/README.md)。

- 导出与门槛：`<py> scripts/export.py motion <片段> --beats <节拍文件> --model marisa,reimu,patchouli,chen,alice` 导出所属家族，检查整身倾角、`braid_*` 片段的辫节连续性、节拍姿态报告并出渲染条；`--check` 不重导出。带辫子的家族：`brim-carry`、`braid-eat`、`dig`、`distress`、`aquatic`、`badge`、`paper`、`braid-water-rescue`、`bin-rummage`，跟随类在 `motion-corrections`。
- 辫子与发束专项：`<py> standards/yukkuri-library/motions/variants/lore/braid_keys.py --check` 对魔理沙关键姿态片段跑辫子、幼油与身体、帽子、地面的穿插门槛，对全部 `braid_*` 与 `hair_chain_keys` 辫子片段跑连续性；含 `hair_chain_keys`／`appendage_keys` 的变体在 `lore/author.py` 编译时自动跑 `appendage_gate`（核对 `app_body`、`app_hat`、`app_floor`、扑腾 `app_water`、`eye_hits`、单步转角、循环闭合与 `root_max`/`distal_max`）；`<py> scripts/export_motion_corrections.py --check` 核对整份动作覆盖；`aquatic_tools` 的 `stroke_gate`、`held_gate`、`stow_gate`、`hull_gate` 在水上家族导出时自动跑，`<py> scripts/check_pose_consumers.py <基线> --lore --stow` 复核持物交接。
- 离线看图：`<py> scripts/preview.py strip <主题> --model marisa --clip <片段> --times ... --view 0 4 --view 90 4`，至少看正面（辫子在画面左）和辫侧；`--wall` 看避墙；`--before <ref>` 出新旧对比。划桨与拨水看 `lore/aquatic_paddle_preview.py`（`--form`、`--against`）与 `lore/aquatic_strokes_preview.py`。逐帧数值用 `<py> scripts/pose_check.py --model marisa --clip <片段> --t 0:<时长>:0.05 --metric tilt,pass[,wet]`。
- 读哪些帧：每个标记时刻（`contact`、`lift_start`、`place`、`release`、`pick`、`mouth`、`power_start`、`power_end`、`grip`）；每段 `turn` 的起点、中点、终点；门槛报告打印的最差时刻；首帧与末帧（与前后片段的衔接）；跨水面扑腾另核最低点高于 `1.8`（水线 `4.0` 下最多入水 `2.2`）且不低于地面。
- 交给用户：`<py> scripts/preview.py video <主题> --model marisa reimu patchouli chen alice --clip ... --before <基线> --decision <要用户决定什么>` 出单页 MP4 对比视频登记到待你查看页（动作验收优先视频，不再堆多帧静态图条）。

## 补充规则

每个碰辫子或发束的包（动作、接触、持物、选片、种族适配），在同一提交里把本次学到的做法或踩到的坑补进上面对应小节；与现有条目冲突时改写原条目，不追加矛盾的新条。改了骨架、参数、门槛或命令的，同时改本页对应数值。

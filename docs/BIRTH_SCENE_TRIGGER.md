# 真实分娩触发入口

真实分娩由试玩场的 `/yukkuri scenario birth <母> <父> <natural|caught|missed>`（管理权限 2；不含 `overstrain`）触发。旧的 `/yukkuri birth_scene deliver` 与 `BirthScene*` 类已随旧展馆删除。它提前真实胎次的临产时间，不搬运双亲或幼体、不创建备用幼体、不临时制造接生帽。

- 双亲是同维度、八格内的活着成年油库里，普通 AI、带 `scn` 标签。

- 双亲带正常 AI 与重力，不使用 `NoAI` 演员或动作片段命令。
- 陆地或岸边的母油应在真实可用窝位两格内；水上母油已经乘坐自己实际拥有且浮起的帽船。入口不会用坐标替代认窝、登船或下水。
- 新胎次走 `FamilyData.conceive(..., LIVE, ...)`，保留生育能力、年龄、体况、营养消耗、容量、亲缘和既有配偶约束。仅此次管理命令临时跳过相识／亲密度门槛。
- 可以接续正常受孕产生的既有胎次，但必须是同一双亲、尚未开始分娩、没有已出生子代的 LIVE 胎次。不会修改孩子 UUID、遗传、种类或父母。
- `caught` 与 `overstrain` 要求当前存在可用于接生的双亲帽子，或母油已在自己的真实帽船中；`missed` 不需要帽子。父油接生仍须自行走近并摘下现有帽子。

## 没有窝位时（2026-10-18 重写）

孕期时钟在母体健康、不受惊、不饿不病、没在睡觉进食时**无论身在何处**都走，直到最后 400 tick（`FamilyLife.NEST_GRACE`）；此前它只在认定的窝旁推进，窝丢了、太远或母油外出觅食时，胎生没有腹部变化、茎生没有茎，也永远生不出来（`placeForTerm` 是现在的唯一判断）。最后一段：在窝旁（两格内）、乘着自己的浮起帽船（水生）、水边岸上，或没有窝可回时在稳固地面上等约 100 tick 就地分娩。夜里睡着的母体临产前 60 tick 会被叫醒（`isSleeping` 曾使夜里的临产一直等到天亮，帽里睡着的则一直不生）。

`FamilyLife.nestStand()` 是家庭代码问“窝在哪”的唯一入口（现在转到 `life().homeStand()`），“家庭大窝”落地后只改这一个方法的函数体。`FamilyLife.birthAnchor()` 给出人口预算用的锚点：窝，否则船，否则站立处。

产程被什么卡住，可以直接问：`/yukkuri status <母> raw` 里 `reproduction.birth_block`（`StatusKey.BIRTH_BLOCK`）会给出原因（`mother_not_ready`、`no_place_for_term`、`mother_moving`、`waiting_for_hat`、`waiting_for_catcher`、`outlet_blocked`、`no_room_for_belly`，或账本拒绝的 `boat_has_no_room`、`population_limit`、`newborn_space_blocked` 等）。

场景代码要让一对油库里“马上自然开始生产”：见 `FamilyLife.prepareBirthScene` 的做法，把 LIVE 胎次 `elapsed` 提前到 `duration-2`（接生场景用 `duration-150`），双亲 AI 启用、母体脚下稳固不动、不睡眠不进食；陆上父油须是戴帽的魔理沙并认识母油。

## 水上接生（2026-10-18 重做：不要伴侣、不要岸）

水生魔理沙乘在自己浮起的帽船里生产，**不需要伴侣，也不需要靠岸**。（旧文档里“临时靠岸”“岸上戴帽的父油接住”不再成立；`bankNest` 只在她恰好已经在岸边时才算临时窝。）

- 新生儿从出口落进她身前的帽内，经 `HatBoatEntity.hasRoomFor / requestEmergencyBerth / reserve` 规划真实座位（先自己的座位，满了用压缩的避难座），再由 `FamilyLife.launchInto` 抛过去。无人接生也照常生。
- **有伴侣时**：伴侣的帽船在最后一段产程里自己划到她的船边（`FamilyLife.tickWaterPartner`，间距 `STATION_GAP=2.1` 格，只用 `paddleTo/holdPosition`），之后新生儿隔一只送一只到伴侣的帽里（`FamilyData.waterCradle`，自己的帽满了则其余全送过去；最远横越 `FamilyLife.SHARE_REACH=3.2` 格，伴侣的座位够不着就留在母亲帽里）。伴侣不必在岸上，也不必在附近的陆地。
- **漏接 = 落水**：`overstrain`、`missed` 和自然 12%（`FamilyLife.missesDelivery`）使新生儿飞过帽沿落水，进入溺水规则（`WaterDistress.plunge`，不会游，每秒扣血，亲油辫子救援及时才能活）。陆地漏接仍是摔伤。
- 修好的几处卡死（以前每一处都会让水上产程停住或让新生儿落水）：`hasRoomForYoung()` 要求戴着帽子的新生儿座位避开主人的整个身体投影，对满尺寸帽船永远为假，出口被判定“堵住”（`LiveBirthGeometry.clear` 现在只看浮起与载员上限，座位交给真实座位规划）；新生儿起跳的扫描把母亲（帽船乘员）、同胞当成障碍；母亲的体重碰撞把新生儿从抛物线上挤开（飞行中 `isPushable=false`）；帽子的碰撞盒把新生儿挡在井口之外（`boardingThrough` 现在包括飞向这顶帽的新生儿）；浮帽随波移动超过 5 厘米就放弃，改为跟随移动的座位并放宽接触判定。

## 四种分支

- `natural`：保留正常随机分支。
- `caught`：这胎选择正常投向帽中的路线，仍服从实际位置、空间、碰撞和席位。障碍或帽子被移走仍可导致失败。
- `missed`（2026-10-17 重定义）：父油来不及，这胎不放帽子：不预先提前到预产前、父油在第一只落地前不会进入接生职责，也不会摘帽。新生儿直接落到地面，弹一下（落地声、扬尘），受一处真实的摔伤（经 `hurt(fall)` 扣最多 2 点生命，保留至少 1 点，不致命；由 `BodyLife.injured` 记录为凹痕，之后按既有伤口／感染／照料规则处理）并哭泣（泪滴、呜咽声）；父亲被惊动后赶来蹭脸、轻舔，母亲产后先惊慌再蹭脸、轻舔安抚。
- `overstrain`：帽子已就位但被推过帽沿（漏接的另一种形式）。**水上（帽船或岸边浮帽）= 溺水**：新生儿落水后不再被直接判死，只进入普通落水规则（`WaterDistress.plunge`），赤油不会游，按沉水每秒扣血，亲油的辫子救援（`BraidRescue`）及时救起才能活，否则溺亡并留下遗体。**陆地 = 摔伤**：同 `missed`，进入伤势系统，不致命。

`caught` 在父油为接生者时，会把临产提前到预产前约 150 tick，使他有时间察觉、走到母油出口前、摘下帽子翻面放好（`HatBoatEntity.setDown`，帽子真实离开头顶，成为帽实体）并扶住帽沿；之后落帽、问候、放出幼体、戴回帽子沿用既有流程（`tickHat`）。父亲约 15 秒内到不了位、帽子无法放下或被阻挡时，该胎改为无垫落地（失败路径）：本应由魔理沙接生（`FamilyLife.catchExpected`）却落地时没有帽，新生儿同样摔伤并被安抚，自然生产也如此，不再只限展馆指定的 `missed`。

接生职责由服务器每 tick 主动领取（`FamilyLife.claimMidwife／tickMidwife`），不再依赖目标选择器能否轮到家庭目标；走位使用带宽限的到位判定（约 5 秒仍不到精确点时放宽到 0.8 格）以避免原先父油停在差一点的位置永远不动手。

参观者会在聊天栏看到灰色“[接生]”进度行，只有带 `scn` 标签的试玩场个体才会发出（`BirthStaging.caption`），内容为服务器实际到达的状态。

场景命令只调用 `FamilyLife.prepareBirthScene(brood, outcome)` 提前到 `duration - 2`（`caught` 为 `duration - 150`）；服务器按正常流程生成唯一真实幼体，并完成接生、产后恢复与辫子拾幼。重复播放应由展区自己的真实家庭追踪／重置流程管理；不要先预放赤油，或以逐帧命令更改坐标。

## H 试玩场接入

试玩场的四个接生场景（陆地／帽船 × 接住／漏接）由 `scripts/museum_scenarios.py` 生成一对普通 AI 的双亲，再用 `/yukkuri scenario home` 认窝或 `/yukkuri scenario boat birth` 放上帽船，最后 `/yukkuri scenario birth <母> <父> <caught|missed|natural>`：它经正常的 `FamilyData.conceive` 受孕，`caught` 把临产提前到 `duration - 150`（有帽父油）或 `duration - 2`，`missed` 调用 `prepareBirthScene(brood, "missed")`。新生儿的身份来自家庭账本；“清空本场地”会先取消被清走母油的胎次，再移除实体。

本机未编译、未运行游戏。命令解析、分娩连续效果、接生碰撞、死亡分支与读档续接仍需远端构建及可运行环境确认。

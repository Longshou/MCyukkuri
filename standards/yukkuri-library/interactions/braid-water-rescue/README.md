# 单辫水面救援

> 2026-10-18 第 5 版（赤油辫侧下沉，项目原创）：不能咬缘的赤油浮得低，直立辫根够不着。新增 `reach_lean`（侧倾 -10°、前倾 -5°、竖向挤压 0.84、向辫侧平移、`ease_ticks` 8），导出时另算 `reach_lean_lift`（让最低点留在甲板上的抬高）与 `reach_lean_root`（该姿态下的辫根，模型单位）。动作片段 `braid_reach_low`（`motions/variants/lore`）读取同一份数据；服务器 `BraidRescueGeometry.leanRootAtYaw` 用同一根骨姿态测辫子可达。导出顺序：先本目录 `author.py`，再 `lore/author.py --export-distress`。

> 2026-10-17 第 4 版（压低近缘与咬缘重做，项目原创，非作品设定）：辫子不再托举、翻转或承力，只握桨或轻触帽缘；`root_pitch_degrees` 为 0，不再整身前倾；新增 `mouth_root`、`mouth_reach`、`approach/offer/bite/push/slide_ticks` 与 `boarding_body_pitch_min` 等键，`boarding_body_bounds` 俯仰范围扩到 -18～+6。以下关于两节辫托举、`RootBlend` 的描述是旧版（保留 id 兼容旧存档与旧预览），不再是现行流程；现行流程见 [水生魔理沙实现](../../../docs/AQUATIC_MARISA_IMPLEMENTATION.md)。

制作源为 `recipe.json`，`author.py` 从当前魔理沙模型导出单辫接触几何到 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json`。对应身体动作在 `motions/variants/lore/recipe.json`，不更换原模型或增加辫子。

接触点取已有发梢网格的实际末端；`hand_right_tip` 骨骼转轴是两节辫子的真实关节，不能与末端接触点混为一点。制作源导出根至关节、关节至发梢的两段自然长度，服务器以两段三角形求出实际 `Joint`，客户端分别反解两根现有骨骼，不增骨、不伸长，也不把辫梢锁成直杆。根部俯身和对应支撑抬升以 `RootBlend` 同时进入服务端根锚与客户端模型：探身时缓入，孩子站稳后先松开发梢再缓退。

服务器联合选择亲油的接触站位、放下站位和孩子这一侧的合法近缘空位。优先让亲油留在近檐接触位，将孩子放到同侧空位，减少搬运；必须移步时，先取得与真实帽面步行一致的路线。抓稳前预扫整个抬起、移步、转入和放下过程：使用孩子身体、实际戴帽外包络及亲油俯身后模型身体边界避让帽壁、亲体、同胞、货物和方块；点轨迹可达不能代表身体可穿过。直线转入受阻时允许同高度有限侧弯，仍须逐段通过体积与两段自然辫长约束。

托举只抬到体积越过实际帽缘所需的净空，不设置朝头顶的高举点。`LIFT` 与低位转入使用两次发力及中间停顿：`0.58*S((q-.15)/.23)+0.42*S((q-.62)/.23)`，其中 `S` 是截断后的 smoothstep。`PLACE` 区分沿帽面行走与低位转入，`SETTLE` 短降放稳，`RELEASE` 前半仍接触，后半在真实支撑成立后松开并收辫。服务端同步 `Duration`、`Travelling`、`RootBlend`、`Progress`、`Effort`、`Joint/PrevJoint` 和 `Tip/PrevTip`，客户端不另搬动孩子。

制作源同时导出 65 级根部俯身的真实足面支撑抬升，以及攀檐用 -18～0 度俯身、-3～3 度侧倾的实际身体网格边界。服务器和客户端在相同姿态下求两段辫子的关节；不能把最终抬升线性乘入中间角度，也不能旋转一个装有大量空角的身体 AABB 来人为抬高小子油。攀檐期间根部保持原缩放，`RootPitch/RootRoll` 与实际分次发力进度同步，真正接触前只侧转并自然浮水。

登船的抬身顶点只采用 `LIFT` 末姿态（俯身 -18 度、侧倾 0 度）所需的实际净空；过程中每帧另按真实来源帽、目的帽和当前身体／戴帽外形投影支撑，不把未来侧倾的临时净空永久加到顶点。来源面上的 `GRIP` 同样按实际身体最低点微调真实脚位，并以其末位置连续进入抬身。越檐时，身体在同一握点约束下缓慢侧转，腹部位置与朝向联合选择，实际转向不超过每 tick 8 度；阶段时长随必要转角延长。`SETTLE` 进度 0.42 起获得完整支持后保持身体最低点贴住支撑面，0.50 时确认持续实际支撑才松开辫梢，再落稳；松握后仍受姿态体积和支持路径约束。

```powershell
python -B standards/yukkuri-library/interactions/braid-water-rescue/author.py
python -B standards/yukkuri-library/motions/variants/lore/author.py --export-distress
```

以上仅导出资源。当前离线预览位于 `.local/previews/hat-motion/index.html`，生成源码为 `motions/variants/lore/hat_motion_preview.py` 与对应 HTML 模板，产物不入库。

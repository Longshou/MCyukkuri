# 单辫水面救援

制作源为 `recipe.json`，`author.py` 从当前魔理沙模型导出单辫接触几何到 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json`。对应身体动作在 `motions/variants/lore/recipe.json`，不更换原模型或增加辫子。

低位救援时，亲油向辫侧下沉：`reach_lean` 侧倾 -10°、前倾 -5°、竖向压缩 0.84、横向缩放 1.06、辫侧平移 0.6 单位，8 tick 缓入缓出。`reach_lean_lift` 按同一姿势支撑身体最低点；动作片段 `braid_reach_low` 与服务端 `BraidRescueGeometry.leanRootAtYaw` 共用该数据，辫段保持自然长度。整身倾斜总量约 11.2°。

接触点取已有发梢网格的实际末端。辫子是一条骨链（`hand_right`、`hand_right_braid_1`～`_5` 与发尾 `hand_right_tip`，见[魔理沙单辫](../../body-standard/features/marisa-single-braid/README.md)），各关节不能与末端接触点混为一点。制作源导出辫根 `braid_root`、静止末端 `braid_tip`（服务器的辫子够取距离取两者直线距离）、各关节 `braid_joints` 与沿关节的全长 `braid_chain_length`；全长不小于直线够取距离，服务器判定够得着的点，渲染的辫梢都能真正碰到。`tip_joint` 与两段长度仅供旧预览读取。客户端 `BraidPose` 把整条骨链弯向目标（`motor/geom/BraidChain`：圆弧、各段保持长度、相邻两节转角不超过 40°，过近时继续折叠、过远时伸直），不伸长，也不把辫梢锁成直杆。根部俯身和对应支撑抬升以 `RootBlend` 同时进入服务端根锚与客户端模型：探身时缓入，孩子站稳后先松开发梢再缓退。

服务器联合选择亲油的接触站位、放下站位和孩子这一侧的合法近缘空位。优先让亲油留在近檐接触位，将孩子放到同侧空位，减少搬运；必须移步时，先取得与真实帽面步行一致的路线。抓稳前预扫整个抬起、移步、转入和放下过程：使用孩子身体、实际戴帽外包络及亲油俯身后模型身体边界避让帽壁、亲体、同胞、货物和方块；点轨迹可达不能代表身体可穿过。直线转入受阻时允许同高度有限侧弯，仍须逐段通过体积与自然辫长约束。

托举只抬到体积越过实际帽缘所需的净空，不设置朝头顶的高举点。`LIFT` 与低位转入使用两次发力及中间停顿：`0.58*S((q-.15)/.23)+0.42*S((q-.62)/.23)`，其中 `S` 是截断后的 smoothstep。`PLACE` 区分沿帽面行走与低位转入，`SETTLE` 短降放稳，`RELEASE` 前半仍接触，后半在真实支撑成立后松开并收辫。服务端同步 `Duration`、`Travelling`、`RootBlend`、`Progress`、`Effort`、`Joint/PrevJoint` 和 `Tip/PrevTip`，客户端不另搬动孩子。

制作源同时导出 65 级根部俯身的真实足面支撑抬升，以及攀檐用 -18～0 度俯身、-3～3 度侧倾的实际身体网格边界。服务器和客户端在相同姿态下求两段辫子的关节；不能把最终抬升线性乘入中间角度，也不能旋转一个装有大量空角的身体 AABB 来人为抬高小子油。攀檐期间根部保持原缩放，`RootPitch/RootRoll` 与实际分次发力进度同步，真正接触前只侧转并自然浮水。

登船的抬身顶点只采用 `LIFT` 末姿态（俯身 -18 度、侧倾 0 度）所需的实际净空；过程中每帧另按真实来源帽、目的帽和当前身体／戴帽外形投影支撑，不把未来侧倾的临时净空永久加到顶点。来源面上的 `GRIP` 同样按实际身体最低点微调真实脚位，并以其末位置连续进入抬身。越檐时，身体在同一握点约束下缓慢侧转，腹部位置与朝向联合选择，实际转向不超过每 tick 8 度；阶段时长随必要转角延长。`SETTLE` 进度 0.42 起获得完整支持后保持身体最低点贴住支撑面，0.50 时确认持续实际支撑才松开辫梢，再落稳；松握后仍受姿态体积和支持路径约束。

```powershell
python -B standards/yukkuri-library/interactions/braid-water-rescue/author.py
python -B standards/yukkuri-library/motions/variants/lore/author.py --export-distress
```

以上仅导出资源。当前离线预览位于 `.local/previews/hat-motion/index.html`，生成源码为 `motions/variants/lore/hat_motion_preview.py` 与对应 HTML 模板，产物不入库。

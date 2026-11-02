# 鼓腮、体操与辫子跟随

动作配方见 [recipe.json](recipe.json)，关键帧由 [author.py](author.py) 生成；运行资源由 `scripts/export_motion_corrections.py` 导出。本包只写动作骨骼和表情时间线，不改选用模型、皮肤网格或伤口几何。无颈圆团子的身体以整体挤压、拉伸和小幅位移表达情绪，身体前倾、侧倾总量约 12°（含周期摆动峰值）。

## 鼓腮与体操

`puff` 曲线由 `puff_seconds`（2.8 秒）和 `taiso_puff_seconds`（3.2 秒）控制：吸气轻微压缩，鼓起到标准体型的约 1.14 倍，保持期加入小幅呼吸与颤动，随后泄气并平滑回落。完整体操按配方中的伸展、侧倾、转体、跳动、鼓腮、恢复片段编排；最高跳点约为身体高度的 38%，伸展不超过 12%。采样步长为 0.02 秒以保留高频颤动，其余动作使用 0.04 秒。落地补偿保持身体最低点贴地。

表情由 `expression_spans` 编排：进入鼓腮阶段切换至 `puff`（缺少时使用 `cheek_puff`），泄气阶段转入 `cheek_puff`；受伤哭脸优先。`cheek_puff` 沿用愤怒脸眼眉，以标准鼓腮嘴形和专用几何补丁构成。缺少该贴图的形态复用灵梦鼓腮嘴部样式。

## 魔理沙辫子跟随

`braid_follow` 只作用于 `hand_right` 及其下整条骨链带动的辫子。跟随身体扭转曲线后，再求最小外摆角，使辫子不比静止基线多进入皮肤 0.02 以上，并让辫梢离地至少 0.03；外摆上限 30°、搜索步长 1°。峰值保持（时长另加骨链滞后，让下段经过同一扭转时仍保持外摆）后平滑回落，循环动作首尾衔接。适用动作在原曲线上叠加此跟随；稀毛辫子贴在头皮上随皮肤变形，不应用该修正。螺壳缩入使用后仰、外张的头发姿势。

辫子是骨链（`hand_right`、`hand_right_braid_1`～`_5`、发尾 `hand_right_tip`）。凡摆动辫根的动作，`braid_chain.py` 把辫根的摆动作为行波沿骨链逐节传下：每节比上一节晚 `lag_seconds`、相对上一节的弯曲按 `max_bend_degrees` 封顶，两者都按 [辫根, 发尾] 渐变（根硬而滞后短，尾软而滞后长，发尾共晚约 0.32 秒）；辫子偏离竖直时每节按 `sag_degrees` 下垂。片段可给受力窗口 `load`（`start`／`end`／`ramp`／`tension`），受力时弯曲与下垂收直，出水时放开。静止和保持姿势不产生滞后，一次性动作在最后 `settle_seconds` 内收回滞后；动作自带的发尾转角叠加在最后一节上。取帽、戴帽、托帽与水生护帽由 `lore/hat.py` 解骨链，与游戏共用圆弧算法（`motor/geom/BraidChain`）。

划桨类片段（`lore/aquatic_tools.py`）先传下摆动，辫根只小幅前后摆、以下各节按 `paddle_shared.curl` 逐节向外弯（辫子从根部垂下、末梢伸出帽檐，不是从头顶僵直抬起的倒 U；`boat_paddle` 回桨时按 `braid_raise` 再卷高末梢），再由 `paddle.aim` 的最后几节按份额、限速把辫梢转向桨叶目标，木棍按自身倾角垂在辫梢缠握处；`paddle` 的共用参数在 `lore/recipe.json` 的 `paddle_shared`，各片段只覆盖差异。`hull_gate` 用蒙皮后的辫子和木棍轴核对 `hull_profiles/` 每种帽船形态（船长坐在座位上面朝船首，偏转在 `captain_yaw` 以内、按 `captain_yaw_step` 取样，与 `Paddle.FACING` 一致）；任一采样穿入船体，或 `boat_paddle` 拉水段中段桨叶未入水到 `depth` 一半，导出即失败。

圆弧解算把向前、向下的合向量投影到目标弦的法平面；目标在正前、正后附近时弯曲面连续。`braid_cradle_lift` 保留原发尾路径，用同一解算重新烘焙骨链，并在合拢前与松开后渐变回原动作；`braid_rescue.json` 保存接触几何及解算源指纹，实际动作在 `motion_corrections.json`。

更新动作源或配方后，从仓库根目录运行 `python scripts/export_motion_corrections.py`；局部试验可用 `--only id,... --trial <目录>`，只核对现有输出用 `--check`。导出检查入口见 [脚本说明](../../../../../scripts/README.md)。

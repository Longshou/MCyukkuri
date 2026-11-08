# 姿态检查接口

`PoseChecks.evaluate(frames, metrics)` 是截图端与离线命令共同使用的只读入口，依赖 Gson、JOML，不读取游戏对象。七项检查均只报告；尚无经过用户审图的零误报基线，不能作为玩法验收或合入门槛。F 样本见 `PoseMetricsTest`，不是历史画面的复原。

## 坐标与结果

每帧沿用观测层 `pose` 信封：`v, run, seq, t, dim, win, u, k, src, pt, f`；`t` 为游戏 tick，`pt` 为小数 tick，`ct` 为片段秒数。离线 `--t` 使用秒。结果保留时间、主体与帧号，包含 `metric, value, unit, band, verdict, where`。`value=null` 表示无可测数值；`error` 是缺数据或无效输入，`skipped` 是定义上不适用，二者均不是通过。`band.basis` 区分固定几何门槛和未校准假设。

所有几何点、网格及矩阵平移均在同一观测坐标系，单位 mu（1/16 格）；矩阵为 JOML 列主序 16 数组。水面 `water.point` 为该坐标系的格数。`gap`、`held` 输出格，`pass`、`wet` 输出 mu，`tilt`、`yaw` 输出度，`posejump` 输出度/秒。客户端须将摄影机相对点、水面和服务端锚点转换到同一坐标系后提交。

## 每帧证据

公共字段为 `model, fp, clip, ct, markers, phase, contact, yawsrc, yaw, root, sup, item, water`。`fp` 是链指纹：模型 LF SHA-256，依次拼接实际采用的 placement_fit、motion_corrections、house_ecology、toilet_contact、face_library 的名称及 Gson JSON，以换行分隔，再取 LF SHA-256；不等同于伤口使用的基础网格指纹。`markers` 为本采样区间跨过的标记名，`clip_markers` 为完整秒数表；`parameters` 是片段参数对象（没有参数时显式 `{}`）。原始证据不足的普通捕获帧不能凭旧 `m` 重新宣布通过。

| 检查 | 额外证据与定义 |
| --- | --- |
| `tilt` | `root` 是最终身体矩阵；`sup.kind=ground` 用世界上轴，`hull` 用帽船矩阵，`carrier` 的 `sup.frame` 必须是承载者头骨矩阵。`SUPINE/FIXED/HOOK/ROLL` 不适用，RUMP/ASSIST 仍计算最终上轴。`parameters.tilt_max` 必须带 `tilt_why`，缺省上限 12°，超出 0–3° 为疑似，更多失败。 |
| `pass` | `meshes` 是命名网格表，每项 `{closed, triangles:[[[x,y,z],...],...]}`；`pairs:[{a,b,allowed:[{center:[x,y,z],radius,why}]}]` 显式声明辫身、幼体与承载者、帽与伴侣身/发、持物与身体、乘员与帽等配对。允许区可声明嘴半径、辫根环、座面接触区、片段 contacts；必须给出真实接触位置和理由，不自动允许整对网格。检测三角面穿越深度与闭合网格内点到表面的深度，取最大值，>0.1 mu 失败。开放帽网格不得设为闭合；不补面、不取凸包。只对声明的配对下结论。 |
| `gap` | `skin_points` 是骑乘者最终皮肤点；`sup.mesh` 是真实支撑三角网格。取支撑上轴上的最低皮肤点，沿上轴测最近支撑面的有符号间隙，按绝对值判定；无对应支撑面报错。≤0.03 格通过，≤0.1 疑似，其余失败。 |
| `wet` | `parameters.stroke.depth` 为 mu；仅在 `clip_markers.contact..stroke` 内计算；`wet_tip` 必须为真实 BraidChain 终点或道具声明的 blade tip 经最终绘制变换后的点。水深至少 depth/2 通过，0 到 depth/2 疑似，高于水面失败。无 stroke 参数但有 `power_start..power_end` 时，动力段内报告缺少入水深度与桨尖契约，段外不适用；两者均无才视为无划水动作；活动区间缺水、未加载水面、缺尖端均报错。 |
| `held` | `item:{held,id,place,drawn,grip,anchor}`，held 为服务端实际占用；drawn 是混合后的最终绘制矩阵，grip 为道具局部握点，anchor 为持有部位的最终锚点。drawn 显式 null 为持物未画出的失败；缺字段报错。距离≤0.02 格通过，≤0.08 疑似，其余失败。 |
| `yaw` | 仅 `yawsrc=body`；客户端提供制作片段根骨转动前的 `body_yaw`，与 `server:{t,by}` 比较；后者必须来自同 tick 的密集服务端采样。离线或旧输入缺少 `body_yaw` 时沿用 `yaw`，旧捕获不能据此声称已排除制作片段转动。包角差≤10°通过，≤30°疑似；>30°连续至少 5 tick 失败，之前为疑似。重复同 tick 的渲染帧按 pt 计时，不按帧数计时，缺采样不跨越累计。 |
| `posejump` | `bones:{name:matrix}`、`transition`（是否处于制作源声明的过渡）。骨骼先按 `pose_basis` 换回制作坐标系，再按连续帧四元数最短转角算角速度；初帧、相隔>1 tick、骨架不符或两帧的 `pose_basis` 一侧缺失报错。过渡期不适用。夹在其他片段间且持续<3 tick 的片段失败，需要后续帧才可判断；180/720°每秒的速度带是暂定假设，尚未校准。 |

客户端记录 `pose_basis`（制作片段前的实体绘制矩阵）；骨骼先变换回此坐标系，避免实体或承载者整体转向被当作骨骼抽跳。缺少此字段的旧输入仍按原始矩阵计算，不声称已消除整体转向。客户端以连续渲染帧号和单调 `render_seconds` 计算姿态角速度，避免游戏校时倒退；离线采样仍要求间隔不超过 1 tick。片段或阶段切换显式标为过渡。客户端 `gap_applicable=false` 只用于非乘员和嘴叼侧向接触，后者仍检查幼体与承载者的穿透。`pairs_complete=true` 的空配对明确表示该帧没有适用配对；缺配对字段仍报错。

时序检查按 `run,win,u` 隔离，输入必须按同一主体的时间顺序提供。掉帧、缺服务端同步、缺 prop tip 或 partner 网格不能由检查器补造。`--serve` 每行一个独立请求；时序窗口必须放在同一请求的 `frames` 中。

## 离线输入

`scripts/pose_check.py --model marisa --clip bank_beckon --t 0:1.6:0.05 --metric tilt,wet,pass [--water 0.5] [--partner child=marisa:baby@0,8,-2] [--care x,y,z]` 使用 G1 的 Splice、Clips、Compose、Skin、Overlays。伙伴是 idle；adult 使用标准比例，baby 使用 family.json 胎生预览比例，juvenile 使用该阶段区间中点；这些是预览初态，不代表游戏中具体个体。`--care` 调用现有后部照料解算，目标为 mu。

离线默认只采样制作片段与指定 care，不选择业务或模拟客户端动态叠加。皮肤输入明确为中性表情、无伤口、无鼓腮和妊娠，骨骼包含片段形变；其他实际形变应由捕获端提供最终网格、皮肤点和骨骼。`--vertices out.f32` 输出最后一个时刻的全部皮肤 xyz，float32 小端、mu。`--pose-out pose.jsonl` 输出同一信封、骨骼、显式皮肤输入与 `m`，可用 `--frames pose.jsonl --metric ...` 回放。

离线 `pass` 默认检查模型辫子与身体，以及已给伙伴的身体接触和帽与身/发；辫根允许球由实际根环网格范围推出。模型未提供的帽空腔或道具不得合成。片段 `contacts` 若存在，使用与 pairs 相同的 `{a,b,allowed}` 记录追加局部允许区。`--context evidence.json` 可补充/覆盖每帧原始证据（例如支撑、prop tip、持物、pairs），用于制作输入，不能用臆造数据替代捕获。没有任何可检查配对时报错。

服务请求格式为 `{"model":"marisa","clip":"idle","t":"0:1:0.05","metric":["tilt"]}` 或 `{"frames":[...],"metric":[...]}`；可带 `partner` 字符串列表、`care` xyz 数组、`water` 数值、`context` 对象、`vertices` 和 `pose_out` 文件路径。单次命令输入/证据错误退出 2；有效检查（包括 fail）退出 0。服务保持一行输入一行 JSON，错误在该行返回，不中断后续请求。

制作工具通过 `scripts/pose_client.py`／`pose_client.cjs` 复用同一 JSON 行服务。未导出的骨架用 `op=compose`，输入 `bones`（父索引）、`defaults`／`tracks`（标准轨道）、`time`，或直接给 `values`（每骨骼 position、rotation、scale 共九值）；输出 `world` 是逐骨骼 3×4 行主序矩阵。帽缩放可给 `hat`、`hat_scale`、`hat_anchor`。`op=arc` 接收平铺的 `joints` 和 xyz `target`，调用运行时 BraidChain 的固定 40° 关节限制。

光栅预览先用 `op=register` 提供 `model`、可选 `fit`／`revision`，取得 `id` 与拼接后的模型；随后 `op=render` 提供 `id`、`action`、`time`、`expression`、`scale`，可带 `adjustment`、`intensity` 或 `care:{anchor,target,blend}`，返回八值顶点（xyz、uv、法线）、行主序骨骼与根姿态。顶点经共享 Skin，护理经共享 Overlays；预览不再自算骨骼。两个活动模型常驻，其他登记输入压缩保存，用完调用 `op=release`。交互页通过本机预览服务的 `/pose` 接口使用相同协议，需要 JDK；不适合直接以文件方式打开。生成模型序列的 `pass` 即算即释放碰撞网格，避免按采样数累计网格内存；要求完整 `pose_out` 的证据仍会保留。

F 覆盖：29=`row29BraidAndChildPenetration`，30=`row30BraidAndDeclaredBladeWet`，31=`row31TiltRelativeToCarrierHead`，32=`row32FloatingRiderGap`，33=`row33SustainedBodyYawLagAndWrap`，34=`row34ClipFlickerAndAuthoredTransition`，35=`row35HeldSwordAnchorAndMissingDraw`，36=`row36HatCutsHairAndHollowControl`。另测无内部顶点的薄面交叉、空心帽、覆盖不足的局部允许区、帽船支撑与 12–15° 疑似带、单位边界、缺证据和确定性。未查摄影可读性、未声明配对或允许区内的穿模、采样间不可见的动作以及没有原始数据的历史截图；速度带与间距带仍需按观测层校准规则审图。

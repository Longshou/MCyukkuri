# 头饰与头发位置适配验收记录

2026-10-07，第一版，造型状态为待用户复查。适用资源：`yukkuri_presentation/placement_fit.json`（SHA-256 见 [manifest.json](../../manifest.json) 的 `runtime_sha256`），配方与生成器指纹同见该清单。以下均为离线检查与渲染，未启动 Minecraft 客户端或浏览器界面。

## 已执行检查

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 导出复核 | `export_placement_fit.cjs --check`：按配方重新计算的运行资源与清单和磁盘一致 | [manifest.json](../../manifest.json) |
| 适配范围 | 12种形态有替换部件，均核对基础模型指纹；身体、五官、鼓腮贴片与生殖器部件未被改动；7种形态无适配 | [checks.json](checks.json) |
| 头饰透穿 | 魔理沙系、橙、帕秋莉、捕食种的帽子／软帽：全部动作片段每段8个时刻，9个向上视线方向。改动形态透穿点均为0（橙由原来的50降为0）；未改形态与原模型相同 | [checks.json](checks.json) |
| 头发露肤 | 10个视角的分类渲染中，原为已适配头发、现显露皮肤的像素最多为皮肤像素的0.18%（灵梦、歪脸灵梦各61／约34,300），来自鬓角下收后发顶边缘下的一小片太阳穴；门限0.5% | [checks.json](checks.json) |
| 五官遮挡 | 眼睛、眉毛、嘴、腮红的不透明像素，从正面及两侧斜前方看，被适配部件新遮挡的数量为0 | [checks.json](checks.json) |
| 蝴蝶结贴合 | 灵梦系六种：结心与尾带到头发／头皮的最近距离0～0.035；从背面四个视角统计尾带像素，在静止、行走、压扁、蓄力、跳跃、侧弯9个时刻相对原模型为0.94～2.55（门限0.9；稀毛灵梦原尾带半埋头皮） | [checks.json](checks.json) |
| 地面 | 各形态最低点与原模型相同 | [checks.json](checks.json) |
| 鼓腮 | 按适配后网格重导后，形变回归、精确接触（0新增穿插、0五官遮挡）、Java一致性全部通过；侧发外摆的中间过程也无接触 | [鼓腮本轮验收](../../../motion-corrections/reports/current/README.md) |
| 其他动作 | 19种形态2,584个采样帧通过地面、挂接与表情时段检查 | [motion-regression.json](../../../motion-corrections/reports/current/motion-regression.json) |
| 头顶垫块 | 分类渲染统计各动作中露出的垫块像素：灵梦原有压扁／蓄力时露出肤色平板，绑定高度切片后在动作抽帧中不再出现 | [actions.png](../../previews/current/actions.png) |
| 帽子交互 | 重导后离线复算 `HatCarry.fits`：GameTest中的五条尺寸断言结果不变；665个父子尺寸组合中15个变化（歪脸、稀毛灵梦初生幼油现在与普通灵梦一样能被魔理沙帽檐托住，0.2尺寸的稀毛魔理沙幼油不再能托）。抬帽距离只取决于帽檐，未变 | `hat_interactions.json` |
| 果实悬挂点 | `export_family_assets.py --check` 一致 | `family.json` |
| 素材检查室 | 19种形态1,487个姿态、290段声音、637个链接、12组参考姿态比较通过 | `.local/review/verification/checks.json` |
| Java与预览一致性 | 生产代码 `YukkuriModel.applyFit` → `applyRevision` → 构造 → `render()`，19种×10帧，4,052,722个三角，最大误差约0.0000044 | [java-parity.json](../../../motion-corrections/reports/current/java-parity.json) |
| 隔离构建 | 独立输出 `.local/placement-fit/build/`，`build` 成功（主代码与p0Test编译，`test` 无源码）；固定JAR SHA-256 `ffe217d556c02c91116cb79140fe216bd34320ca70aa7df50ca0f42e9a2ecc6b`，其中四份相关资源与源文件一致 | [build.json](build.json) |
| 启动用构建 | 另运行标准 `gradlew build createClientLaunchScript` 更新根目录 `build/`，供 `启动游戏.cmd` 使用 | `.local/placement-fit/logs/root_build.log` |
| 视觉审查 | 本人查看本轮导出的前后对比、脱帽、动作抽帧、未改形态、帽子交互与鼓腮预览，以及放大的纹理渲染 | [previews/current](../../previews/current/) |
| 独立审查 | 另起只读审查代理三轮查看并补渲。第一轮指出灵梦、歪脸灵梦、多毛灵梦蝴蝶结尾带被后发吞掉，稀毛灵梦尾带插进头皮；第二轮指出灵梦结心处尾带剪切翘起，稀毛尾带在动作中破碎下陷。均已改正，并加入动作中的尾带可见度检查。终检无阻塞问题，剩两处近看才明显的轻微瑕疵，见下节 | [visual-review.json](visual-review.json) |

## 仍存在的轻微问题

- 灵梦、歪脸灵梦纯侧面看，结心下方有一小块红色楔形（约头高5%），是尾带顶段离开结心处；背面和斜后看连贯无断口。
- 稀毛灵梦放大到约1600像素时，尾带上段有头顶发丝的细纹，右尾带中段有一个很小的肤色缺口；正常尺寸下只隐约可见。加大间隙并细分没有消除发丝细纹，保留已验证的0.05设置。

## 检查中发现的既有问题（本版未改）

- 家魔理沙夹取、搬运时短辫翻到帽檐前沿，透穿统计1,319个采样点；这是已验收的持物动作，前后一致。帕秋莉（侧倾、蓄力时刘海与发顶各少量，17点）与弗兰（俯冲时刘海1点）同样前后一致。
- 12种形态的伤口与徽章数据记录的模型指纹与当前已提交的模型文件不符，游戏会关闭这些形态的伤口与徽章显示。位置适配不改基础模型，没有影响这一点；已另行登记处理。

## 复现

```powershell
node scripts/export_placement_fit.cjs
python scripts/export_motion_corrections.py
python scripts/export_hat_interactions.py
python scripts/preview_hat_interactions.py
python scripts/export_family_assets.py --check
python scripts/build_review_catalog.py
node scripts/verify_placement_fit.cjs
node scripts/verify_puff_revision.cjs
node scripts/verify_motion_corrections.cjs
node scripts/verify_review.cjs
node scripts/verify_puff_face_contact.cjs
. ./.local/setup/env.ps1
./gradlew.bat --init-script scripts/placement_fit_checks.init.gradle --project-cache-dir .local/placement-fit/gradle-cache --no-configuration-cache build writePlacementFitClasspath
$env:PUFF_CLASSPATH='.local/placement-fit/classpath.txt'; node scripts/verify_puff_java.cjs
```

精确接触检查可用 `PUFF_IDS`、`PUFF_WORK` 和 `FACE_CONTACT_OUT` 分组并行，再合并为 `face-contact.json`。

## 未验收范围

- 未启动或操作 Minecraft，未做实机渲染、光照、帧率和多人同步验收；帽子交互的 GameTest 没有重跑，只离线复算了尺寸判定。
- 透穿、露肤与接触都是离散采样（每段动作8个时刻、10个视角），不等同于任意时刻的严格证明。
- 本版未经用户确认；标准造型与既有批准记录保持原样。

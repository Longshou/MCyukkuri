# 第七版鼓腮验收记录

2026-10-07。制作源、运行资源、19张鼓腮贴图、游戏端代码、素材检查室和预览已同步；造型状态为待用户复查。本版以八景U的“ぷくー”为依据，把两颊做成圆鼓的球形，嘴换成标准鼓腮的“ハ”形噘嘴，眼睛、眉毛与身体底部不动；可动侧发同步外摆，其余头发作为障碍物，脸颊在其前方停住。

## 位置适配后重跑（2026-10-08 00:37–00:41）

接入[头饰与头发位置适配](../../../placement-fit/README.md)后，鼓腮修订按拼接后的网格重新导出，下列检查在新导出上重跑，报告文件已覆盖为本轮结果。灵梦与歪脸灵梦的外摆角度和压脸量与之前相同；魔理沙、歪脸魔理沙的侧发由固定改为外摆11.5°、14°。

| 检查 | 本轮结果 | 记录 |
| --- | --- | --- |
| 形变回归 | 19种形态通过；静止时细分皮肤与身体最大偏差约0.0000069；25,078,320个五官及其他部件数值在鼓腮中不动；6,536组侧发刚体距离保持 | [checks.json](checks.json) |
| 精确接触 | 19种形态各8个时刻：皮肤新增穿插0、扎根顶点新埋0、外摆侧发新增穿插0、皮肤自交0；1,442,920个五官／腮红像素被皮肤遮挡0。19种分4组并行运行后合并，脚本与输入指纹一致 | [face-contact.json](face-contact.json) |
| Java与预览一致性 | 生产代码 `applyFit`、`applyRevision`、构造函数与 `render()`：19种×10帧（含 waddle、press、hop），4,052,722个三角，最大误差约0.0000044；包络误差约5.3×10⁻⁷ | [java-parity.json](java-parity.json) |
| 导出复核 | 在最终源文件上运行 `export_motion_corrections.py --check`：重新计算的运行资源、19张鼓腮贴图与清单均与磁盘一致 | [manifest.json](../../manifest.json) |
| 其他动作回归 | 19种形态、2,584个采样帧通过 | [motion-regression.json](motion-regression.json) |
| 素材检查室 | 19种形态1,487个姿态、290段声音、637个链接、12组参考姿态比较通过 | `.local/review/verification/checks.json` |
| 隔离构建 | 位置适配包的独立构建成功并固定JAR，SHA-256 `ffe217d556c02c91116cb79140fe216bd34320ca70aa7df50ca0f42e9a2ecc6b`；JAR内位置适配、鼓腮、帽子交互与果实资源与源文件一致 | [placement-fit build.json](../../../placement-fit/reports/current/build.json) |

下方为位置适配前的第七版记录，保留作基线；其中的数值与JAR指纹不对应当前资源。

## 已执行检查

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 形变回归 | 19种形态：细分皮肤闭合、静止时与原身体重合（最大偏差约0.000007模型单位）；鼓腮6个时刻中五官及形变以外部件的位置和UV不变（1,321万个数值）；根骨骼不动；侧发外摆为刚体（980组距离）；包络在[0, 1]内并回到0；任一时刻打断后常态与基础模型完全一致 | [checks.json](checks.json) |
| 精确接触 | 19种形态各8个时刻（鼓起、满腮、放气及接近零的起止）：皮肤与头发、饰物的新增三角相交0，原本扎根于皮肤的部件新增被埋顶点0，外摆侧发与其他部件新增相交0，皮肤自相交0；1,442,920个眼睛、眉毛、嘴、腮红不透明像素无一被皮肤遮挡；腮红距皮肤不超过0.03倍脸部尺寸；无顶点低于地面 | [face-contact.json](face-contact.json) |
| Java与预览一致性 | 离线调用生产代码 `YukkuriModel.applyRevision`、构造函数和 `render()`，以记录型 `VertexConsumer` 采集输出，与 `pose.js` 逐三角比较：19种形态×7帧，共2,160,826个三角，最大坐标误差约0.0000019模型单位；鼓腮包络641个采样最大误差约0.0000005 | [java-parity.json](java-parity.json) |
| 其他动作回归 | 19种形态、8段动作、2,584个采样帧通过有限数值、地面接触、跳跃高度、表情时段与动作切换检查；前后对比图按本版重绘 | [motion-regression.json](motion-regression.json) |
| 素材检查室 | `verify_review.cjs`：19种形态1,487个姿态、290段声音、637个链接与12组参考姿态比较通过；目录已重建 | `.local/review/verification/checks.json` |
| 导出复核 | 在最终源文件上运行 `export_motion_corrections.py --check`：重新计算的运行资源、19张鼓腮贴图与清单均与磁盘一致 | [manifest.json](../../manifest.json) |
| 隔离构建 | Gradle `build` 成功，生产代码与p0Test夹具编译完成；`test` 为 `NO-SOURCE` | [build.json](build.json) |
| 视觉审查 | 查看本轮导出的五视图、19种形态特写与斜侧面、左右侧面、单体动图连续帧及前后对比；未见脸颊进入头发、腮红破碎或五官被遮挡 | [visual-review.json](visual-review.json) |

接触检查以静止姿态已有的接触为基线，只统计鼓腮新增的穿插。形变按鼓腮量线性插值，8个时刻覆盖从约0.05到满腮的量值；它仍是离散采样，不等同于任意连续时刻的严格零相交证明。

## 复现与产物

在项目根目录执行：

```powershell
python scripts/export_motion_corrections.py
python scripts/build_review_catalog.py
node scripts/verify_puff_revision.cjs
node scripts/verify_puff_face_contact.cjs
node scripts/verify_motion_corrections.cjs
node scripts/verify_review.cjs
. ./.local/setup/env.ps1
./gradlew.bat --init-script scripts/puff_checks.init.gradle --project-cache-dir .local/puff-v7/gradle-cache --no-configuration-cache build writePuffClasspath
node scripts/verify_puff_java.cjs
```

`verify_motion_corrections.cjs` 的检查结果写到 `preview/review/motion-fix/checks.json`，本目录的 [motion-regression.json](motion-regression.json) 是其副本。编译输出、资源输出及运行 classpath 已核对为 `.local/puff-v7/build/` 下的独立目录。

构建成功后立即固定JAR到 `.local/puff-v7/release/mcyukkuri-0.1.0-dev.jar`，SHA-256为 `2888775406e4e47ccdb153b7819e84e9f86a0b9c18f51356abc2c88ed3c962db`；已核对其中动作资源、19张鼓腮贴图和模型类与本轮输出一致，再同步到现有启动脚本读取的 `build/classes/java/main`、`build/resources/main` 和 `build/libs`。运行资源SHA-256为 `1dc31cd90aae395df15cc6fcb677a03307e8a287aba5eb72cdb009a3832f5ca2`，来源指纹见 [manifest.json](../../manifest.json)。这些结果对应本轮固定产物，不能证明之后的共享工作副本仍通过检查。

最新预览：[常态与满腮五视图](../../previews/current/review.png)、[19种形态特写](../../previews/current/close-ups.png)、[19种形态斜侧面](../../previews/current/all-models.png)、[左右侧面接触视角](../../previews/current/contact-angles.png)、[单体动图](../../previews/current/puff.gif)、[四种形态连续动作](../../previews/current/face-stability.gif)。

## 未验收范围

- 未启动或操作 Minecraft 与浏览器界面，未做实机渲染、光照、帧率和多人同步验收。鼓腮期间皮肤约7,400个三角（豆形约13,700个），另加两份腮红网格；性能未实测。
- 伤口替代网格正在绘制或家油夹持物品时，鼓腮形变暂停（表情仍切换），以保留伤口与挂点位置；这两种组合不算完整支持。孕体贴面改用鼓起后的皮肤三角，未经实机核对。
- `validate_entity_assets.py` 在既有检查“manifest must register all nine variants”处失败，与初始化时 `.local/setup/check-entity-assets.log` 的记录相同，发生在读取动作资源之前，本轮未改动该处。
- 未运行 `runP0GameTest`；本轮改动在客户端渲染与资源，未改服务器行为。
- 离线视觉审查不替代用户的标准造型确认。中文记录经人工核对；未提供 `tech-doc-style-chinese`、`tech-doc-lint`，未运行。

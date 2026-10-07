# 第六版鼓腮验收记录

> 历史记录：第六版当时的验收说明，原文保留；链接已改指本归档目录内的文件。当前版本见[归档说明](README.md)。

2026-10-07。制作源、配套导出、预览和游戏运行文件已同步；造型状态为待用户复查。本轮采用局部横向鼓腮，固定眼眉、嘴部和身体底部，让侧发、发带与发梢随皮肤避让，并将腮红贴合到实际皮肤三角面。基础模型和既有批准记录保持原样。

## 已执行检查

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 网格与恢复 | 19种形态，独立鼓腮及体操鼓腮各65个时刻；身体闭合、五官固定、底部固定、局部形变不翻折、动作打断后恢复均通过 | [checks.json](checks.json) |
| 表面接触 | 739,840个不透明五官采样、30,157,144个附件采样通过；包括充气、满腮、放气及接近零的起止阶段 | [face-contact.json](face-contact.json) |
| Java与预览一致性 | 编译生产Java形变方法，比较19种形态共22,800个输入；最大坐标误差约0.000001313模型单位 | [java-parity.json](java-parity.json) |
| 其他动作回归 | 19种形态、8段动作、2,584个采样帧通过有限数值、地面接触、跳跃和动作切换检查 | [motion-regression.json](motion-regression.json) |
| 隔离构建 | Gradle `build`通过，生产代码和p0Test夹具编译完成；`test`为`NO-SOURCE` | [build.json](build.json) |
| 视觉审查 | 查看本轮导出的五视图、19种形态全览、左右斜侧面与侧面，并抽查连续动作关键帧；未见腮红悬浮或头发被脸颊明显吞没 | [visual-review.json](visual-review.json) |

接触检查排除原模型已有的内嵌接缝。附件允许的采样误差为体高的0.2%，本轮最大新增穿入深度约0.006018模型单位，处于误差范围内。腮红与皮肤的最大间距约0.026288模型单位。结果支持当前预览未见明显穿插，不等同于任意视角、任意连续时刻的严格零相交证明。

## 复现与产物

在项目根目录执行：

```powershell
python scripts/export_motion_corrections.py
node scripts/verify_puff_revision.cjs
node scripts/verify_puff_face_contact.cjs
node scripts/verify_puff_java.cjs
node .local/puff-contact/check-motions.cjs
python scripts/build_review_catalog.py
. ./.local/setup/env.ps1
./gradlew.bat --init-script .local/puff-contact/init.gradle --project-cache-dir .local/puff-contact/gradle-cache --no-configuration-cache build printPuffPaths
```

本轮动作回归使用临时包装脚本，执行`verify_motion_corrections.cjs`的检查部分，跳过历史预览的重绘；检查输出写入本目录。编译输出、资源输出及实际运行classpath均核对为`.local/puff-contact/build/`下的独立目录。

构建成功后立即固定JAR到`.local/puff-contact/release/mcyukkuri-0.1.0-dev.jar`，SHA-256为`68822ab83eeec5a7188c70f2d0c7256ec8736270cb920c4baeec3abadefed384`。已核对其中动作资源、19张鼓腮贴图和模型类与本轮输出一致，再同步到现有启动脚本使用的`build/classes/java/main`、`build/resources/main`和`build/libs`。来源文件指纹见[manifest.json](source/manifest.json)，构建命令、目录与运行资源指纹见[build.json](build.json)。这些结果对应本轮固定产物，不能用于证明之后的共享工作副本仍通过检查。

最新预览：[常态与满腮五视图](review.png)、[左右斜侧面与侧面](contact-angles.png)、[19种形态](all-models.png)、[单体动图](puff.gif)、[四种形态连续动作](face-stability.gif)。

## 未验收范围

未启动或操作Minecraft及浏览器界面，未完成实机渲染、帧率和孕体叠加验收。家油夹持物品或伤口替代网格正在绘制时暂不叠加鼓腮几何，以保留原挂点和伤口位置；这些组合不能算作完整鼓腮支持。孕体贴图已改用实际鼓腮皮肤三角面计算，但仍需实机核对。

离线视觉审查不替代用户的标准造型确认。本轮中文记录已人工核对，未运行未提供的`tech-doc-style-chinese`或`tech-doc-lint`。

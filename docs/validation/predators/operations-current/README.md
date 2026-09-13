# 捕食种器具适配补验

2026-09-13。联合伤口测试暴露固定姿态的资源缺口：旧导出器只识别普通种和家油身体，跳过了两种捕食种。现已识别 `predator_body_surface`，同步制作模型、选定快照和游戏资源；未知身体会明确报错。新增两种各五份适配：有帽／失帽固定具、有帽／失帽吊带、铁箱。

服务端支撑高度仍读取对应网格的 `body_lift`，补充缺失资源及无效数值检查，错误信息包含路径。没有改变角色身体、帽饰、翅膀或已有确认记录。

本轮独立 `compileJava processResources` 通过，命令使用 JDK 21、`.local/predators/build.gradle`、`--project-cache-dir .local/predators/gradle-cache --no-configuration-cache --offline`。`scripts/validate_entity_assets.py` 通过九种实体及 45 份器具检查；制作源 `build.py --check` 通过。[构建日志](build.log)与[来源及图像指纹](validation.json)已保存。

使用本轮实际打包身体与器具网格离线生成 30 张正侧背视图，固定姿态按运行高度旋转 90 度；逐图检查支撑垫、带子、吊环和箱体。固定具支撑在身体下方，带子留出翼部轮廓；吊环在头顶，铁箱覆盖全部静止轮廓。铁箱侧背壁为不透明设计，不能从该方向检查内部表面。离线图不替代客户端验收；新资源的固定姿态与伤口引擎复测由联合验收任务执行，本页不预报通过。

[固定具三视图](fixed.png) · [吊带与铁箱三视图](slings-and-boxes.png)。这些器具仍待用户确认。

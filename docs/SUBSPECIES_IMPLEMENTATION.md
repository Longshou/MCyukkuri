# 亚种、先天形态与帽船

当前接入螺壳魔理沙、多毛灵梦，以及灵梦／魔理沙各自的稀疏毛发、面部不足和豆形细长身体，共八套模型。帽船是普通魔理沙取得的真实载具，单独保存归属和乘员。此处不把乘船经历当作遗传亚种。

## 取得与保存

`SubspeciesLife` 将 `Kind`、`Congenital` 和后天帽船经历分开保存，客户端同步前两项。形态只适用于对应的灵梦或魔理沙，螺壳／多毛不与先天不足叠加。先天外观不修改性格、认知或伤口，不从后天伤口推导遗传。

自然生成和出生按实体 UUID 抽取一次，存读或再次调用出生接口不会重抽。当前平衡参数：对应命名亚种通常为 1/160；同亚种亲本参与时为 1/4；普通灵梦／魔理沙的先天外观不足为 1/200，三种等概率。这些概率是模组参数，不是作品统计。出生链路可用 `birthFlags(UUID,variant,parentA,parentB)` 提前选定并保存后代形态，以 `initializeBirth(flags)` 在碰撞判定前初始化；旧 `onBirth(child,parentA,parentB)` 保留为幂等入口。静态 `assetId(variant,flags)` 供未出生幼体选用相同模型，家庭记录与胎生显示由出生任务接线验收。

螺壳移动速度降低 35%，两个日常进水增量变为约四分之一，仍会进水。饮食、寒冷和治疗沿用基础物种。多毛灵梦的等待训练有独立意愿判定，未学会前每 240 tick 可尝试一次，成功意愿概率 5%；不强制修改其认知值。豆形身体的基础碰撞高度为普通形态的 1.55 倍、宽度为 0.82 倍，再叠加现有成长与生理变形。

## 帽船

在干岸边对带有自身帽子的普通魔理沙潜行持木棍右击，可尝试放帽乘船；合适岸边的自主行为也有机会触发。落水不会凭空生成帽船。帽子令牌从主人转入船体，主人真实骑乘该实体。已有船、缺帽、被操作、距离过远、非水面或放置碰撞均拒绝投放。

船体随水流移动并维持水面浮力，记录 `AFLOAT`、`BEACHED`、`CAPSIZED`、`RESCUED` 状态。过量进水、受击，或对船上主人再次潜行持木棍右击会翻覆；乘员真正落水，翻覆船体不能作为干燥站立面。空手右击近处船体可从干岸救援，位置不合适时先拉近。有效救援返回同一饰物令牌、让主人离船到岸上，重复救援不能复制帽子。搁岸时也会尝试恢复。

`HatBoatEntity.find`、`createFor`、`tryLaunchNearby`、`board`、`capsize` 和 `rescue` 供野外事件使用；调用方应以实际返回值和乘员关系判断阶段。查不到卸载区块中的实体不等于死亡或获救，不能生成替身船。`REMOVED` 保留给外部状态对接，当前正常收束走 `RESCUED` 后移除。

## 美术接入

[选用清单](../art/source/subspecies-runtime/manifest.json)逐项列出模型、几何标识、骨架、贴图和运行目标。[交互预览](../art/source/subspecies-runtime/preview.html)与[多角度图](../art/source/subspecies-runtime/previews/current/)来自当前运行导出。

螺壳选用标准库 `marisa_tsumuri`，多毛灵梦选用已有 `reimu_wasa` 并适配基础尺度；不足形态由[配方](../standards/yukkuri-library/subspecies/deficient/recipes.json)生成。帽船选用既有 aquatic 配件源。独立螺壳物品由同一螺壳几何导出，拆下时不再变成普通帽子。动作复用当前基础种骨架曲线并重映射；螺壳侧弯改为整体小幅转动，避免壳与头撕裂。缩壳等预览片段尚未作为独立生存动作触发。

身体受伤表面、持食口部和饰物显隐按形态资源标识选择。豆形使用连续身体表面与混合骨骼，并保留背部标记；源码快照和运行资源同步生成。螺壳延续旧造型确认，多毛、先天不足与帽船适配待用户确认。

## 验证

2026-09-13：冻结构建的亚种专项 **60/60** 断言通过，覆盖种类约束、形态 NBT、出生幂等、预选形态、插入世界前的豆形尺寸、真实乘员、令牌转移、漂浮保持干燥、翻覆落水、救援与禁止重复投放，以及螺壳物品和豆形口部坐标。八套受伤表面逐一核对实际加载模型的 SHA-256、移动中的身体射线命中和侧弯挂点有效性。日志 `.local/subspecies-port/frozen-gametest.log`，结果 `.local/subspecies-port/frozen-gametest/p0-control/subspecies-checks.json`。类文件与资源位于 `.local/subspecies-port/runtime-verified/`，逐文件指纹为 `.local/subspecies-port/runtime-fingerprints.json`。预览夹具和出生纯接口仅针对该副本独立编译，未用其他活动任务的中间构建替换整个副本。

另有 44 项来源指纹及局部文档链接核对通过。Edge 无界面浏览器切换八种形态和行走动作无脚本错误，并人工查看了当前页面及多角度图。以上属于离线与引擎检查，实机画面另外记录。

复测使用 JDK 21：

```powershell
./gradlew.bat --project-cache-dir .local/subspecies-port/gradle-cache --no-configuration-cache -I scripts/subspecies_checks.init.gradle runP0GameTest createP0PreviewLaunchScript
```

此配置同时隔离实际编译输出、资源输出、项目缓存和世界。专项通过仅适用于该快照；不代表其他活动任务或整个共享工作副本通过。

最终实机证据见[20张原始帧与指纹](validation/subspecies/index.html)。真实客户端潜行持木棍右击完成放帽乘船；停止客户端并重进同一世界后，主人 UUID、船 UUID 和实际乘员关系保持。再次右击翻覆后湿度增至8，空手右击救援后恢复饰物、清除船关联。上述输入经过客户端交互与服务端响应，记录于 `input-complete.json`。螺壳、多毛、豆形各有0.32倍幼体画面，螺壳／多毛行走与豆形伤口侧弯各录制5帧。

实机发现并修复了稀疏形态误缩头顶皮层造成的饰物间隙，以及水面穿入帽船内部的问题。稀疏毛发的根部和蝴蝶结按实际身体位置适配，源、骨架、导出、受伤表面和预览同步更新；帽船遮水区域从实际内帽沿生成，翻覆时不遮水。豆形受伤表面改为长边自适应细分，皮肤顶点从323061降至96786；短片采样平均帧耗时约从49ms降至22ms，非通用性能保证。

本任务最终客户端均已停止。胎次预选、`Morphs` 同步及稀有幼体脱离显示的后续公共接线由出生任务在其新快照验收，不混入本页固定副本结论。

联合验收随后发现豆形母体出生时缺少 `chewing_closed.png`。亚种导出已补充生态新增的 `closed_smile`、`chewing`、`chewing_closed`、`satisfied`、`open_smile`，八套共128张表情。此次仅更新表情选择与预览，八套运行几何及动作的原指纹保持不变；不以静默替换为平静表情掩盖缺图。螺壳仍用256×256图集，其余七套为128×128。`python scripts/export_subspecies.py --check-textures` 已完成源确认指纹、PNG解码、尺寸和源／快照／运行一致性检查；[40幅新增表情图](../art/source/subspecies-runtime/previews/current/ecology-expressions.png)已逐行目检，记录见[表情检查](validation/subspecies/expression-checks.json)。新增表情的实际客户端加载与出生演出交联合验收在新冻结包复测，不沿用上述旧快照的实机结论。

后续联合任务在 `runtime-20260912-230707` 完成真实资源重载，128张图集全部通过 `NativeImage` 解码及 `TextureManager` 加载，八套模型待机顶点检查通过；[原始客户端加载结果](validation/subspecies/client-subspecies-atlases.json)已复制归档。联合任务另报告豆形母体停服恢复后再分娩的994条检查通过，双客户端完整日志未出现模组缺图。首次分娩录像被另一玩家遮挡，之后使用同一冻结类与资源、独立世界副本及单客户端完成[豆形母体补拍](validation/port-integration/current/bean-birth-supplement/README.md)：147帧覆盖300 tick，联合验收与出生负责人分别核看四张精选原帧，闭口咀嚼、幼体显露、首胎脱离及三胎完成均通过；三项投药前置和八项保存后检查通过。该豆形母体的视觉缺口已关闭，不扩展为全部亚种的分娩视觉验收。

# 家庭生长与茎生素材

[recipe.json](recipe.json) 与 [shed-stem.json](shed-stem.json) 是本包的制作数据；[export_family_assets.py](../../../scripts/export_family_assets.py) 导出家族展示资源与产后茎道具，来源指纹分别见 [provenance.json](provenance.json) 和 [shed-stem-provenance.json](shed-stem-provenance.json)。

普通灵梦与魔理沙使用 `art/source/standard-runtime/` 的选定模型、骨架和表情；服务器与客户端共用 `FamilyGeometry` 计算母体茎与子代位置。每胎 1–5 个，茎长随胎数、母体和子代体型拟合（[fit_stem.py](fit_stem.py)）；子代始终悬在母体前方，间隔错开并避开脸、头饰和地面。每个子代由一根连续、渐细的小茎从主茎挂点沿重力竖直悬下，静止时整根小茎与子代头顶挂点在同一垂线上，不作固定的左右斜伸；出生从对应悬挂处直落。挂点间距和主茎长度由 `fit_stem.py` 拟合，保证幼体与母体、相邻幼体及地面留有间隙，不依靠斜枝错开。客户端在母体实际走动或跳跃时才按现有重力方向轻摆并向移动反方向滞后，停稳后恢复竖直，没有静止自发摆动（项目设计）；客户端不改变胎次数据或服务端出生落点。主茎仍按参考在身体前方的竖直面内向前拱出。剪下或装瓶的茎同样静止竖垂。自然生产完成后，空茎从根部脱落。

产后茎由世界级 `ShedStemDrops` 持有等待记录。记录只在已生成子代全部实际落地、落水或入帽，或进入死亡、明确移除、收容等终止状态后才允许投放；生成未完成或被取消不能触发投放。普通卸载不推定落定，重载后继续等待真实落定或终止事件，不强加载远处区块。母体死亡后仍保留等待；母体已加载时使用其当前根部位置，否则使用记录的根部位置。每 20 tick 只在投放区块已加载时尝试；事件取消时保留记录。按胎次 UUID 去重，已投放记录重载后不补发。该契约覆盖正常存档、读档和重试，不保证区块实体文件与世界存档在磁盘崩溃时的原子性。

茎道具加入普通食物标签，可喂食、放入食盆或供饥饿个体拾食，使用普通食物的 20 营养值和进食恢复；没有额外治疗效果。空茎造型不含胎儿或可培养胎次。“茎＋干草块＋白羊毛”制作一个油库里窝，旧配方继续可用。胎生、家油卵、取消胎次及剪下后培养的茎不额外生成茎道具。

从仓库根目录运行 `python art/props/family-life/export_shed_stem.py` 导出道具；追加 `--check` 只核对导出，`--preview --node <Node路径>` 在 `.local/previews/shed-stem/` 生成当前预览。预览与登记数据不进入运行资源。

`python art/props/family-life/preview_stem.py --node <Node路径>` 用当前运行网格与导出的配方离线生成 `.local/previews/stem-hanging/current.png`；总入口由 `scripts/build_preview_hub.py` 重建并收录。图只显示静止悬挂几何，移动、碰撞和出生仍在试玩场确认。

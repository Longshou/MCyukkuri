# 油库里展馆

根目录 `启动动作博物馆.cmd` 使用远端 CI 的现成产物启动五厅、27 个展台，不执行本机编译。运行配置为 `.local/experimental-map/museum-runtime.json`；入口和完整动作适配见 [动作目录](MOTION_MUSEUM_ACTIONS.md)。

## 当前展台

身体、表情、考试和徽章仍有显式片段演示。取食、排泄、首便照护、独立、家油、水生、运输、储粮和聚落入口设置有限初始条件，随后由普通服务器逻辑运行。按钮不能保证成功：碰撞、距离、库存、身体状态与人口条件仍然有效。

| 展厅 | 主要入口与观察内容 |
| --- | --- |
| A 身体与日常 | 身体片段；真实食物拾取和进食；回窝休息；普通、厕所及地面排便；实际照护工具 |
| B 家庭与成长 | 真实接生及漏接庭院；嚼糜与舔舐首便；成长体型；西侧独立筑巢庭院；家油独立庭院的拾絮、入巢、产卵及孵化 |
| C 水生魔理沙 | 真实陆地/船上出生庭院；返岸收帽；单船、两幼体与四幼体负载；初航；亲油救援和幼体自救 |
| D 人类社会 | 考试、徽章与表情片段；真实喷雾和运输篮操作；商店交互与动手角 |
| E 季节与聚落 | 真实采割、搬运和储粮（嘴贴储粮堆存取）；居巢取食；建立聚落、派工、迁居及安全重置 |

出生庭院相互隔离，避开密集展馆演员占用的局部人口上限。双亲是真实个体，幼体由 `FamilyData` 创建唯一身份，出生后继续照护。接生与漏接按钮调用 `birth_scene deliver`，漏接会造成已有的实际伤亡后果。已有胎次、恢复期和后代不会随着按钮倒放；补食按钮只生成明确数量的真实口粮。

家油庭院提供六份棉絮、十二份食屑与空巢。两只家油先自行拾取、搬回、筑巢；满足普通条件后才产卵。初航入口不预授予首航完成，独立入口不预授予材料或施工进度。帽船使用真实帽子、真实座位与有限粮食/桨；容量不足会拒绝初始化。

聚落入口使用 `/yukkuri museum settlement start <中心>`，派工为 `order forage|scout|message`，另有 `threat <1..20>`、`migrate`、`status`、`reset`。展台四按钮为建立、采集、迁居、安全重置；其他入口可从 `mcylab:stand/settle_*` 调用。持久登记只管理本轮创建的成员和设施，重置不按半径杀实体；成员被收容、未加载或牵涉外部家庭时整次拒绝，混居住所及外来库存保留。

## 停止与连续移动

普通片段的移动使用一次 `museum walk` 导航请求，后续片段等待实际抵达；失败停止，不以密集传送补完。初始布置、访客跳转和安全归位仍允许一次定位。

`/function mcylab:stop_all` 尝试停止展台。真实出生、辫子拾幼、落水、初航、载船或未完成安全上岸时拒绝重置。先带回收容/未加载成员，等照护或上岸结束，再尝试停止。已有物资、亲缘、出生和学习成果不会回滚。`/reload` 只取消片段调度及普通演示导航，真实生命过程继续。

运输展台和动手角发放实际喷雾及带冒险模式放置许可的运输篮。收容后应将同一个体放回，再停止对应展台。大厅入口可补领导览；`/function mcylab:home` 返回大厅。

## 修补旧存档与生成新地图

安全修补只在游戏内填补缺失结构、恢复既定按钮及标牌，不离线改写 region/实体文件，不覆盖现有非空方块或设施库存。新增庭院演员采用一次性登记安装，反复修补不会补出已收容或死亡的同名演员。旧模板演员不会被范围删除；完整新布局优先在新地图观察。

```powershell
python scripts/build_museum_full.py --pack-only --output .local/experimental-map/museum-repair-new
```

把输出内 `saves/MCyukkuri-Museum/datapacks/mcylab` 更新到目标世界的 datapacks，再用新版 CI 运行环境打开旧地图或执行 `/reload`。本版首次载入会在五秒后自动安全修补；之后需要再次补洞时执行 `/function mcylab:repair`。修补函数记录并只释放本次新增的区块加载票。不能仅替换数据包而继续使用缺少新命令的旧模组。

生成独立地图（输出必须是不存在的新目录）：

```powershell
python scripts/build_museum_full.py --seed-level .local/p0/gametest/world/level.dat --output .local/experimental-map/museum-new --catalog docs/MOTION_MUSEUM_ACTIONS.md
```

仅复制种子世界的元数据，不复制区块、实体或玩家。首次进入由数据包建馆；日志 `MCYMUSEUM_BUILD_COMPLETE` 与 `MCYMUSEUM_CHECK_PASS` 只表示生成和结构计数完成，不代表动态玩法或画面验收。

制作脚本为 `museum_scenes.py`、`museum_dsl.py`、`museum_hat_boats.py`、`museum_aquatic_rescue.py`、`museum_birth.py`、`museum_house.py` 与 `build_museum_full.py`。非编译检查和离线预览写入 `.local/reports/`、`.local/previews/`；动态接触、长时间导航、多人干扰、重启恢复与帧耗时需要可运行环境实测。

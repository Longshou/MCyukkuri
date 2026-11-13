# 待办

只放未完成项，完成即删。状态只用[约定](roles/lead.md#待办维护)六种；规则在所属专题，这里登记现象、运行版本与完成标准。维护规则见[总负责人](roles/lead.md#待办维护)。

## 进行中

开工前登记，合入后移除；已登记区域先协调，不同时改。

| 条目 | 工作树 | 主要改动文件 |
| --- | --- | --- |
| 台词审校与补充（总负责人调度的 Codex 标签页） | `.worktrees/wp-gb-speech-review` | art/dialogue/, src/main/resources/assets/mcyukkuri/dialogue/, lang/*.json |
| 茎生 2：茎从根到顶生长再结果，耗尽死亡茎枯萎 | `.worktrees/wp-ic-stem-growth` | art/props/family-life, standards/yukkuri-library/drafts/remains, client/FamilyRenderer, RemainsRenderer |
| 工具 2：并入原版物品、实际使用模型与效果 | `.worktrees/wp-id-tools-v2` | item/OperationToolItem, interaction/OperationLife, art/props operation-tools, recipes, lang |
| 街道生活：爱派、虐派与路人（单步可见反应） | `.worktrees/wp-ig-human-npc` | human/HumanNpc, human/Custody, social (reactions), museum street scenes, docs/FEATURES.md |

## 玩法待办

- **2026-11-13 试玩反馈**（运行版本 [41d423bff](https://github.com/Longshou/MCyukkuri/commit/41d423bff)，[CI 43260604657](https://github.com/Longshou/MCyukkuri/actions/runs/43260604657)；用户原话为验收标准，编号沿用原话，原话无 9）。此前的待办按用户「之前的todo待办移除」全部移除。
  - 1 捕食种建模：已接入待试玩（[d85745009](https://github.com/Longshou/MCyukkuri/commit/d85745009)，用户已看预览认可）。用户：「捕食种建模出问题，翅膀错位 面部头发错位等」
  - 2 按钮延迟：已接入待试玩（[ca2e66dc9](https://github.com/Longshou/MCyukkuri/commit/ca2e66dc9)，性能包；按钮不再被吞，场景加载期不被清场覆盖）。用户：「wo在测FM 21 22等的时候发现按了按钮延迟较高才出现演出 或者有时候按了也没用 看看是不是性能问题 如果是则修复」
  - 3 同屏性能：部分接入（[1ea3f0740](https://github.com/Longshou/MCyukkuri/commit/1ea3f0740)，复用碰撞几何、减轻场景与人群渲染；仍需试玩看卡顿）。用户：「油库里同屏动作卡顿很明显 需要优化性能」
  - 4 素鸡立节奏：已接入待试玩（[8f9c4a8ed](https://github.com/Longshou/MCyukkuri/commit/8f9c4a8ed)；草地 MD-08、MD-10）。用户：「素鸡立演出问题：中间过程太短 素鸡立过程应该是： 中间重复多次 5~30秒 然后是素鸡立演出：要来了...素...素...素鸡立！  跟随语音台词节奏做演出 高峰期停留3秒 然后才接神气 / 满足脸」
  - 5 爱丽丝雷普魔：已接入待试玩（[8f9c4a8ed](https://github.com/Longshou/MCyukkuri/commit/8f9c4a8ed)；草地 MD-08、MD-10）。用户：「爱丽丝雷普魔状态的移动是平移 没有动作 且素鸡立瞬间没有演出，应该继续锁住母油演出完成后才释放」
  - 6 漏尿场景与水滩：已接入待试玩（[317b4afd9](https://github.com/Longshou/MCyukkuri/commit/317b4afd9)，MD-17 减到 4 只，水滩 90 秒后 30 秒淡出）。用户：「短受惊漏尿演出放那么多只 非常卡 场景演示要优化 性能也要优化 且尿液水滩应该也会随时间淡化消失 纯贴图变化」
  - 7 谱系名字：已接入待试玩（[317b4afd9](https://github.com/Longshou/MCyukkuri/commit/317b4afd9)）。用户：「交互菜单的谱系里面应该有油库里名字」
  - 8 台词用词：已接入待试玩（[c087b485d](https://github.com/Longshou/MCyukkuri/commit/c087b485d)，中文台词源与语言文件已无「慢慢来」）。用户：「中文台词的慢慢来全部换成 油库里」
  - 10 嗯嗯奴隶脏污：已接入待试玩（[317b4afd9](https://github.com/Longshou/MCyukkuri/commit/317b4afd9)）。用户：「嗯嗯奴隶默认三级脏污」
  - 11 油霉驱逐：已接入待试玩（[8b99e83bc](https://github.com/Longshou/MCyukkuri/commit/8b99e83bc)；巢穴 DN-05、聚落 CL-06）。用户：「油霉个体会被逐出聚落 / 家庭 有演出」
  - 12 流浪与觅食重做：部分接入（[2e875ea71](https://github.com/Longshou/MCyukkuri/commit/2e875ea71)，流浪找纸箱／木箱遮蔽、雨中纸箱塌、翻有限的垃圾、避风缝；流浪与觅食 FG-08～FG-11；对人讨食与假帽等在态度包里）。用户：「流浪与觅食 野生住所的演示重做 要符合现在的系统设定 需要重做的演出也重做」「流浪与觅食的逻辑也该优化。不止这里 在工作的途中发现可以优化的演出 动作等 都可以上报调度者」
  - 13 移动速度：已接入待试玩（[e3cc12142](https://github.com/Longshou/MCyukkuri/commit/e3cc12142)，自驱步态 1.75–3.87 格/秒，均低于人类步行；搬运中转身需试玩看）。用户：「油库里的移动速度不可能比人类更快 据此修正整体移速」
  - 14 运输篮：已接入待试玩（[973baa128](https://github.com/Longshou/MCyukkuri/commit/973baa128)、[31308d613](https://github.com/Longshou/MCyukkuri/commit/31308d613)；普通／高级／大号三种容量，街道 TN-06）。用户：「运输篮内有油库里时 需要显示相关信息。并且运输篮应该支持一次性运输多个非成体 简单处理可以按成长阶段来判断数量」「运输篮载荷可以通过做几种换色篮子做区分，比如有普通高级这种区分」
  - 15 TN01：已接入待试玩（[1ba96424e](https://github.com/Longshou/MCyukkuri/commit/1ba96424e)，会马上消失的机会优先于日常野外事件；街道 TN-01）。用户：「TN01演出失效 这个演出动作美术都做了吗」
  - 16 TN02：已接入待试玩（[a44ed817b](https://github.com/Longshou/MCyukkuri/commit/a44ed817b)，银章与嫉妒双方停下交换表情动作；街道 TN-02）。用户：「TN02演出油库里会乱跑 而且这个也得做的更丰富 表情互动都要有吧」
  - 17 考试：部分接入（训练八项技能分等级、经验来自日常，[3b2310e6f](https://github.com/Longshou/MCyukkuri/commit/3b2310e6f)，街道 TN-14、TN-15；考试每题可见问答、到公布结果才收费、新增铂金章（[c2c041856](https://github.com/Longshou/MCyukkuri/commit/c2c041856)；街道 TN-03、TN-16～TN-19；铂金章冷白八角徽章已换正式美术）。用户：「考试过程演出对吗？我只测了铜章 提问名字后 我没看到油库里回答就过了。」15–17 合并处理，用户：「训练与考试相关建议全链路排查下 评估调整」铂金章改钻石章：「铂金的徽章可以改成钻石的，风格延续其他徽章的样子」
  - 18 打招呼前倾：已接入待试玩（[81124f288](https://github.com/Longshou/MCyukkuri/commit/81124f288)，前倾只在面对面蹭脸时做，招呼站位按真实体型留出前倾空间；草地 MD-02、MD-18）。用户：「打招呼过程前倾动作会把对面的油库里撞得后退？不符合预期」
  - 19 抖动滥用：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「不要使用高频的抖动组成非害怕颤抖场景 商店的演出用那么多抖动干嘛」
  - 20 商店信息：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「商店的油库里信息通过npc提供就可以了不要用告示牌 不然不好更新」
  - 21 商店任务与店员：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「删掉寻找丢失的油库里任务 没意义 不做店员店长的区分 右键商店npc都可以触发互动 不要只能店长触发」
  - 22 商店空间：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「商店太过狭窄 已知发现一直开关门 npc和油库里卡在一起。 可以做后院放养部分油库里」
  - 23 商店购买：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「无绿宝石购买 提示没钱或者时间不对什么的 然后准备好了绿宝石购买点了没反应。 另外购买界面的位置信息完全没必要。」
  - 24 商店供货：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「商店现在不要供货限制，自然刷新售卖的油库里，但是还是必须和店里的对应。」
  - 25 商店陈列与门：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「商店物品展示用展示框 。商店的铁门和玻璃板完全对不上的」
  - 26 商店饲养：已接入待试玩（[7f5607a9b](https://github.com/Longshou/MCyukkuri/commit/7f5607a9b)，宠物店重做；商店场景 shop_build／shop_window／shop_buy／shop_restock）。用户：「商店内的油库里，店员保证喂食 和 清理厕所 ，待售状态油库里不会进入发情期方便管理。 且主要售卖未成年油」
  - 帽沿跳：已接入待试玩（[cd6883349](https://github.com/Longshou/MCyukkuri/commit/cd6883349)）。用户：「帽上子油蹦跳到自己位置时，跳的途中会突然变换位置，一次跳跃被分成了两次」

- **2026-11-13 补充要求**（用户原话为验收标准）：
  - 茎生：复测失败（[80f7774ea](https://github.com/Longshou/MCyukkuri/commit/80f7774ea)，约 20 秒肉眼可见长茎结果、营养同步消耗、耗尽死亡的遗体带茎和未熟果；草地 MD-11、MD-12，出生与成长 FM-03、FM-12）。用户：「茎生这里规则：如果受孕是茎生，则会以肉眼可见地速度长出来，需要有流畅地长出与结出果实赤油的演出。不是慢慢长出来的，营养也会跟随这个过程迅速得到消耗，如果这个过程营养耗尽死去应该有对应遗体，有茎，且茎上有未长成的果实赤油」「茎生的根茎应该是从根部到顶部先长出来，然后再结出果实。不像现在这样，你做的有点像是从小变大，并不是生长」「耗尽营养死亡之后，它的根茎应该也会有相应的外观改变，而不是保持原来正常的样子」
  - 帽上赤油下帽：已接入待试玩（[bffa51d6a](https://github.com/Longshou/MCyukkuri/commit/bffa51d6a)，从帽檐转身、蓄力、跳下落稳；出生与成长 FM-21、帽船 PD-11）。用户：「帽上赤油这里只需要修赤油从帽上下来也是平移的问题即可，另外两个已经好了」
  - 器官位置：已接入待试玩（[ff8c3fac1](https://github.com/Longshou/MCyukkuri/commit/ff8c3fac1)，豆油器官从下往上测量，205 种配对按实测部位对位；动作展厅 → 特殊身体）。用户：「马姆马姆和佩妮佩妮规则应该都是一样的。豆油因为身体比较长，注意器官都是从下往上计算位置的，而且要确保素鸡立行为位置匹配正确。可以根据动作实况调整器官位置」
  - 语料库、动作库：部分接入。用户：「语料库 动作库要处理」。80 个待补事件已补成候选、动作分级检查 0 错（a939a2c28）；落水自救、辫子救援、帽子漂走／找回的台词已接到真实时刻（帽船 PD-14、PD-17）。
  - 演出夸张化：部分接入。用户：「演出夸张化指的是部分动作动态表达需要更明显，需要调整的还是要继续调整」
  - 玻璃挤脸：未做。用户：「玻璃挤脸可做，店员巡店没有必要额外做逻辑」
  - 家庭通用（亲油久不归、一方觅食一方看家）：已接入待试玩（[5754a0c21](https://github.com/Longshou/MCyukkuri/commit/5754a0c21)；流浪与觅食 FG-02、FG-07）。用户：「这里要考虑处理亲油许久不归（死亡）子油如何判断后续如何行动的问题，父母一方出去觅食另一方配合行为的问题」
  - 口部、辫子与承载守恒：已接入待试玩（[db92d6c42](https://github.com/Longshou/MCyukkuri/commit/db92d6c42)，每个部位同一时间只做一类事、保留原容量，交接先收后放；FM-11、FM-15、PD-02、PD-17）。用户：「口部物品守恒需要看看正常不，这个要确保。这种守恒相关要求 比如辫子上的物品 嘴里的物品 可能承载的赤油，这种你看看在代码上要不要调整抽象出来，方便统一管理」
  - 粪便：已接入待试玩（[2b9c62d32](https://github.com/Longshou/MCyukkuri/commit/2b9c62d32) 删旧档位、粪堆按体积连续；[d6e9397e1](https://github.com/Longshou/MCyukkuri/commit/d6e9397e1) 粪便管理演示 BM-14；露天衰减与捡拾只能玩家在场看）。用户：「旧粪堆要处理，且新的粪便管理方案我没看到相关演示，你也要跟我说清楚」「采纳建议。另外问题是大小和排出量应该挂钩吧」。删除旧档位残留，做一个粪便管理演示场。
  - 鬼威惨工具：部分接入（[97d6564b5](https://github.com/Longshou/MCyukkuri/commit/97d6564b5)，14 件工具与剪下的头发改为与小麦粉糊一致的图标；交互已接线：[aec6fe697](https://github.com/Longshou/MCyukkuri/commit/aec6fe697)，统一的保护门挡住对他人宠物与未付款商品的伤害；身体 BD-07、商店 shop_build）。用户：「现有的油虐工具设计好交互，再按照游戏其他物品风格重新做建模」「一些工具可以合并，比如剪刀也可以和原版物品合并」「工具需要有实际建模，即实际与油库里的互动需要处理好，工具需要有实际的效果」
  - 流浪油、商店（宠物店与鬼威惨商店）、街道、训练与考试：用户已认可计划，分包进行中（流浪油 wp-hf-stray、宠物店 wp-hg-pet-shop、考试 wp-ho-exams；街道布局与鬼威惨商店排在宠物店之后）。用户：「商店注意好布景和npc逻辑能闭环正常，被玩家交互打断也能恢复正常流程；街道的售货机什么的可以替换成mc原版中的村庄建筑，不出戏。路灯，纸箱这种还是可以存在的。纸箱应该跟流浪油演出相关；训练与考试的问题在于，内容和正常游戏内容割裂无关，除了训练与考试其他时候几乎没有体现或者联动」
  - 垃圾头饰与通用态度：部分接入（[1afd7b44b](https://github.com/Longshou/MCyukkuri/commit/1afd7b44b)：自尊由数值现算，饥饿流浪按数值讨食或辱骂索食、野油见生人停看或退开；城镇 TN-13。垃圾头饰：[a960729a0](https://github.com/Longshou/MCyukkuri/commit/a960729a0)，右键消耗一件随机成三样之一，自尊低才接受，同类认出是假货会嘲讽但不驱逐，死后随遗体；流浪与觅食 FG-12；纸盒、瓶盖、冰淇淋筒三种正式模型已换上，并修了非魔理沙体型戴垃圾头饰时客户端崩溃（[bc42862cb](https://github.com/Longshou/MCyukkuri/commit/bc42862cb)）。徽章、宠物、同伴之间的反应改读同一套数值：[9740d6294](https://github.com/Longshou/MCyukkuri/commit/9740d6294)，街道 TN-01、TN-02，房屋 HD-06；橱窗与买卖反应也改读同一套数值，[3f6b49dfd](https://github.com/Longshou/MCyukkuri/commit/3f6b49dfd)，商店 shop_window）。用户：「缺帽戴这几种垃圾顶替。那么如果对这种垃圾取下头饰呢？我建议统一作为垃圾饰品处理，右键给油库里随机变成一种垃圾头饰。油库里也要有相应反应，也会根据数值反应。流浪有如果嚣张的会直接辱骂人类并索要食物，这种根据数值变化的态度应该是全游戏通用的。」「我看后台任务的态度模型不全面，而且没有和游戏的数值配合好 这些涉及很广的较为底层的修改 需要多个裁决者判断」——先出贴合现有数值的设计，三模型独立裁决后再实现。
  - 工厂：暂不开放，后续再做。用户：「工厂相关暂不开放，后面再做记为todo 现有内容不应该依赖工厂」。商店以外的依赖已解除（5e18b041f，新世界不生成工厂、旧存档可读）；商店侧由宠物店包清理。

- **人类世界扩建（街道生活、爱派虐派 NPC、鬼威惨商店）**：部分接入（街道摊位与小巷、流浪物件、鬼威惨商店：[ff20a18b6](https://github.com/Longshou/MCyukkuri/commit/ff20a18b6)、[21a79fd07](https://github.com/Longshou/MCyukkuri/commit/21a79fd07)，商店 oni_shop、流浪 stray_rubbish；爱派虐派 NPC 进行中 wp-ig-human-npc）。用户：「做好群落和结构规划前 原版村庄需要纳入 mod 比如街道商店等都可以和村庄一起生成  街道相关内容也需要大幅度完善 现在的简陋  包括 npc 爱派虐派做好做完善 还有鬼威惨商店也可以做了 参考wiki 设定」「街道生活、爱派虐派 NPC 和鬼威惨商店 也需要」。设定依据见 Wiki [人类世界](../wiki/hakkei-reference/world/human-world.md)。村庄生成：已接入待试玩（[8f59e1f5d](https://github.com/Longshou/MCyukkuri/commit/8f59e1f5d)；新建默认世界种子 42，`/tp @s 651 80 -302`）。

## 流程与工具

提效类改动；状态与玩法条目相同，完成即删。以真实提效衡量，不追数据指标。目前无未完成项。

## 待用户裁决

设定来源之间、或来源与项目设计之间需要用户决定的冲突；游戏已采用的以游戏实现为准，不列在这里。每条写清各方说法与出处、对游戏的影响，用户决定后移入所属文档并删去。

- **环境捕食者与其它吃同类的种类**：站点记鸟、鼠、猫、狗会伤害油库里，Letty、Yoshika、Rumia 等型也捕食同类（[来源表](../wiki/hakkei-reference/sources-fandom.md) F04、F36）；项目只实现蕾米莉亚与芙兰两个捕食种。选项：维持两种／加入不具名的环境捕食者（猫、鸟）／扩大捕食种名单。影响：威胁扫描与逃跑、护幼链路、试玩场景数量。
- **幼体的“答应慢慢来才活得下去”**：站点写幼体未获亲代或饲主回应会拒食、停止活动，约二十四小时仍未听到便可能死亡（F35）；项目已有出生问候，但只按真实饥饿、伤势与照料结算，没有回应存活判定。选项：不加／只作不安压力（不致死）／按站点写成存活门槛。影响：新生儿首餐链、亲代回应演出、幼体死亡率。
- **完全水生的种类**：站点列 Nitori、Wakasagihime 等不怕水的型，并称部分型能长期在水下生活（F18），另写把丝带当筏的“水生灵梦”（F151）；项目只有帽船水生魔理沙，其余种类落水即受水伤。选项：维持现状／只做外观亚种／做真实水生亚种（需独立的溺水与救援规则）。影响：溺水与救援、帽船亚种的独占性。
- **商品化宠物品系（迷你油、即时油、哭泣油）**：站点写宠物市场把主线种育成改良品系：迷你油成体只有几厘米、永远带幼体口音，即时油冻结成包、由饲主解冻，哭泣油专卖给施虐者（[来源表](../wiki/hakkei-reference/sources-fandom.md) F40、F44）；项目宠物就是主线种族个体，按阶段、体型、徽章与技能区分，没有这些品系。选项：不做（店铺只卖普通个体）／只做“迷你”体型的普通个体（沿用同一套属性与技能，不新增登记）／做独立品系（新种族登记与专属饲养规则）。影响：种族登记与存档契约、活体库存与定价、体型与成长规则。

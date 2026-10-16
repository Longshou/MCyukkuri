"""Merge only this package's language keys and export reward-free survival advancements."""
from pathlib import Path
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
LANG=ROOT/'src/main/resources/assets/mcyukkuri/lang'
DATA=ROOT/'src/main/resources/data/mcyukkuri'
# Each row is Chinese, English, Japanese. Keep individual vanilla-book pages short.
TEXT={
'block.mcyukkuri.cold_storage': ['冷冻收容柜','Cold-storage cabinet','冷凍保管庫'],
'freezer.mcyukkuri.store_failed':['收容失败：四格已满，或运输篮中的凭据已失效。','Cannot store: all four places are full, or the transport basket is stale.','保管できません。満庫、または輸送かごの記録が無効です。'],
'freezer.mcyukkuri.empty':['你的收容库为空。','Your cold-storage vault is empty.','あなたの保管庫は空です。'],
'freezer.mcyukkuri.count':['个人收容库：%s / %s。','Personal cold storage: %s / %s.','個人保管庫：%s / %s。'],
'freezer.mcyukkuri.instructions':['满运输篮右键存入，空运输篮右键取最早存入的一只。所有柜共用你的四格；拆柜后重建可取回。','Use a full transport basket to store; an empty transport basket retrieves the oldest occupant. All cabinets share your four places. Rebuild a broken cabinet to recover them.','満載のかごで預け、空のかごで古い順に受け取ります。全ての庫で自分の4枠を共有。庫を建て直せば回収できます。'],
'notebook.mcyukkuri.chapter.start':['观察手册\n\n手持笔记对空气使用：读指南。对油库里使用：看实际状态并登记种族。\n\n观察不会喂食、治疗或改变个性。每次都会读取当前状态。','Field notebook\n\nUse in the air for this guide. Use on a yukkuri to read its current state and record its species.\n\nObserving does not feed, heal or change personality.','観察手帳\n\n空中で使うと案内を開きます。ゆっくりに使うと現在の状態を読み、種族を記録します。\n観察では給餌や治療は行いません。'],
'notebook.mcyukkuri.chapter.route':['从零开始\n\n书＋羽毛→观察笔记。\n箱子＋两根线→运输篮。\n碗＋两个黏土球→食盆。\n干草块＋白色羊毛＋线→草窝。\n三块橡木木板＋沙子→油厕。\n\n先搭屋顶，留出入口，再带回个体。','First supplies\n\nBook + feather: notebook.\nChest + 2 string: transport basket.\nBowl + 2 clay balls: food bowl.\nHay bale + white wool + string: nest.\n3 oak planks + sand: potty.\nBuild a roof and clear entrance first.','最初の準備\n\n本＋羽根：手帳\nチェスト＋糸2：かご\nボウル＋粘土玉2：餌皿\n干草＋白い羊毛＋糸：巣\nオークの板3＋砂：トイレ\n屋根と入口を先に用意しましょう。'],
'notebook.mcyukkuri.chapter.discovery':['发现与运输\n\n探索平原、森林和草甸，留意野外家庭与住所。\n\n用运输篮对活体使用装入，对有落脚点的干燥空地使用放出。墙壁、积水或重复凭据会阻止放出，篮内个体仍保留。','Discovery and transport\n\nExplore plains, forests and meadows. Look for families and shelters.\nUse a transport basket on a living yukkuri, then on dry supported ground. Blocked space or water prevents release; the transport basket stays full.','発見と輸送\n\n平原・森・草地で家族や住処を探します。\n生きた個体をかごに入れ、足場のある乾いた空間に放します。壁や水で出せない場合、かごの中に残ります。'],
'notebook.mcyukkuri.chapter.care':['吃饭与清洁\n\n把苹果、面包或油粮放进食盆。取到嘴里后，还需咀嚼吞下才获得营养。\n\n湿布可清理油厕；对水源使用干布可浸湿。食物、粪便和遗留物都有实际数量，清理会改变现场。','Food and cleaning\n\nPut apples, bread or oil grain in a bowl. Food provides nutrition only after swallowing.\nWet a cloth at a water source. Use it to clean the potty. Food, waste and cleaning all use real items.','食事と掃除\n\n餌皿にリンゴ・パン・ゆっくり用の餌を入れます。飲み込んでから栄養になります。\n布を水源で濡らしてトイレを掃除します。餌も排泄物も実物を扱います。'],
'notebook.mcyukkuri.chapter.water':['饮水与药品\n\n口干、便秘或吃辣后，可递清水药水瓶或酸奶饮品。口服效果在吞下时生效。\n\n弹珠汽水喷雾先等待再入睡；兴奋药只增加繁殖驱动力。幼儿语药改变口吻，不会变小。查看消耗品页确认剩余时间。','Water and medicine\n\nOffer water potions or yogurt for dryness, constipation or spicy food. Oral effects begin at swallowing.\nRamune spray has a delay. Stimulant adds drive; baby-talk medicine changes speech, not body age.','飲水と薬\n\n口の乾き・便秘・辛い餌には水瓶やヨーグルト。飲み込んで効果が出ます。\nラムネスプレーには待ち時間があります。興奮薬は欲求、幼児語薬は口調に作用します。'],
'notebook.mcyukkuri.chapter.social':['熟悉与互动\n\n空手使用可交谈，潜行回应不同。教学哨用于学习；同伴绳介绍合适的成体。\n\n情感页显示亲昵需求、无聊和日常活动次数。害怕、饥饿或危险会改变行为，个性数值不是指令开关。','Familiarity\n\nUse an empty hand to speak; sneaking changes the response. Teach with a whistle and introduce compatible adults with a companion cord.\nThe needs page records affection, boredom and daily activities.','親しみと交流\n\n素手で話しかけ、しゃがむと応答が変わります。笛で教え、仲間の紐で相性の合う成体を紹介できます。\n感情ページには親愛欲求・退屈・日常活動を表示します。'],
'notebook.mcyukkuri.chapter.home':['安排住所\n\n用笔记点草窝或野外住所以选择，再潜行点个体安排入住。必须有真实入口和落脚空间。\n\n洞穴可铺叶子，几根细枝只是结界，不挡雨也不挡敌人。纸箱不能由油库里修复。','A place to rest\n\nUse the notebook on a nest or shelter to select it, then sneak-use on a yukkuri to assign it. A real entrance and standing space are required.\nTwig boundaries do not block rain or enemies.','住処の指定\n\n手帳で巣や住処を選び、しゃがんで個体に使うと入居を指定します。入口と足場が必要です。\n小枝の結界は雨や敵を防ぎません。'],
'notebook.mcyukkuri.chapter.family':['家庭与成长\n\n为相互熟悉的健康成体提供邻近住所、食物和休息。配对不保证立即怀孕，人口与睡位也会限制出生。\n\n亲缘保存真实父母身份；同住不是亲属。幼体吃饱并休息后逐步成长。','Family and growth\n\nHealthy familiar adults need nearby homes, food and rest. Pairing does not guarantee pregnancy; population and sleeping space also matter.\nAncestry records actual parents. Young grow with nourishment and rest.','家族と成長\n\n健康で親しい成体に近い住処、餌、休息を用意します。紹介しても即妊娠せず、人口や寝床の制限もあります。\n血縁は実際の親を記録します。'],
'notebook.mcyukkuri.chapter.birth':['茎生、胎生与卵生\n\n普通种可有茎生；生殖用馅需对合适成体持续投药，成功后产生真实胎次，父体记为未知。\n\n胎次页显示方式、进度与未出生数量。家油使用巢卵。收容母体暂停体内孕期，不会让离体茎或巢卵一起停止。','Birth\n\nStandard kinds can bear stems. Apply reproductive filling continuously to an eligible adult for assisted live pregnancy; the father is unknown.\nThe brood page shows progress. House kinds lay eggs. Detached broods stay in the world.','出産\n\n通常種には茎生があります。適切な成体へ生殖用餡を続けて与えると胎生の妊娠が成立し、父は不明になります。\n胎次ページで進行を確認。家ゆは巣卵を使います。'],
'notebook.mcyukkuri.chapter.house':['家油生活\n\n在遮雨处放家油缝隙，留出入口。提供碎食、纸、线或小麦，让家油实际搬运筑巢。\n\n巢卵需要食物与亲代照料。潜行清扫巢位会影响卵与垫料，清理前先观察。','House kinds\n\nPlace a house crevice under shelter with a clear entrance. Provide scraps, paper, string or wheat for carrying and nesting.\nEggs need food and care. Sneak-cleaning a nest affects eggs and lining; inspect it first.','家ゆの生活\n\n屋根のある場所に隙間を置き、入口を空けます。食べくず、紙、糸、小麦を運んで巣作りします。\n卵には食事と世話が必要。しゃがみ掃除の前に巣を確認しましょう。'],
'notebook.mcyukkuri.chapter.body':['身体状态\n\n观察伤口、感染、馅量与油霉。橙汁和去霉药用途不同，先查状态再使用。\n\n短暂擦洗不同于持续雨淋和泡水。普通种不能靠投食引诱安全横渡河流；有专用水面形态时另查其状态。','Body condition\n\nCheck wounds, infection, filling and mold. Orange juice and mold remover have different uses.\nA short wash differs from prolonged rain or immersion. Food lures do not make ordinary kinds safe river swimmers.','身体の状態\n\n傷・感染・餡量・カビを確認します。オレンジジュースとカビ取り薬は用途が違います。\n短い清拭と雨や水への長時間の接触を区別しましょう。'],
'notebook.mcyukkuri.chapter.tools':['工具与中断\n\n工具作用于真实身体或持有物；按界面说明选择部位和操作。扣除、损伤、清洁与治疗不是同一个效果。\n\n需要持续执行的动作可能被距离、危险或目标移动打断。再次操作前检查物品与状态。','Tools and interruption\n\nTools affect real body parts or held items. Follow the operation menu for the target and action.\nDistance, danger or movement can interrupt continuous work. Check the item and condition before trying again.','道具と中断\n\n道具は実際の身体や所持品に作用します。操作画面で部位と行動を確認します。\n距離・危険・移動で継続操作が中断することがあります。再操作前に状態を確認してください。'],
'notebook.mcyukkuri.chapter.play':['游乐与环境\n\n毛线球、弹珠、橡胶球与布偶供探看和玩耍。纸筒、陶管、滑梯与蹦床需要可走到的入口和落脚点。\n\n垫料改变接地条件；砂纸会磨伤。水池的水仍会带来湿润与浸水风险。','Play and surroundings\n\nOffer yarn, marbles, balls or dolls. Tunnels, slides and trampolines need reachable entrances and landings.\nLining changes surface contact; sandpaper abrades. Pools still cause wetness and immersion.','遊びと環境\n\n毛糸玉、ビー玉、ボール、人形で遊べます。筒・滑り台・トランポリンには入口と着地点が必要です。\n紙や土は接地を変え、紙やすりは擦り傷の原因になります。'],
'notebook.mcyukkuri.chapter.storage':['冷冻收容\n\n铁锭×4、雪块×2、箱子、红石、末影珍珠合成冷冻柜。\n\n满运输篮点柜存入；每位玩家四格，所有柜共用。库内年龄、生理和体内孕期暂停，不造成冻伤。新造柜不会增加格数。','Cold storage\n\nCraft with 4 iron ingots, 2 snow blocks, a chest, redstone and an ender pearl.\nUse a full transport basket on it. All cabinets share four places per player. Age, physiology and internal pregnancy pause without cold damage.','冷凍保管\n\n鉄4・雪ブロック2・チェスト・レッドストーン・エンダーパールで作ります。\n満載のかごで預けます。全ての庫で個人の4枠を共有。年齢・生理・体内の妊娠が止まります。'],
'notebook.mcyukkuri.chapter.retrieval':['取回与拆柜\n\n空运输篮点柜：取最早存入的一只，再到干燥空地放出。空手点柜可看数量和种类。\n\n拆柜不会丢掉库内个体；重建任意柜仍可取回。玩家死亡与跨维度不清空收容库。普通运输不算弃养。','Retrieving\n\nUse an empty transport basket to take the oldest occupant, then release on dry ground. Empty-hand use lists the vault.\nBreaking a cabinet does not delete occupants. Rebuild to recover them, including after death or travel.','引き取り\n\n空のかごで古い順に引き取り、乾いた場所へ放します。素手で中身を確認。\n庫を壊しても個体は消えません。死亡や別次元への移動後も、建て直して回収できます。'],
'notebook.mcyukkuri.chapter.commerce':['人类设施\n\n村落附近可寻找人类照顾者、商店和工厂。按实际菜单查看出售、照顾与委托条件。\n\n商店个体保留原身份；工厂的育成与出货需要实际亲代、物料和道路。观察到设备不表示你已完成其中工序。','Human facilities\n\nLook for caretakers, shops and factories near settlements. Their menus show current stock and conditions.\nAnimals retain their identity in trade. Factory work requires real parents, supplies and routes.','人間の施設\n\n集落周辺で世話役、店、工場を探します。実際のメニューで在庫と条件を確認します。\n売買でも個体の身元は保たれます。工場には親・資材・通路が必要です。'],
'notebook.mcyukkuri.chapter.progress':['生存进度\n\n取得笔记→首次观察→安排住所。另可完成设施准备、冷冻存入、取回与真实家庭出生。\n\n进度在实际成功后记录一次，无经验或物品奖励。重复翻阅不会制造状态或反复领奖。','Survival progress\n\nGet a notebook, observe and assign a home. Also prepare care supplies, store and retrieve an occupant, and raise a real family.\nSuccessful events record once. There are no item or XP rewards to farm.','サバイバルの進捗\n\n手帳の入手、初観察、住処の指定、世話用品、冷凍の預け入れと引き取り、実際の出産を記録します。\n成功時に一度だけ記録し、経験値や品物の報酬はありません。'],
'notebook.mcyukkuri.chapter.status':['内容状态\n\n后面的种族页按当前登记列表生成；亲自观察才显示“已观察”。\n\n捕食种与新增亚种仍在分别验收，注册或有模型不等于全部动作通过。未完成候选不提供虚构取得路线。Patchouli、Jade兼容未接入。','Content status\n\nSpecies pages use the current registry; only actual observation marks discovery.\nPredators and new forms have separate acceptance checks. Registration is not full validation. Optional guide/HUD integrations are absent.','内容の状態\n\n種族ページは現在の登録に従い、実際に観察した種だけ記録済みになります。\n捕食種や新形態は個別検証中です。登録は全動作の検証完了を意味しません。'],
'notebook.mcyukkuri.chapter.species':['%s\n%s\n\n%s','%s\n%s\n\n%s','%s\n%s\n\n%s'],
'notebook.mcyukkuri.chapter.seen':['已观察','Observed','観察済み'],
'notebook.mcyukkuri.chapter.unseen':['尚未观察','Not yet observed','未観察'],
'notebook.mcyukkuri.chapter.standard_species':['普通生活类。观察个体可读到年龄、个性、家庭、身体和消耗品状态。\n\n种族名不是健康或智力等级；同种个体也会有不同经历。','Standard living kind. Observe the individual for age, personality, family, body and consumable state.\nA species name is not a health or intelligence rating.','通常の生活種です。個体の年齢・性格・家族・身体・薬の状態を観察できます。\n種族名は健康や知能の等級ではありません。'],
'notebook.mcyukkuri.chapter.house_species':['家油生态类。寻找碎食、搬运筑巢并通过巢卵繁殖。\n\n普通种进屋不会变成家油。巢与亲缘需要实际观察，不能根据小体型推定。','House ecology. These kinds collect scraps, carry nesting supplies and reproduce through nest eggs.\nAn ordinary yukkuri does not become a house kind merely by going indoors.','家ゆの生態です。食べくずを集め、材料を運んで巣を作り、巣卵で繁殖します。\n通常種が家に入っただけで家ゆにはなりません。'],
'notebook.mcyukkuri.chapter.predator':['捕食种。与普通宠物分开安排空间；正在接入飞行、捕食、巢穴与交易。\n\n该页表明实体已登记，不宣称所有动作或生态验收完成。以当前个体和菜单实际表现为准。','Predator kind. Plan separate space from ordinary pets. Flight, hunting, nests and trade are under separate implementation checks.\nThis registry entry does not certify every behavior.','捕食種です。通常のペットと空間を分けてください。飛行・捕食・巣・取引は個別に検証中。\nこの登録ページだけで全行動の検証完了とはしません。'],
'notebook.mcyukkuri.chapter.no_brood':['当前胎次\n\n没有进行中的体内胎次或茎生胎次。离体茎与巢卵请检查对应设施。','Current brood\n\nNo active internal or attached-stem brood. Inspect the actual nest or detached stem for external broods.','現在の胎次\n\n進行中の体内・付着茎の胎次はありません。離れた茎や巣卵はその施設を確認してください。'],
'notebook.mcyukkuri.chapter.brood':['当前胎次\n\n方式：%s\n进度：%s%%\n未出生：%s\n父体：%s\n\n空间、照料和中断会影响出生。收容不会新建胎次。','Current brood\n\nKind: %s\nProgress: %s%%\nUnborn: %s\nFather: %s\n\nSpace, care and interruption can delay birth. Storage preserves the existing brood.','現在の胎次\n\n方式：%s\n進行：%s%%\n未出生：%s\n父：%s\n\n空間・世話・中断で出産が遅れることがあります。保管でも同じ胎次を維持します。'],
'notebook.mcyukkuri.chapter.birth_live':['胎生','Live birth','胎生'],
'notebook.mcyukkuri.chapter.birth_stem':['茎生','Stem birth','茎生'],
'notebook.mcyukkuri.chapter.birth_egg':['巢卵','Nest eggs','巣卵'],
}
TEXT.update({
'notebook.mcyukkuri.chapter.social':['熟悉与互动\n\n空手右键温和交谈；潜行空手打开照顾菜单，可选冷淡回应等操作。教学哨用于学习，同伴绳介绍成体。\n\n情感页显示亲昵需求、无聊和活动次数。危险与生理需要会打断互动。','Familiarity\n\nEmpty-hand use speaks gently. Sneak-use opens the care menu, including colder replies. Teach with a whistle; introduce adults with a cord.\nThe needs page shows affection, boredom and activities. Danger interrupts care.','親しみと交流\n\n素手で優しく話し、しゃがんで使うと世話メニューを開きます。笛で教え、紐で成体を紹介します。\n感情ページで親愛欲求や退屈を確認。危険で交流は中断します。'],
'notebook.mcyukkuri.chapter.accessories':['饰物与徽章\n\n潜行并用剪刀取下饰物；把原饰物对原主人使用可归还。饰物有主人，复制外观不能复制身份。\n\n失饰可能影响同伴识别，不删除家谱。徽章的获得与补发，请看宠物手册，以及考官处的考试菜单。','Accessories and badges\n\nSneak-use shears to remove an accessory; use it on its original owner to return it. Appearance copies do not copy identity.\nLoss can affect recognition, but not ancestry. For exams and reissues, see the Pet Handbook and the Examiner at the shop.','お飾りとバッジ\n\nしゃがんでハサミを使うとお飾りを外し、元の持ち主に使うと返せます。見た目の複製では身元を複製できません。\n失っても家系は消えません。バッジの取得と再発行は、ペット手帳と店の試験官で確認します。'],
'notebook.mcyukkuri.chapter.community':['野外家庭与遭遇\n\n森林可有多户聚落，亲代会照料真实幼体；流浪油也会觅食找住所。观察群落页区分成员、领袖和床位。\n\n野外情景卡仍在专项验收。观察不算喂食或收养；明确弃养与普通运输分开。','Wild families\n\nForest communities can include several households. Observe membership, leadership and sleeping places.\nWild encounter cards have separate checks. Watching is neither feeding nor adoption; transport is not abandonment.','野外の家族\n\n森の集落には複数の家族が住みます。観察で所属・指導者・寝床を確認します。\n遭遇イベントは個別検証中です。観察は給餌や引き取りと異なり、輸送は放棄ではありません。'],
'notebook.mcyukkuri.chapter.form_entry':['%s\n%s\n\n这是独立的亚种或先天形态记录。种族、形态与后天乘船分别保存。\n\n新增外观与行为仍需分别验收；体型和毛量不能用来推断智力。','%s\n%s\n\nA separate subspecies or congenital form. Species, shape and acquired boat use are recorded independently.\nNew appearances and behaviors have separate checks; shape does not determine intelligence.','%s\n%s\n\n亜種または先天的な形態の記録です。種族・形態・後天的な乗船を分けて保存します。\n外見や毛量だけで知能は決まりません。'],
'notebook.mcyukkuri.chapter.form':['个体形态\n\n亚种：%s\n先天形态：%s\n帽船：%s\n乘船经历：%s\n\n帽船是后天移动方式，不会把普通种变成螺壳种。','Individual form\n\nSubspecies: %s\nCongenital form: %s\nHat boat: %s\nBoat experience: %s\n\nUsing a hat boat does not turn an ordinary kind into a shell kind.','個体の形態\n\n亜種：%s\n先天形態：%s\n帽子船：%s\n乗船歴：%s\n\n帽子船は後天的な移動手段です。螺殻種へ変わることはありません。'],
'notebook.mcyukkuri.chapter.no_boat':['当前无帽船','No current hat boat','現在の帽子船なし']
})
ADV={
'root':(['开始观察','Start observing','観察の始まり'],['取得观察笔记','Obtain a field notebook','観察手帳を入手する'],'mcyukkuri:field_notebook',None),
'observe':(['认识一只油库里','Meet a yukkuri','ゆっくりを知る'],['实际使用手册观察个体','Use the notebook on a living individual','実際に手帳で個体を観察する'],'mcyukkuri:field_notebook','root'),
'supplies':(['准备生活空间','Prepare a home','生活の準備'],['同时备好食盆、草窝和油厕','Have a food bowl, nest and potty together','餌皿・巣・トイレを揃える'],'mcyukkuri:food_bowl','root'),
'shelter':(['有个落脚处','A place to rest','休める場所'],['用手册成功安排真实住所','Successfully assign an actual home with the notebook','手帳で実際の住処を指定する'],'mcyukkuri:yukkuri_nest','observe'),
'cold_storage':(['暂时安稳收容','Safe keeping','一時保管'],['把活体从运输篮存入个人收容库','Store a living occupant from its carrier','かごの生体を個人保管庫へ預ける'],'mcyukkuri:cold_storage','supplies'),
'retrieve':(['接回同行','Reunited for travel','引き取り'],['用空运输篮取回原个体','Retrieve the original occupant into an empty carrier','空のかごへ元の個体を引き取る'],'mcyukkuri:yukkuri_carrier','cold_storage'),
'family_birth':(['家庭添丁','A new family member','新しい家族'],['你照顾的亲代有真实幼体出生','An animal in your care has a real offspring born','世話している親から実際に子が生まれる'],'mcyukkuri:yukkuri_nest','shelter'),
}
outputs={}
for event,(titles,descriptions,icon,parent) in ADV.items():
    TEXT['advancement.mcyukkuri.'+event+'.title']=titles;TEXT['advancement.mcyukkuri.'+event+'.description']=descriptions
    display={'icon':{'id':icon},'title':{'translate':'advancement.mcyukkuri.'+event+'.title'},'description':{'translate':'advancement.mcyukkuri.'+event+'.description'},
             'frame':'task','show_toast':True,'announce_to_chat':False,'hidden':False}
    if parent is None:display['background']='minecraft:textures/block/bookshelf.png'
    criterion={'trigger':'minecraft:impossible'}
    if event=='root':criterion={'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':['mcyukkuri:field_notebook']}]}}
    if event=='supplies':criterion={'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':['mcyukkuri:'+item]} for item in ['food_bowl','yukkuri_nest','yukkuri_toilet']]}}
    value={'display':display,'criteria':{'performed':criterion},'requirements':[['performed']]}
    if parent:value['parent']='mcyukkuri:survival/'+parent
    outputs[DATA/'advancement/survival'/f'{event}.json']=value
outputs[DATA/'advancement/recipes/cold_storage.json']={'parent':'minecraft:recipes/root','criteria':{'has_pearl':{'trigger':'minecraft:inventory_changed','conditions':{'items':[{'items':['minecraft:ender_pearl']}]}}},'requirements':[['has_pearl']],'rewards':{'recipes':['mcyukkuri:cold_storage']}}
check='--check' in sys.argv
for path,value in outputs.items():
    content=json.dumps(value,ensure_ascii=False,indent=2)+'\n'
    if check:
        assert path.is_file() and json.loads(path.read_text(encoding='utf-8'))==value,path
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8')
for i,locale in enumerate(('zh_cn','en_us','ja_jp')):
    path=LANG/f'{locale}.json'
    current=json.loads(path.read_text(encoding='utf-8'))
    selected={key:row[i] for key,row in TEXT.items()}
    if check:
        for key,value in selected.items():assert current.get(key)==value,(locale,key)
    else:
        current.update(selected)
        path.write_text(json.dumps(current,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(('Verified' if check else 'Merged'),len(TEXT),'guide/storage/progress keys in three languages;',len(outputs),'advancement files')

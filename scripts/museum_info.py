"""What each station tells the visitor: the board that hangs in front of it and the preset it needs.

Kept apart from the stand definitions (museum_scenes.py) so wording can change without touching the scenes.
Every board has the same shape (see ``board_parts``): what to watch / what to press / how long / what if
nothing happens, then a short English and Japanese line. Chinese is the primary language.

``env`` presets are applied by ``go/s/<id>`` and by the demo buttons; every other station restores noon and
clear weather, so only one station at a time keeps a different time of day or weather:
    time: noon | morning | midnight      weather: clear | rain      biome: filled once when the map is built
"""

INFO = {
    'body': dict(
        watch='灵梦的圆身体怎样走、转、跳、伸展、做体操',
        press='按 1 走路 / 2 伸展 / 3 体操，一次一段',
        wait='每段 10–25 秒，播完自动站回原位',
        fail='按“重置本站”后再按一次',
        en='Clips: walk, hop, stretch, exercise.',
        ja='歩く・跳ねる・伸びる・体操の動き。'),
    'daily': dict(
        watch='饿了自己去吃苹果，困了自己回窝睡；洗后甩水',
        press='按 1 取食（真实）/ 2 回窝（真实）/ 3 甩水（片段）',
        wait='取食约 1 分钟，回窝约 1.5 分钟；窗口结束后行为继续',
        fail='按“重置本站”，再按 1 或 2',
        en='Real eating and napping, then a shake clip.',
        ja='食事と昼寝は実際の行動、甩水は動作クリップ。'),
    'social': dict(
        watch='橙与爱丽丝问候、蹭蹭、合唱；鼓腮、受惊、害羞、得意',
        press='按 1 相遇 / 2 合唱 / 3 情绪 / 4 表情接力',
        wait='每段 10–30 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='Greeting, nuzzling, singing and moods.',
        ja='あいさつ・すりすり・歌・気持ちの動き。'),
    'care': dict(
        watch='橙受伤后被照料；便意来了去厕所，或就地排便',
        press='按 1 受伤照料（会发橙汁、绷带）/ 2 厕所排便 / 3 就地排便',
        wait='每项观察 1.5 分钟，窗口结束后行为继续',
        fail='按“重置本站”；工具已发到你手上，对橙使用',
        en='Wound care with real tools; toilet vs floor.',
        ja='ケガの手当てと、トイレ・床での排泄。'),
    'birth': dict(
        watch='亲油在庭院里真实生产：父油摘帽接住，或来不及漏接',
        press='按 1–4 去对应庭院的观察台；5 补口粮',
        wait='约 1–2 分钟生产，之后看照料；每对双亲只生一胎',
        fail='没开始：看聊天栏红字；在观察台按“换一对新油”重来',
        en='Real births in yards: caught vs missed.',
        ja='庭で実際に出産。受け止め／取りこぼしを選ぶ。'),
    'feeding': dict(
        watch='左：亲油把苹果嚼成糜喂幼体；右：亲油舔舐帮幼体首次排便',
        press='按 1 左侧嚼糜喂养 / 2 右侧舔舐首便',
        wait='每项约 1.5 分钟，窗口结束后行为继续',
        fail='按“重置本站”后再按；两项不要同时看',
        en='Chewed food and licking for a first stool.',
        ja='噛み砕いた食事と、なめて促す初めての排便。'),
    'hopping': dict(
        watch='亲油示范起跳，子油跟着练，学会后被夸',
        press='按 1 学起跳 / 2 一起活动',
        wait='每段约 20–30 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='A parent teaches a young one to hop.',
        ja='親が跳び方を教え、子が真似する。'),
    'growth': dict(
        watch='赤油、子油、亚成油、成油四个阶段；亚成油离家独立筑新巢',
        press='按 1 一路长大（片段）/ 2 开始独立（真实）/ 3 去观察台',
        wait='独立要等十分钟以上：请在庭院观察台等；期间别按 1',
        fail='要重来：按“重置本站”；它会结束这次独立，已建的巢可能保留',
        en='Four growth stages; a teen builds a nest.',
        ja='成長の四段階と、巣立ちして新しい巣を作る様子。'),
    'house': dict(
        watch='家油在自己的庭院里拾棉絮、筑巢、入巢，满足条件才产卵',
        press='按 1 去庭院观察台开始 / 2 领食屑和棉絮，自己丢给家油',
        wait='筑巢约几分钟，孵化更久；庭院里的东西是真实库存',
        fail='丢些食屑到家油附近；观察台可“重置家油庭院”',
        en='House yukkuri gather lint, build a nest.',
        ja='家ゆっくりが綿を集めて実際に巣を作る。'),
    'boatlife': dict(
        watch='水生魔理沙乘自己的帽船：备粮、划桨、返岸收帽，幼体载荷不同',
        press='按 1 备粮划桨 / 2 返岸收帽 / 3 两幼体 / 4 四幼体',
        wait='数十秒到数分钟；船没回岸时不能重置',
        fail='按 5 “安全回岸重置”；红字会说明原因',
        en='Hat boats: paddling, cargo, young aboard.',
        ja='帽子の舟で食料集め・漕ぎ・帰岸。'),
    'voyage': dict(
        watch='小子油放下帽子、跳进自己的帽船，第一次划出去再回来',
        press='按 1 真实第一次出航，然后等待',
        wait='约 1–2 分钟；中途请勿重置',
        fail='按 2 “安全回岸重置”后再按 1',
        en='A young marisa launches her first boat.',
        ja='子ゆっくりが初めて帽子の舟で漕ぎ出す。'),
    'rescue': dict(
        watch='孩子落水：亲油伸辫救起，或孩子自己抖落帽子、爬回帽上',
        press='按 1 亲船上岸 / 2 落水自救 / 3 亲油救援',
        wait='约 30–90 秒；救援没完成前不能重置',
        fail='按 4 “安全回岸重置”后再按；红字会说明原因',
        en='Falling in and being rescued.',
        ja='水に落ちた子の救助と自力で戻る様子。'),
    'exam': dict(
        watch='灵梦考试：紧张、作答、躲猫猫，再看合格或不合格',
        press='按 1 合格结局 / 2 不合格结局',
        wait='每段约 40 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='A pet exam: pass and fail endings.',
        ja='ペット試験。合格と不合格。'),
    'badge': dict(
        watch='别上银章的宠物，旁边的野生个体羡慕或嫉妒',
        press='按 1 别上银章 / 2 嫉妒与摘章',
        wait='每段约 30 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='A pinned badge and a wild onlooker.',
        ja='バッジをつけたペットと羨む野生の子。'),
    'wild': dict(
        watch='野生个体来讨吃或挑衅，宠物应对：不理会，或没忍住',
        press='按 1 不理会 / 2 没忍住',
        wait='每段约 30 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='A wild beggar and a pet who copes or fails.',
        ja='野良の子へのペットの対応。'),
    'pred': dict(
        watch='银章的蕾米莉亚听令，没学会等待的芙兰不听；猎物逃跑',
        press='按 1 听令与忍住 / 2 不服从 / 3 猎物逃跑',
        wait='每段约 15–25 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='Predators that obey, and ones that do not.',
        ja='命令に従う捕食種と従わない子。'),
    'date': dict(
        watch='饲养员牵线：灵梦与魔理沙害羞、走近、蹭蹭',
        press='按 1 饲养员相亲',
        wait='约 25 秒，播完自动归位',
        fail='按“重置本站”后再按一次',
        en='A matchmaking meeting: shy, closer, nuzzle.',
        ja='飼育員の仲立ちで会う。'),
    'shop': dict(
        watch='店主、大哥哥、在售的爱丽丝；大哥哥身旁有一份食物',
        press='按 1 看大哥哥身旁的食物（真实）；走近右键店主开店',
        wait='取食约 1 分钟；商店为真实库存与成交',
        fail='按“重置本站”；右键店主才会开店',
        en='Shopkeeper, buyers and a real snack.',
        ja='店主・お兄さん・売り物のゆっくり。'),
    'transport': dict(
        watch='喷雾让野生个体睡着再装篮，放出后仍是同一只',
        press='按 1 灵梦装篮 / 2 领喷雾和篮 / 3 换新野生；围栏里是野生',
        wait='装篮约 1–2 分钟；自己动手',
        fail='篮不肯收：先喷雾；野生跑了按 3 换新',
        en='Spray, basket, release: same identity.',
        ja='スプレーで眠らせてカゴへ。同じ個体のまま。'),
    'biomes': dict(
        watch='季节由群系决定，不看时钟：同一块地换群系，季节随之改变',
        press='按 1 春 / 2 夏 / 3 秋 / 4 冬，聊天栏会报告判定结果',
        wait='立刻生效',
        fail='重按；聊天栏显示当前群系和季节',
        en='Season follows the biome, not the clock.',
        ja='季節は時間ではなくバイオームで決まる。'),
    'autumn': dict(
        watch='秋季森林：灵梦割草、衔回洞口，存进储粮堆；橙留守',
        press='按 1 真实采集储粮',
        wait='观察窗口 2 分钟，之后行为继续',
        fail='按“重置本站”重铺草丛后再按 1',
        en='Autumn: gather forage into the nest store.',
        ja='秋の森で草を集めて貯蔵する。',
        env=dict(time='noon', weather='clear', biome='minecraft:forest')),
    'winter': dict(
        watch='冬季雪原：下雪或夜里待在窝里，吃存粮，大的让食给小的',
        press='按 1 回窝取粮 / 2 下雪 / 3 夜晚 / 4 放晴',
        wait='观察窗口 2 分钟；本站会让全馆下雪',
        fail='不回窝：按 2 或 3；离站按“放晴”或下一站',
        en='Winter: stay in the nest and eat the store.',
        ja='冬は巣にこもって貯えを食べる。',
        env=dict(time='noon', weather='rain', biome='minecraft:snowy_plains', keep_time=True)),
    'settle': dict(
        watch='真实的森林聚落：族长、住所、采集、迁居；东门观察台能看到演示站点和空站点',
        press='按 1 建立聚落 / 2 执行采集 / 3 尝试迁居 / 4 安全重置 / 5 去东门观察台',
        wait='采集可能要等 1–2 分钟；白天且族长在附近才会派工',
        fail='看聊天栏；观察台有“清晨集会”和“正午”',
        en='A real forest settlement, seen from the gate.',
        ja='森の集落。東門の展望台から見る。'),
}

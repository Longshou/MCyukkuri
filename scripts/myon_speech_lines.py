"""Myon (Youmu) full speech library and the kotatsu-Marisa lines, imported by build_speech_tiers.py.

Worldview (komicolle 40720 11518905): Myon has two mutually exclusive tics ("myon" / "chinpo"); she is strong, brave
and a little dim, a hard-working forager, and carries a stick she calls her Kanroken. Every line is a
project-original composition, unvoiced.

Frames are (jp, zh, en). {t} is the speaker's own tic (filled per quirk, exactly once per line); baby frames use {c},
the quirk's babble cry. Lines never use the self-name "みょん" so that a chinpo individual never says the other tic.
"""

MYON_BASIS = 'komicolle 40720 11518905 Myon tics/brave, dim, stick sword; project-original wording, unvoiced'
KOTATSU_BASIS = 'kotatsu Marisa (summer: too hot, sleeps in the cool shade; winter: warm kotatsu); project-original wording, unvoiced'

TICS = {  # quirk -> (jp, zh, en); the young tier uses the same forms
    'miyo': ('みょん', '咪呦', 'Myon'),
    'kinbao': ('ちーんぽ', '金寶', 'Chinpo'),
}

# event -> tier -> (jp, zh, en). Meanings follow the Alice / Marisa reference lines of the same event.
LINES = {
    'afraid': {
        'adult': ('{t}！ こ、こわくない……こわくないっ！', '{t}！不、不怕……妖梦不怕！', '{t}! N-not scared... not scared!'),
        'young': ('{t}！ こ、こわくにゃい……こわくにゃいっ！', '{t}！不、不怕怕……窝不怕！', '{t}! N-not scawed... not scawed!')},
    'ask_food': {
        'adult': ('{t}！ おなかすいた！ ごはん、ちょうだい！', '{t}！肚子饿了！给妖梦饭饭！', '{t}! Hungry! Food, please!'),
        'young': ('{t}！ おなかしゅいた！ ごはん、ちょうらい！', '{t}！肚肚饿惹！给窝饭饭！', '{t}! Hungwy! Food, pwease!')},
    'ask_play': {
        'adult': ('ひまだ……{t}！ いっしょに、ちゃんばらしよう！', '好无聊……{t}！一起来比剑吧！', "So bored... {t}! Let's play swords!"),
        'young': ('ひまぁ……{t}！ いっちょに ちゃんばら ちよ！', '好无聊……{t}！一起玩比剑吧！', 'So bowed... {t}! Pway swords!')},
    'ask_snack': {
        'adult': ('{t}！ あまあま、ほしい！ ちょうだい！', '{t}！想要甜甜！给我嘛！', '{t}! Want sweets! Please!'),
        'young': ('{t}！ あみゃあみゃ、ほちい！ ちょうらい！', '{t}！想要甜甜！给窝嘛！', '{t}! Want sweeties! Pwease!')},
    'ask_walk': {
        'adult': ('{t}！ おさんぽ、いく！ みまわりもする！', '{t}！去散步！顺便巡逻！', "{t}! Walk time! I'll patrol too!"),
        'young': ('{t}！ おしゃんぽ、いく！', '{t}！窝要去散步！', '{t}! Walkies!')},
    'awake': {
        'adult': ('{t}！ ゆっくりおはよう！ きょうもがんばる！', '{t}！油库里早上好！今天也要加油！', '{t}! Good morning! Working hard today too!'),
        'young': ('{t}！ ゆっくち おはよー！', '{t}！油库气，早上好！', '{t}! Mownin!')},
    'badge_admire': {
        'adult': ('{t}！ すごい！ おしえて、おしえて！', '{t}！好厉害！教教妖梦，教教妖梦！', '{t}! Amazing! Teach me, teach me!'),
        'young': ('{t}！ しゅごい！ おしえちぇ！', '{t}！好厉害！教教窝！', '{t}! Amazin! Teach me!')},
    'badge_ask': {
        'adult': ('それ、たべられる？ ……{t}？ さわってもいい？', '那个能吃吗？……{t}？可以摸摸吗？', 'Can you eat that? ...{t}? Can I touch it?'),
        'young': ('それ、たべれりゅ？ {t}？ しゃわってみたい！', '那个能吃吗？{t}？窝想摸摸！', 'Can you eat dat? {t}? Wanna touch!')},
    'badge_beg': {
        'adult': ('{t}……ひとくちだけ、わけてほしい……', '{t}……就一口，分我一点……', '{t}... just one bite, please...'),
        'young': ('{t}……ひとくちだけ、ちょうらい……', '{t}……就一口，给窝嘛……', '{t}... just one bite, pwease...')},
    'badge_envy': {
        'adult': ('いいな……{t}。あのバッジ、ほしい。', '真好……{t}。妖梦也想要那个徽章。', 'Lucky... {t}. I want that badge.'),
        'young': ('いいにゃあ……{t}。ばっじ、ほちい。', '真好……{t}。窝也想要徽章。', 'Lucky... {t}. Want dat badge.')},
    'badge_envy_snark': {
        'adult': ('{t}！ かいゆは、たたかえない。よわい！', '{t}！当宠物的不会打架。弱！', "{t}! Pets can't fight. Weak!"),
        'young': ('{t}！ かいゆは、よわっちい！', '{t}！宠物弱弱的！', '{t}! Pets are weak-weak!')},
    'badge_glance': {
        'adult': ('ぴかぴか……あれ、なに？ {t}？', '亮晶晶……那是什么？{t}？', 'Shiny... what is that? {t}?'),
        'young': ('ぴかぴか……あれ、にゃあに？ {t}？', '亮晶晶……那是啥？{t}？', 'Shiny... whazzat? {t}?')},
    'badge_humble': {
        'adult': ('{t}。まだ、しゅぎょうちゅう。', '{t}。妖梦还在修行中。', "{t}. I'm still training."),
        'young': ('{t}。まだ、しゅぎょうちゅう！', '{t}。窝还在修行！', '{t}. Still twaining!')},
    'badge_mock': {
        'adult': ('かいゆのくせに、えらそう！ {t}！', '一个宠物还摆架子！{t}！', 'Just a pet, acting so big! {t}!'),
        'young': ('かいゆのくちぇに、えらしょう！ {t}！', '宠物还摆架子！{t}！', 'Jus a pet, acting big! {t}!')},
    'badge_pinned': {
        'adult': ('{t}！ バッジ、もらった！ がんばったしるし！', '{t}！拿到徽章了！是努力的证明！', '{t}! Got a badge! Proof I worked hard!'),
        'young': ('{t}！ ばっじ、もらっちゃ！ ぴかぴか！', '{t}！拿到徽章惹！亮晶晶！', '{t}! Got a badge! Shiny!')},
    'badge_pity': {
        'adult': ('バッジ、なくなった……？ {t}、だいじょうぶ？', '徽章没了……？{t}，没事吧？', 'Your badge is gone...? {t}, you okay?'),
        'young': ('ばっじ、にゃくなった……？ {t}、だいじょぶ？', '徽章没惹……？{t}，没事吧？', 'Badge gone...? {t}, okay?')},
    'badge_proud': {
        'adult': ('{t}！ みて、バッジ！ つよいしるし！', '{t}！看，徽章！是强大的证明！', "{t}! Look, a badge! Proof I'm strong!"),
        'young': ('{t}！ みちぇ、ばっじ！ しゅごい？', '{t}！看，徽章！厉害吧？', '{t}! Look, badge! Cool?')},
    'badge_pull_back': {
        'adult': ('あれに、ちかよっちゃだめ！ こっちにくる。{t}！', '不能靠近那个！到这边来。{t}！', "Don't go near that! Come here. {t}!"),
        'young': ('ちかよっちゃ、らめ！ こっち！ {t}！', '不能靠近！来这边！{t}！', 'No going near! Over hewe! {t}!')},
    'badge_retort': {
        'adult': ('{t}！ のらは、あっちいけ！', '{t}！野良，到那边去！', '{t}! Strays, go away!'),
        'young': ('{t}！ のらは、あっちいっちぇ！', '{t}！野良去那边！', '{t}! Stway, go away!')},
    'badge_share_offer': {
        'adult': ('はんぶんこ。{t}。ゆっくりたべて。', '分一半。{t}。慢慢吃吧。', 'Half each. {t}. Eat slowly.'),
        'young': ('はんぶんこ！ {t}！ ゆっくち たべちぇ！', '分一半！{t}！慢慢吃！', 'Half each! {t}! Eat swowly!')},
    'badge_share_refuse': {
        'adult': ('これは、わたしのごはん。だめ。{t}。', '这是妖梦的饭。不行。{t}。', 'This is my food. No. {t}.'),
        'young': ('これ、わたちの ごはん！ らめ！ {t}！', '这是窝的饭！不给！{t}！', 'Dis my food! No! {t}!')},
    'badge_show_kind': {
        'adult': ('{t}。みてもいい。にんげんさんが、つけてくれた。', '{t}。可以看。是人类先生给戴上的。', '{t}. You can look. A human pinned it on me.'),
        'young': ('{t}。みちぇいいよ。にんげんしゃんが つけちぇくれた！', '{t}。可以看。人类先生给窝戴的！', '{t}. You can look. A human put it on me!')},
    'badge_taken': {
        'adult': ('バッジが……ない。{t}……しょんぼり……', '徽章……没有了。{t}……好失落……', 'My badge... is gone. {t}... so sad...'),
        'young': ('ばっじ、にゃい……{t}……しょんぼり……', '徽章没惹……{t}……好失落……', 'Badge... gone... {t}... sad...')},
    'badge_thanks': {
        'adult': ('ありがとう……{t}。やっと、ゆっくりできる。', '谢谢……{t}。总算能油库里了。', 'Thank you... {t}. I can finally relax.'),
        'young': ('ありがちょう……{t}。ゆっくち できりゅ。', '谢谢……{t}。能油库气了。', 'Fank you... {t}. Can wewax now.')},
    'basket_enter': {
        'adult': ('{t}！ わかった、はいる！', '{t}！知道了，进去！', '{t}! Okay, going in!'),
        'young': ('{t}！ はいりゅ！ ゆっくち はいりゅ！', '{t}！进去！慢慢进去！', '{t}! Goin in! Swowly!')},
    'basket_exit': {
        'adult': ('ここが、あたらしいおうち？ {t}！ みまわりする！', '这里是新家？{t}！先巡逻一圈！', "Is this the new home? {t}! I'll patrol!"),
        'young': ('ここ、どこ？ {t}？ あたらちい おうち？', '这是哪？{t}？是新家吗？', 'Where is dis? {t}? New home?')},
    'basket_refuse': {
        'adult': ('{t}！ かごはいや！ はなせ！', '{t}！不要篮子！放开！', '{t}! No basket! Let go!'),
        'young': ('{t}！ かご、いやぁ！ はなちてぇ！', '{t}！不要篮子！放开窝！', '{t}! No basket! Lemme go!')},
    'birth': {
        'adult': ('{t}！ うまれる！', '{t}！要生了！', "{t}! It's coming!"),
        'young': ('{t}！ うみゃれりゅ！', '{t}！要生惹！', "{t}! It's comin!")},
    'boat_rock_startle': {
        'adult': ('{t}！ ふねが、ゆれる！', '{t}！船在摇！', '{t}! The boat is rocking!'),
        'young': ('{t}！ ふにぇが、ゆれりゅ！', '{t}！船在摇摇！', '{t}! Boat is wockin!')},
    'braid_rescue_grip': {
        'adult': ('くわえた！ はなさない！ {t}！', '咬住了！不会松口！{t}！', "Got you! I won't let go! {t}!"),
        'young': ('くわえちゃ！ はなしゃない！ {t}！', '咬住惹！不松口！{t}！', "Got chu! Won't let go! {t}!")},
    'braid_rescue_offer': {
        'adult': ('{t}！ いまいく！ 観楼剣に、つかまって！', '{t}！这就来！抓住觀樓劍！', '{t}! Coming! Grab the Kanroken!'),
        'young': ('{t}！ いまいく！ 観楼剣に ちゅかまっちぇ！', '{t}！这就来！抓住觀樓劍！', '{t}! Comin! Gwab the Kanroken!')},
    'braid_rescue_safe': {
        'adult': ('もう、ふねのうえ。{t}。ゆっくりいきして。', '已经到船上了。{t}。慢慢喘口气。', "You're on the boat now. {t}. Breathe slowly."),
        'young': ('もう ふにぇのうえ！ {t}！ ゆっくち いきちて！', '到船上惹！{t}！慢慢喘气！', 'On da boat now! {t}! Bweathe!')},
    'busy': {
        'adult': ('{t}？ いま、いそがしい。', '{t}？现在很忙。', '{t}? Busy right now.'),
        'young': ('{t}？ いま、いしょがちい！', '{t}？窝在忙！', '{t}? Busy!')},
    'caregiver': {
        'adult': ('{t}♪ えへへ。', '{t}♪ 嘿嘿。', '{t}! Hehe.'),
        'young': ('{t}♪ えへへぇ。', '{t}♪ 嘿嘿嘿。', '{t}! Hehehe.')},
    'chew': {
        'adult': ('むーしゃ、むーしゃ……{t}。', '姆咻，姆咻……{t}。', 'Munch, munch... {t}.'),
        'young': ('むーちゃ、むーちゃ……{t}。', '姆咻，姆咻……{t}。', 'Munch, munch... {t}.')},
    'cold': {
        'adult': ('{t}……さむい……でも、まけない……', '{t}……好冷……但是不认输……', "{t}... cold... but I won't lose..."),
        'young': ('{t}……しゃむい……しゃむいよぉ……', '{t}……冷冷……好冷……', '{t}... cold... so cold...')},
    'courtship': {
        'adult': ('{t}♪ ……すき。', '{t}♪……喜欢。', '{t}! ...I like you.'),
        'young': ('{t}♪ ……しゅき。', '{t}♪……喜欢。', '{t}! ...Wike you.')},
    'courtship_held': {
        'adult': ('すーりすーり、したい……でも、がまん。{t}。', '好想蹭蹭……但还要忍着。{t}。', 'I want to nuzzle... but I wait. {t}.'),
        'young': ('しゅーりしゅーり ちたい……がまん。{t}。', '想蹭蹭……要忍着。{t}。', 'Wanna nuzzle... must wait. {t}.')},
    'date_fond': {
        'adult': ('あのこ、つよそう……また、あいたい。{t}。', '那孩子看起来很强……还想再见。{t}。', 'That one looks strong... I want to meet again. {t}.'),
        'young': ('あのこ、いいこ！ また あいちゃい！ {t}！', '那孩子是好孩子！还想再见！{t}！', 'Dat one is nice! Wanna meet again! {t}!')},
    'date_meet': {
        'adult': ('{t}！ は、はじめまして……よろしく……', '{t}！初、初次见面……请多关照……', '{t}! N-nice to meet you... hello...'),
        'young': ('{t}！ は、はじめまちて……はじゅかちい……', '{t}！初、初次见面……好害羞……', '{t}! H-hewwo... so shy...')},
    'elder_kind': {
        'adult': ('ごはんをくれる、にんげんさんもいる。{t}。', '也有会给饭的人类先生。{t}。', 'Some humans give food, too. {t}.'),
        'young': ('ごはん くれりゅ にんげんしゃんも いりゅ。{t}。', '也有给饭饭的人类先生。{t}。', 'Some humans give food. {t}.')},
    'elder_warn': {
        'adult': ('にんげんには、ちかよるな。{t}。きをつけて。', '别靠近人类。{t}。要小心。', 'Stay away from humans. {t}. Be careful.'),
        'young': ('にんげんしゃんに ちかよっちゃ らめ！ {t}！', '不能靠近人类先生！{t}！', 'No going near humans! {t}!')},
    'exam_answer': {
        'adult': ('{t}！ はい！ わかる！ ……たぶん！', '{t}！是！明白！……大概！', '{t}! Yes! I know! ...Probably!'),
        'young': ('{t}！ はいっ！ わかりゅ！', '{t}！好的！窝知道！', '{t}! Yes! I know!')},
    'exam_fail': {
        'adult': ('どうして……どぼじてぇ……{t}……', '为什么……为什么会这样……{t}……', 'Why... whyyy... {t}...'),
        'young': ('どぼちて……どぼちてぇ……{t}……', '为什么……为什么嘛……{t}……', 'Why... whyyy... {t}...')},
    'exam_hide_fail': {
        'adult': ('{t}！ ……ここには、だれもいない！', '{t}！……这里谁也没有！', "{t}! ...Nobody's here!"),
        'young': ('{t}！ だぁれも いにゃい！', '{t}！谁也没有！', '{t}! Nobody hewe!')},
    'exam_nervous': {
        'adult': ('{t}……バッジ、とれるかな……どきどきする。', '{t}……徽章能拿到吗……心扑通扑通的。', '{t}... will I get the badge... heart pounding.'),
        'young': ('{t}……こわいけど、がんばりゅ！', '{t}……虽然怕怕，窝会加油！', "{t}... scawy, but I'll twy!")},
    'exam_pass': {
        'adult': ('{t}！ やった！ バッジ！ しゅぎょうのせいか！', '{t}！太好了！徽章！修行的成果！', '{t}! Yes! A badge! My training paid off!'),
        'young': ('{t}！ やっちゃ！ ばっじ！ しあわちぇー！', '{t}！太好惹！徽章！好幸福——！', '{t}! Yay! Badge! So happy!')},
    'exam_promise_fail': {
        'adult': ('ずるい！ わたしのぶんがない！ {t}！', '狡猾！没有妖梦的份！{t}！', "Not fair! There's none for me! {t}!"),
        'young': ('じゅるい！ わたちのばん！ {t}！ ぷんぷん！', '狡猾！是窝的份！{t}！气鼓鼓！', 'No fair! My tuwn! {t}! Hmph!')},
    'exam_promise_wait': {
        'adult': ('がまん……がまんも、しゅぎょう。{t}。', '忍耐……忍耐也是修行。{t}。', 'Patience... patience is training too. {t}.'),
        'young': ('がまん、がまん……{t}……まだかにゃ……', '忍耐，忍耐……{t}……还没好吗……', 'Wait, wait... {t}... not yet...?')},
    'exam_report': {
        'adult': ('{t}！ にんげんさん、あそこに、のらがいる！', '{t}！人类先生，那边有野良！', "{t}! Human, there's a stray over there!"),
        'young': ('{t}！ にんげんしゃん、のらがいりゅ！', '{t}！人类先生，有野良！', '{t}! Human, a stway!')},
    'exam_sorry': {
        'adult': ('ごめんなさい……まちがえた。{t}……', '对不起……弄错了。{t}……', 'Sorry... I got it wrong. {t}...'),
        'young': ('ごめんにゃしゃい……まちがえちゃった……{t}……', '对不起……窝弄错惹……{t}……', 'Sowwy... got it wong... {t}...')},
    'exam_unsure': {
        'adult': ('えーと……あれ……？ {t}……わすれた……', '嗯……咦……？{t}……忘了……', 'Um... huh...? {t}... I forgot...'),
        'young': ('えーと……あれぇ……？ {t}……わかんにゃい……', '嗯……咦……？{t}……不知道……', 'Um... huh...? {t}... dunno...')},
    'exercise': {
        'adult': ('{t}！ すぶり、いっかい、にかい、さんかい……いっぱい！', '{t}！挥剑，一下、两下、三下……好多好多！', '{t}! Swings: one, two, three... lots!'),
        'young': ('{t}！ ゆっくち がんばりゅ！', '{t}！要油库气地努腻！', "{t}! Gonna twy hawd!")},
    'expecting': {
        'adult': ('{t}♪ あかちゃん、まもる！', '{t}♪ 妖梦会保护宝宝！', "{t}! I'll protect the baby!"),
        'young': ('ゆわぁぁ♪ {t}♪', '油哇啊啊♪{t}♪', 'Yuwaaa! {t}!')},
    'family_alarm_answer': {
        'adult': ('{t}！ いまいく！', '{t}！这就过来！', '{t}! Coming now!'),
        'young': ('{t}！ いま いくぅ！', '{t}！窝这就来！', '{t}! Comin!')},
    'family_alarm_call': {
        'adult': ('{t}！ ちびたち、こっち！ うしろにかくれて！', '{t}！孩子们，到这边！躲到妖梦后面！', '{t}! Little ones, here! Hide behind me!'),
        'young': ('{t}！ こっち、こっち！ はやく！', '{t}！这边，这边！快点！', '{t}! Hewe, hewe! Huwwy!')},
    'family_alarm_calm': {
        'adult': ('ふう……もう、だいじょうぶ。{t}。', '呼……已经安全了。{t}。', "Phew... it's safe now. {t}."),
        'young': ('ふう……もう だいじょぶ。{t}。', '呼……没事惹。{t}。', 'Phew... safe now. {t}.')},
    'family_alarm_flee': {
        'adult': ('ここは、あぶない！ はなれる！ {t}！', '这里危险！快离开！{t}！', "It's dangerous here! Move away! {t}!"),
        'young': ('ここ、あぶにゃい！ にげりゅ！ {t}！', '这里危险！快跑！{t}！', 'Dangewous! Wun! {t}!')},
    'family_died': {
        'adult': ('{t}……まもれなかった……ゆっくりぃ……', '{t}……没能保护好……油库里——……', "{t}... couldn't protect you... yukkuriii..."),
        'young': ('{t}……おきちぇ……ゆっくちぃ……', '{t}……醒醒……油库气——……', '{t}... wake up... yukkuchiii...')},
    'fed': {
        'adult': ('{t}！ しあわせー！', '{t}！好幸福——！', '{t}! So happy!'),
        'young': ('{t}！ しあわちぇー！', '{t}！好幸胡——！', '{t}! So happeee!')},
    'food_found': {
        'adult': ('{t}！ ごはんさん、みつけた！', '{t}！找到饭先生了！', '{t}! Found Mr. Food!'),
        'young': ('{t}！ ごはんしゃん！', '{t}！饭鲜生！', '{t}! Mistew Food!')},
    'gentle': {
        'adult': ('{t}♪ やさしい……しあわせー。', '{t}♪ 好温柔……好幸福——。', '{t}! So gentle... so happy.'),
        'young': ('{t}♪ しあわちぇー！', '{t}♪ 好幸胡——！', '{t}! Happeee!')},
    'grown': {
        'adult': ('{t}！ おおきくなった！ もっと、つよくなる！', '{t}！长大了！还要变得更强！', "{t}! I'm bigger! I'll get even stronger!"),
        'young': ('{t}！ おっきくにゃった！', '{t}！长大惹！', '{t}! I gwew!')},
    'harness_on': {
        'adult': ('{t}！ ぴったり！ おさんぽ、いける！', '{t}！刚刚好！能去散步了！', '{t}! Perfect fit! Walk time!'),
        'young': ('{t}！ ぴったり！ おしゃんぽ いけりゅ！', '{t}！刚刚好！能去散步惹！', '{t}! Fits! Walkies!')},
    'harness_tug': {
        'adult': ('いまは、あるきたくない……{t}。まって。', '现在不想走……{t}。等一下。', "Don't want to walk now... {t}. Wait."),
        'young': ('いまは、あるきたくにゃい……{t}。まっちぇ。', '现在不想走……{t}。等等嘛。', "Don't wanna walk... {t}. Wait.")},
    'hat_drifting': {
        'adult': ('{t}！ おかざりが、ながれていく！', '{t}！头饰漂走了！', '{t}! My ribbon is drifting away!'),
        'young': ('{t}！ おかじゃりが、にゃがれてく！', '{t}！饰饰漂走惹！', '{t}! Wibbon dwifting away!')},
    'hat_out_of_reach': {
        'adult': ('もう、とどかない……{t}……', '已经够不到了……{t}……', "Can't reach it anymore... {t}..."),
        'young': ('もう、とどかにゃい……{t}……', '够不到惹……{t}……', "Can't weach... {t}...")},
    'hat_recovered': {
        'adult': ('{t}！ おかざり、つかまえた！', '{t}！抓回头饰了！', '{t}! Got my ribbon back!'),
        'young': ('{t}！ おかじゃり、ちゅかまえた！', '{t}！抓回饰饰惹！', '{t}! Got my wibbon!')},
    'hatched': {
        'adult': ('{t}！ ゆっくりしていってね！', '{t}！油库里洗跌一跌捏！', '{t}! Take it easy!'),
        'young': ('{t}……？ ゆっくち しちぇいっちぇにぇ！', '{t}……？油库气洗切一切捏！', '{t}...? Take it eashy!')},
    'heard': {
        'adult': ('{t}？ なにか、きこえた。', '{t}？听到什么了。', '{t}? I heard something.'),
        'young': ('{t}？ にゃにか、きこえた？', '{t}？听到啥惹？', '{t}? Heawd something?')},
    'hello': {
        'adult': ('ゆっくりしていってね！ {t}！', '油库里洗跌一跌捏！{t}！', 'Take it easy! {t}!'),
        'young': ('ゆっくち しちぇいっちぇにぇ！ {t}！', '油库气洗切一切捏！{t}！', 'Take it eashy! {t}!')},
    'house_arrival': {
        'adult': ('{t}！ おうちについた。ゆっくりする！', '{t}！到家了。要油库里！', '{t}! Home. Time to relax!'),
        'young': ('{t}！ ゆっきゅり しゅりゅ！', '{t}！要油库气！', '{t}! Wewax time!')},
    'house_deposit': {
        'adult': ('{t}！ ごはん、おいた！ ゆっくりする！', '{t}！饭饭放好了！要油库里！', '{t}! Food stored! Time to relax!'),
        'young': ('{t}！ ごはん、おいちゃ！', '{t}！饭饭放好惹！', '{t}! Food put away!')},
    'house_hatched': {
        'adult': ('{t}！ でてきた！ ここが、おうち。', '{t}！出来了！这里就是小窝。', "{t}! You're out! This is home."),
        'young': ('{t}！ でてきちゃ！ ここ、おうち！', '{t}！出来惹！这里是窝窝！', '{t}! Out! Dis is home!')},
    'house_listen': {
        'adult': ('ちいさいこが、うごいてる。{t}。', '小不点在里面动了。{t}。', 'The little one is moving. {t}.'),
        'young': ('ちっちゃいこ、うごいてりゅ！ {t}！', '小不点在动惹！{t}！', 'Wittle one is moving! {t}!')},
    'house_peek': {
        'adult': ('{t}？ なかに、なにかいる？', '{t}？里面有什么吗？', '{t}? Something inside?'),
        'young': ('{t}？ にゃかに、にゃにかいりゅ？', '{t}？里面有啥？', '{t}? Somefin inside?')},
    'house_pickup': {
        'adult': ('{t}！ よいしょ、よいしょ。', '{t}！嘿咻，嘿咻。', '{t}! Heave, heave.'),
        'young': ('{t}！ よいちょ、よいちょ。', '{t}！嘿咻，嘿咻。', '{t}! Heave-ho.')},
    'house_search_home': {
        'adult': ('{t}。あんぜんなすきまを、さがす。', '{t}。要找个安全的缝隙。', "{t}. I'll find a safe gap."),
        'young': ('{t}。おうちしゃん、みちゅける！', '{t}。要找到窝窝！', '{t}. Gonna find a home!')},
    'house_startled': {
        'adult': ('{t}！ びっくりした！ ……でも、にげない！', '{t}！吓了一跳！……但是不逃！', "{t}! That startled me! ...But I won't run!"),
        'young': ('{t}！ こわい！ かくれりゅ！', '{t}！怕怕！要躲起来！', '{t}! Scawy! Hiding!')},
    'hungry': {
        'adult': ('{t}……おなかすいた……', '{t}……肚子饿了……', "{t}... I'm hungry..."),
        'young': ('{t}……おなかしゅいた……', '{t}……肚肚饿惹……', '{t}... hungwy...')},
    'idle': {
        'adult': ('{t}～。ゆっくりする。', '{t}～。油库里一下。', '{t}~ Taking it easy.'),
        'young': ('{t}～♪ ゆっきゅり！', '{t}～♪ 油库气！', '{t}~ Easy!')},
    'introduced': {
        'adult': ('{t}！ ゆっくりしていってね！ よろしく！', '{t}！油库里洗跌一跌捏！请多关照！', '{t}! Take it easy! Nice to meet you!'),
        'young': ('{t}！ よろちく！ ゆっくちちていっちぇにぇ！', '{t}！请多关照！油库气洗切一切捏！', '{t}! Hewwo! Take it eashy!')},
    'known_peer': {
        'adult': ('{t}！ また、あえた！ ゆっくりしていってね！', '{t}！又见面了！油库里洗跌一跌捏！', '{t}! Met again! Take it easy!'),
        'young': ('{t}！ まちゃ あえちゃ！', '{t}！又见面惹！', '{t}! Again! Hi!')},
    'laid_eggs': {
        'adult': ('{t}♪ ちびちゃんたち……ぜったい、まもる。', '{t}♪ 小宝宝们……一定会保护好。', "{t}! My little ones... I'll protect you, always."),
        'young': ('{t}♪ ゆわぁぁぁ♪', '{t}♪ 油哇啊啊♪', '{t}! Yuwaaa!')},
    'leisure_done': {
        'adult': ('{t}！ たのしかった！ しあわせー！', '{t}！好开心！好幸福——！', '{t}! That was fun! So happy!'),
        'young': ('{t}！ たのちかった！', '{t}！好开心惹！', '{t}! Fun!')},
    'lesson_learned': {
        'adult': ('{t}！ がんばった！ ほめて！', '{t}！很努力了！要夸奖！', '{t}! I worked hard! Praise me!'),
        'young': ('{t}！ がんばっちゃ！ ほめちぇ！', '{t}！努腻惹！夸夸窝！', '{t}! Twied hawd! Pwaise me!')},
    'lesson_praised': {
        'adult': ('{t}！ ほめられた！ もっと、しゅぎょうする！', '{t}！被夸奖了！还要更加修行！', "{t}! Praised! I'll train even more!"),
        'young': ('{t}！ えへへ、ほめられちゃ！', '{t}！嘿嘿，被夸惹！', '{t}! Hehe, pwaised!')},
    'lesson_progress': {
        'adult': ('{t}。ゆっくり、がんばる。', '{t}。要油库里地努力。', '{t}. Slow and steady.'),
        'young': ('{t}。ゆっくち がんばりゅ。', '{t}。油库气地努腻。', '{t}. Twying.')},
    'lesson_refused': {
        'adult': ('ぷくーっ！ やらない！ {t}！', '噗咕——！不做！{t}！', "Puff! Won't do it! {t}!"),
        'young': ('ぷきゅーっ！ やらにゃい！ {t}！', '噗啾——！不要！{t}！', "Puff! Won't! {t}!")},
    'lesson_scolded': {
        'adult': ('ごめんなさい……ちゃんと、なおす。{t}……', '对不起……会好好改正。{t}……', "Sorry... I'll fix it properly. {t}..."),
        'young': ('ごめんにゃしゃい……なおしゅ……{t}……', '对不起……窝会改……{t}……', 'Sowwy... will fix... {t}...')},
    'lesson_start': {
        'adult': ('{t}！ しゅぎょう、はじめ！', '{t}！修行开始！', '{t}! Training, begin!'),
        'young': ('{t}！ がんばりゅ！', '{t}！要努腻！', "{t}! I'll twy!")},
    'lost_accessory': {
        'adult': ('{t}！ わたしのおかざり、かえせ！', '{t}！把妖梦的头饰还回来！', '{t}! Give my ribbon back!'),
        'young': ('{t}！ おかじゃり、かえちて！', '{t}！把饰饰还肥来！', '{t}! Give wibbon back!')},
    'lost_parent': {
        'adult': ('{t}……どこ……？ ゆっくりぃ……', '{t}……在哪里……？油库里——……', '{t}... where are you...? Yukkuriii...'),
        'young': ('{t}……おかーしゃん……ゆっくちぃ……', '{t}……妈妈……油库气——……', '{t}... mama... yukkuchiii...')},
    'mine': {
        'adult': ('ぷくーっ！ これは、わたしの！ {t}！', '噗咕——！这是妖梦的！{t}！', 'Puff! This is mine! {t}!'),
        'young': ('ぷきゅーっ！ わたちの！ {t}！', '噗啾——！窝的！{t}！', 'Puff! Mine! {t}!')},
    'mood_bad': {
        'adult': ('{t}……あんまり、ゆっくりできてない……', '{t}……不太能慢慢来……', "{t}... can't really relax..."),
        'young': ('{t}……あんまり ゆっくち できてにゃい……', '{t}……不太能慢慢来……', '{t}... not weawy wewaxed...')},
    'mood_good': {
        'adult': ('{t}！ きょうは、とってもゆっくりできてる！', '{t}！今天过得超悠闲！', '{t}! Really relaxed today!'),
        'young': ('{t}！ きょうは ゆっくち できてりゅ！', '{t}！今天好悠闲！', '{t}! Wewaxed today!')},
    'mood_ok': {
        'adult': ('{t}。ふつう。ぼちぼち。', '{t}。还行。慢慢悠悠。', '{t}. Okay. So-so.'),
        'young': ('{t}。ふちゅう。ぼちぼち。', '{t}。还行。慢慢悠悠。', '{t}. Okay.')},
    'need_toilet': {
        'adult': ('{t}。うんうん、する。', '{t}。要嗯嗯了。', '{t}. Need to poo.'),
        'young': ('{t}！ うんうん、しゅりゅ！', '{t}！要嗯嗯惹！', '{t}! Poo time!')},
    'newborn': {
        'adult': ('{t}！ ゆっくりしていってね！ まもってあげる！', '{t}！油库里洗跌一跌捏！妖梦会保护你！', "{t}! Take it easy! I'll protect you!"),
        'young': ('{t}！ ゆっくち しちぇいっちぇにぇ！', '{t}！油库气洗切一切捏！', '{t}! Take it eashy!')},
    'nuzzle': {
        'adult': ('すーりすーり……{t}♪', '擦擦脸，擦擦脸……{t}♪', 'Nuzzle, nuzzle... {t}!'),
        'young': ('しゅーりしゅーり……{t}♪', '擦擦腻，擦擦腻……{t}♪', 'Nuzzwe, nuzzwe... {t}!')},
    'nuzzle_done': {
        'adult': ('{t}♪ しあわせー！', '{t}♪ 好幸福——！', '{t}! So happy!'),
        'young': ('{t}♪ ぽかぽか、しあわちぇー！', '{t}♪ 暖乎乎，好幸胡——！', '{t}! Warm and happeee!')},
    'peer': {
        'adult': ('{t}！ だれ？ ……ゆっくりしていってね！', '{t}！是谁？……油库里洗跌一跌捏！', "{t}! Who's that? ...Take it easy!"),
        'young': ('{t}？ だぁれ？ ゆっくちちていっちぇにぇ！', '{t}？是谁？油库气洗切一切捏！', '{t}? Who? Take it eashy!')},
    'permit_glad': {
        'adult': ('あかちゃん、いいの？ {t}！ ぜったい、まもる！', '可以要宝宝了？{t}！一定会保护好！', "We can have babies? {t}! I'll protect them!"),
        'young': ('あかちゃん、いいにょ？ {t}！ しあわちぇー！', '可以要宝宝吗？{t}！好幸福——！', 'Babies? {t}! So happy!')},
    'pet_report_hurt': {
        'adult': ('{t}……にんげんさん、のらに、いじめられた……', '{t}……人类先生，被野良欺负了……', '{t}... human, a stray bullied me...'),
        'young': ('{t}……にんげんしゃん、のらに いじめられちゃ……', '{t}……人类先生，被野良欺负惹……', '{t}... human, a stway was mean...')},
    'pet_report_owner': {
        'adult': ('{t}！ にんげんさん、のらは、むしした！ えらい？', '{t}！人类先生，野良我没理会！厉害吧？', '{t}! Human, I ignored the stray! Good, right?'),
        'young': ('{t}！ にんげんしゃん、のらは むちちた！', '{t}！人类先生，窝没理野良！', '{t}! Human, I ignowed the stway!')},
    'plea_sad': {
        'adult': ('……そう。{t}。また、くる。', '……这样啊。{t}。下次再来。', "...I see. {t}. I'll come again."),
        'young': ('……しょう。{t}。また くりゅ。', '……这样啊。{t}。下次再来。', '...Okay. {t}. Come again.')},
    'puff': {
        'adult': ('ぷくーっ！ {t}！ かかってこい！', '噗咕——！{t}！放马过来！', 'Puff! {t}! Bring it on!'),
        'young': ('ぷきゅーっ！ {t}！', '噗啾——！{t}！', 'Puff! {t}!')},
    'request_sulk': {
        'adult': ('もう、しらない！ ぷんぷん！ {t}！', '不理你了！气鼓鼓！{t}！', 'Not talking to you! Hmph! {t}!'),
        'young': ('もう ちらにゃい！ ぷんぷん！ {t}！', '不理你惹！气鼓鼓！{t}！', 'Not talkin! Hmph! {t}!')},
    'request_thanks': {
        'adult': ('{t}！ ありがとう！ これで、ゆっくりできる！', '{t}！谢谢！这下能慢慢来了！', '{t}! Thanks! Now I can relax!'),
        'young': ('{t}！ ありがちょう！ ゆっくち できりゅ！', '{t}！谢谢！能慢慢来惹！', '{t}! Fank you! Can wewax!')},
    'returned_accessory': {
        'adult': ('{t}！ おかざり、おかえり！', '{t}！头饰，欢迎回来！', '{t}! Welcome back, ribbon!'),
        'young': ('{t}！ おかじゃり、おかえりっ！', '{t}！饰饰，欢迎肥来！', '{t}! Wibbon, welcome back!')},
    'self_rescue_climb': {
        'adult': ('んっ……よいしょ……{t}！ もういっかい！', '嗯……嘿咻……{t}！再使一次劲！', 'Nngh... heave... {t}! Once more!'),
        'young': ('んっ……よいちょ……{t}！ もういっかい！', '嗯……嘿咻……{t}！再来一次！', 'Nngh... {t}! Again!')},
    'self_rescue_grip': {
        'adult': ('{t}！ ふちを、くわえた！', '{t}！咬住边缘了！', '{t}! Got the edge in my mouth!'),
        'young': ('{t}！ ふち、くわえちゃ！', '{t}！咬住边边惹！', '{t}! Got the edge!')},
    'self_rescue_hat': {
        'adult': ('おかざり……ふりおとす……{t}……', '头饰……抖掉……{t}……', 'Ribbon... shake it off... {t}...'),
        'young': ('おかじゃり……ふりおとしゅ……{t}……', '饰饰……抖掉……{t}……', 'Wibbon... shake off... {t}...')},
    'self_rescue_safe': {
        'adult': ('はあ……のぼれた……{t}……', '哈啊……爬上来了……{t}……', 'Haah... made it up... {t}...'),
        'young': ('はあ……のぼれちゃ……{t}……', '哈啊……爬上来惹……{t}……', 'Haah... made it... {t}...')},
    'self_rescue_slip': {
        'adult': ('{t}！ すべる！', '{t}！要滑下去了！', "{t}! I'm slipping!"),
        'young': ('{t}！ しゅべりゅ！', '{t}！要滑下去惹！', '{t}! Swipping!')},
    'settlement_assign': {
        'adult': ('{t}！ うんうんどれい、おといれをそうじしろ！', '{t}！嗯嗯奴隶，去打扫厕所！', '{t}! Poo-slave, clean the toilet!'),
        'young': ('{t}！ うんうん、あっちに どけちぇ！', '{t}！介个嗯嗯，搬那边！', '{t}! Move the poo over dere!')},
    'settlement_drive': {
        'adult': ('{t}！ ここは、おまえのおうちじゃない！ でていけ！', '{t}！这里不是你的家！出去！', "{t}! This isn't your home! Get out!"),
        'young': ('{t}！ ここ、らめ！ あっちいっちぇ！', '{t}！介里不行！去那边！', '{t}! Not hewe! Go away!')},
    'settlement_hurt': {
        'adult': ('おかざりがないと……だめなの……？ {t}……', '没有头饰……就不行了吗……？{t}……', 'Without my ribbon... am I no good...? {t}...'),
        'young': ('おかじゃり、にゃい……ひとりは、いや……{t}……', '饰饰没惹……不要丢下窝……{t}……', "Wibbon gone... don't weave me... {t}...")},
    'settlement_shun': {
        'adult': ('{t}！ しらないゆっくり、ちかづくな！', '{t}！不认识的油库里，别靠近！', '{t}! Strange yukkuri, stay back!'),
        'young': ('{t}？ だれ？ こにゃいで！', '{t}？系谁？不要过来！', "{t}? Who? Don't come!")},
    'settlement_submit': {
        'adult': ('そうじする……だから、ここにいさせて。{t}……', '我会打扫……所以让我留在这里。{t}……', "I'll clean... so let me stay. {t}..."),
        'young': ('おそうじ しゅる！ ここに いちゃい……{t}……', '窝会扫扫！窝想待介里……{t}……', "I'll cwean! Wanna stay... {t}...")},
    'share': {
        'adult': ('{t}♪ はい、どうぞ。', '{t}♪ 给，请用。', '{t}! Here you go.'),
        'young': ('{t}♪ どーじょ！', '{t}♪ 给你！', '{t}! Hewe!')},
    'shop_appeal': {
        'adult': ('{t}！ かって！ つよいから、まもってあげる！', '{t}！买下妖梦吧！妖梦很强，会保护你！', "{t}! Buy me! I'm strong, I'll protect you!"),
        'young': ('{t}！ かっちぇ！ いいこだよ！', '{t}！买下窝！窝是乖孩子！', "{t}! Buy me! I'm good!")},
    'shop_bought': {
        'adult': ('{t}！ あたらしいおうち！ よろしく！', '{t}！新的家！请多关照！', '{t}! New home! Nice to meet you!'),
        'young': ('{t}！ あたらちい おうち！ よろちく！', '{t}！新的家！请多关照！', '{t}! New home! Hewwo!')},
    'shop_sold_proud': {
        'adult': ('{t}！ もっといいおうちに、いく！ さらば！', '{t}！要去更好的家！再会！', '{t}! Off to a better home! Farewell!'),
        'young': ('{t}！ もっといい おうちに いく！ ばいばい！', '{t}！要去更好的家！拜拜！', '{t}! Better home! Bye-bye!')},
    'shop_sold_sad': {
        'adult': ('どうして……わすれないで……{t}……', '为什么……不要忘了妖梦……{t}……', "Why... don't forget me... {t}..."),
        'young': ('どうちて……わすれにゃいでぇ……{t}……', '为什么……不要忘了窝……{t}……', "Why... don't fowget me... {t}...")},
    'shy': {
        'adult': ('{t}……はずかしい……', '{t}……好害羞……', '{t}... embarrassing...'),
        'young': ('{t}……はじゅかちい……', '{t}……羞羞……', '{t}... shy...')},
    'sleep': {
        'adult': ('すーや……すーや……{t}……', '呼——呀……呼——呀……{t}……', 'Zzz... zzz... {t}...'),
        'young': ('しゅーや……しゅーや……{t}……', '呼——呀……呼——呀……{t}……', 'Zzz... {t}...')},
    'snack_eat': {
        'adult': ('{t}！ あまあま、うまい！ しあわせー！', '{t}！甜甜好吃！好幸福——！', '{t}! Sweets, yum! So happy!'),
        'young': ('{t}！ あみゃあみゃ、おいちい！ しあわちぇー！', '{t}！甜甜好七！好幸胡——！', '{t}! Sweeties, yummy! Happeee!')},
    'snack_greedy': {
        'adult': ('{t}！ もっと！ あまあま、もっとちょうだい！', '{t}！还要！再给妖梦甜甜！', '{t}! More! More sweets!'),
        'young': ('{t}！ もっちょ！ あみゃあみゃ、もっちょ！', '{t}！还要！甜甜还要！', '{t}! Mowe! Mowe sweeties!')},
    'snack_refuse': {
        'adult': ('もう、おなかいっぱい……あとで。{t}。', '已经吃饱了……待会儿再吃。{t}。', "I'm full... later. {t}."),
        'young': ('ぽんぽん、いっぱい……あとで。{t}。', '肚肚满满……待会儿。{t}。', 'Tummy full... later. {t}.')},
    'spray_drowsy': {
        'adult': ('{t}……あまいにおい……ねむい……ちからが、ぬける……', '{t}……甜甜的香味……好困……没力气了……', '{t}... sweet smell... sleepy... so weak...'),
        'young': ('{t}……らむねのにおい……ねむねむ……', '{t}……汽水的味道……困困……', '{t}... soda smell... sweepy...')},
    'swaddle_on': {
        'adult': ('ぬくぬく……あったかい……{t}……', '暖乎乎的……好温暖……{t}……', 'Snug... so warm... {t}...'),
        'young': ('ぬくぬく……あっちゃかい……{t}……', '暖乎乎……好温暖……{t}……', 'Snug... wawm... {t}...')},
    'take_food': {
        'adult': ('{t}！ ゆっくり、たべる！', '{t}！要油库里地吃！', "{t}! I'll eat slowly!"),
        'young': ('{t}！ ゆっくち たべりゅ！', '{t}！要油库气地粗！', '{t}! Eat swowly!')},
    'toilet_done': {
        'adult': ('{t}！ すっきりー！', '{t}！好舒畅——！', '{t}! Ahh, relief!'),
        'young': ('{t}！ ちゅっきりー！', '{t}！好苏畅——！', '{t}! Wewief!')},
    'toy': {
        'adult': ('{t}！ わたしも、あそぶ！', '{t}！妖梦也要玩！', '{t}! I want to play too!'),
        'young': ('{t}！ わたちも、あしょぶ！', '{t}！窝也要丸！', '{t}! Me pway too!')},
    'treated': {
        'adult': ('{t}！ にんげんさん、ありがとう！', '{t}！人类先生，谢谢！', '{t}! Thank you, human!'),
        'young': ('{t}！ にんげんしゃん、ありがちょー！', '{t}！人类鲜生，屑屑！', '{t}! Fank you, human!')},
    'unrecognized': {
        'adult': ('{t}？ ……なんだった？', '{t}？……刚才怎么了？', '{t}? ...What was that?'),
        'young': ('{t}？ にゃんだった？', '{t}？刚才怎么惹？', '{t}? Whazzat?')},
    'wait_family': {
        'adult': ('{t}……まだかな……ずっと、まってる……', '{t}……还没来吗……一直在等……', "{t}... not yet... I'll keep waiting..."),
        'young': ('{t}……まだぁ……？ ゆっくちぃ……', '{t}……还没好吗……？油库气——……', '{t}... not yet...? Yukkuchiii...')},
    'wait_learned': {
        'adult': ('{t}！ ゆっくり、りかいした！', '{t}！油库里地明白了！', '{t}! I understand now!'),
        'young': ('{t}！ ゆっくち りきゃいちた！', '{t}！油库气地明白惹！', '{t}! I undewstand!')},
    'wait_turn': {
        'adult': ('ゆっくり……わかった……{t}。', '油库里……知道了……{t}。', 'Okay... I understand... {t}.'),
        'young': ('ゆっくち……わかっちゃ……{t}。', '油库气……知道惹……{t}。', 'Okay... got it... {t}.')},
    'walk_enjoy': {
        'adult': ('{t}！ おさんぽ、たのしい！ ゆっくりできる！', '{t}！散步好开心！好悠闲！', '{t}! Walks are fun! So relaxing!'),
        'young': ('{t}！ おしゃんぽ、たのちい！', '{t}！散步好开心！', '{t}! Walkies fun!')},
    'want_nuzzle': {
        'adult': ('{t}……すーりすーり、したい。', '{t}……想要擦擦脸。', '{t}... I want to nuzzle.'),
        'young': ('{t}……しゅーりしゅーり、ちたい。', '{t}……想要擦擦腻。', '{t}... wanna nuzzwe.')},
    'washed': {
        'adult': ('{t}！ すっきりー！ きれいになった！', '{t}！好舒畅——！变干净了！', '{t}! Refreshed! All clean!'),
        'young': ('{t}！ ちゅっきりー！ きれーきれー！', '{t}！好苏畅——！干净净！', '{t}! Fwesh! All cwean!')},
    'water_help': {
        'adult': ('ゆぶっ！ {t}！ たすけて！', '咕呜！{t}！救命！', 'Glub! {t}! Help!'),
        'young': ('ゆぶっ！ {t}！ たしゅけて！', '咕呜！{t}！救命呀！', 'Glub! {t}! Hewp!')},
    'wild_plea_keep': {
        'adult': ('{t}！ かいゆっくりにして！ いいこにする！', '{t}！请收我当宠物！会乖乖的！', "{t}! Keep me as a pet! I'll be good!"),
        'young': ('{t}！ かいゆっくりに ちて！ いいこに しゅる！', '{t}！收窝当宠物！窝会乖乖的！', "{t}! Keep me! I'll be good!")},
    'wild_role_beg': {
        'adult': ('{t}！ あまあま、ちょうだい！ ここ、あけて！', '{t}！给我甜甜！把这里打开！', '{t}! Sweets, please! Open up!'),
        'young': ('{t}！ あまあま、ちょうらい！ あけちぇ！', '{t}！给窝甜甜！快打开！', '{t}! Sweeties! Open up!')},
    'wild_role_provoke': {
        'adult': ('{t}！ バッジなんか、いらない！ しょうぶしろ！', '{t}！什么徽章，不需要！来决斗！', '{t}! Who needs a badge! Fight me!'),
        'young': ('{t}！ ばっじ、いらにゃい！ しょうぶ！', '{t}！徽章才不要！来比试！', "{t}! Don't need badge! Fight!")},
    'wounded': {
        'adult': ('{t}！ いたい……！ でも、まだたたかえる！', '{t}！好痛……！但还能打！', '{t}! Ow...! But I can still fight!'),
        'young': ('ゆんやぁぁ！ {t}！ いちゃいよぉぉ！', '油嗯呀啊啊！{t}！好痛痛——！', 'Waaah! {t}! It huwts!')},
}

# Counting past three (Patchouli's 4..9 buckets): Myon cannot count that far and collapses to "lots" after three.
_COUNT = {  # base -> (adult jp noun, young jp noun, zh noun, en noun, adult jp numbers, young jp numbers)
    'count_food': ('ごはんさんは', 'ごはんしゃん', '食物先生有', 'Food', 'ひとつ、ふたつ、みっつ', 'ひとちゅ、ふたちゅ、みっちゅ'),
    'count_stock': ('たくわえは', 'たくわえ', '存粮有', 'Stockpile', 'ひとつ、ふたつ、みっつ', 'ひとちゅ、ふたちゅ、みっちゅ'),
    'count_children': ('こどもは', 'ちびちゃん', '孩子有', 'Children', 'ひとり、ふたり、さんにん', 'ひとり、ふたり、しゃんにん'),
    'count_peers': ('なかまは', 'なかま', '伙伴有', 'Friends', 'ひとり、ふたり、さんにん', 'ひとり、ふたり、しゃんにん'),
}
for _base, (_na, _ny, _zh, _en, _a, _y) in _COUNT.items():
    for _n in range(4, 10):
        LINES[f'{_base}_{_n}'] = {
            'adult': (f'{{t}}！ {_na}……{_a}……いっぱい！', f'{{t}}！{_zh}……1个、2个、3个……好多好多！', f'{{t}}! {_en}: one, two, three... lots!'),
            'young': (f'{{t}}！ {_ny}……{_y}……いっぱい！', f'{{t}}！{_zh}……1个、2个、3个……好多好多！', f'{{t}}! {_en}: one, two, thwee... lots!')}

# Myon-only events. Baby frames use {c}, the quirk's babble cry.
NEW = {
    'kanroken_draw': {
        'adult': ('{t}！ 観楼剣、ぬいた！ こっちにくるな！', '{t}！觀樓劍出鞘！不许过来！', "{t}! Kanroken, drawn! Don't come closer!"),
        'juvenile': ('{t}！ 観楼剣で、みんなをまもる！ くるな！', '{t}！妖梦用觀樓劍保护大家！别过来！', "{t}! I'll guard everyone with Kanroken! Back off!"),
        'young': ('{t}！ 観楼剣！ こっち、くりゅな！', '{t}！觀樓劍！不许过来！', '{t}! Kanroken! Stay back!'),
        'baby': ('{c}！ ぼーぼー、ぶんぶん！', '{c}！棒棒，呼呼！', '{c}! Stick, swish swish!')},
    'kanroken_found': {
        'adult': ('{t}！ いいぼう、みつけた！ きょうから観楼剣！', '{t}！找到好棍子了！从今天起就是觀樓劍！', "{t}! A good stick! From today, it's Kanroken!"),
        'juvenile': ('{t}！ このぼう、つよそう！ 観楼剣にする！', '{t}！这根棍子看起来好强！就叫觀樓劍！', "{t}! This stick looks strong! It's my Kanroken!"),
        'young': ('{t}！ いいぼう！ 観楼剣に しゅる！', '{t}！好棍棍！要叫它觀樓劍！', "{t}! Good stick! It's Kanroken now!"),
        'baby': ('{c}♪ ぼー！ ぼー！', '{c}♪ 棒棒！棒棒！', '{c}! Stick! Stick!')},
    'kanroken_lost': {
        'adult': ('観楼剣が……ない……{t}……でも、なかない！', '觀樓劍……不见了……{t}……但是不哭！', "Kanroken... is gone... {t}... but I won't cry!"),
        'juvenile': ('観楼剣、おれちゃった……{t}……またさがす！', '觀樓劍断掉了……{t}……再去找一根！', "Kanroken broke... {t}... I'll find another!"),
        'young': ('観楼剣……にゃい……{t}……にゃかにゃい……', '觀樓劍……没惹……{t}……窝不哭……', 'Kanroken... gone... {t}... not cwying...'),
        'baby': ('ぼー……にゃい……{c}ーっ！', '棒棒……没有了……{c}——！', 'Stick... gone... {c}!')},
    'forage_proud': {
        'adult': ('{t}！ ごはん、とってきた！ ひとつ、ふたつ……いっぱい！', '{t}！饭饭采回来了！一个、两个……好多好多！', '{t}! Brought food! One, two... lots!'),
        'juvenile': ('{t}！ いちばんはやく、いっぱいあつめた！', '{t}！妖梦最快，采了好多好多！', '{t}! Fastest! I gathered lots and lots!'),
        'young': ('{t}！ ごはん、いっぱい とってきちゃ！', '{t}！采了好多好多饭饭惹！', '{t}! Got lots of food!'),
        'baby': ('{c}♪ まんま、いっぱい！', '{c}♪ 饭饭，好多好多！', '{c}! Num-num, lots!')},
}

# Kotatsu Marisa (plain marisa, no quirk).
KOTATSU = {
    'kotatsu_hot': {
        'adult': ('あついのぜ……すずしいところで ねるのぜ……', '好热贼……要在凉快的地方睡觉贼……', "So hot... I'm gonna sleep somewhere cool..."),
        'young': ('あちゅいのじぇ……しゅずしいとこで ねりゅのじぇ……', '好热惹杰……要去凉快的地方睡觉惹杰……', 'So hot... gonna nap somewhere coow...')},
    'kotatsu_warm': {
        'adult': ('ぽかぽかなのぜ！ まりさのこたつで あったまるのぜ！', '暖洋洋贼！来魔理沙的被炉里暖和暖和贼！', "Nice and toasty! Come warm up in Marisa's kotatsu!"),
        'young': ('ぽかぽかなのじぇ！ まりちゃのこたちゅ、はいりゅのじぇ！', '暖乎乎惹杰！快来窝的被炉里惹杰！', 'So toasty! Come in my kotatsu!')},
}

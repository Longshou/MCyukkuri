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
        'young': ('{t}！ こ、こわくにゃい……ほんとだもん！', '{t}！不、不怕……真的不怕！', '{t}! N-not scawed... really!')},
    'ask_food': {
        'adult': ('{t}！ おなかすいた！ ごはん、ちょうだい！', '{t}！彭彭饿了！给一点饭！', '{t}! Hungry! Some food, please!'),
        'young': ('{t}！ ぽんぽんしゃん、ぺこぺこ！ ごはん、ちょうらい！',
                  '{t}！彭彭先森饿惹！给一点饭嘛！', '{t}! Mr. Tummy is hungwy! Food, pwease!')},
    'ask_play': {
        'adult': ('ひまだ……{t}！ いっしょに、ちゃんばらしよう！', '好无聊……{t}！一起来比剑吧！', "So bored... {t}! Let's play swords!"),
        'young': ('ひまぁ……{t}！ いっちょに ちゃんばら ちよ！', '好无聊……{t}！一起玩比剑吧！', 'So bowed... {t}! Pway swords!')},
    'ask_snack': {
        'adult': ('{t}！ あまあま、ほしい！ ちょうだい！', '{t}！想要甜甜！给我嘛！', '{t}! Want sweets! Please!'),
        'young': ('{t}！ あみゃあみゃ、ほちい！ ちょうらい！', '{t}！想要甜甜！给窝嘛！', '{t}! Want sweeties! Pwease!')},
    'ask_walk': {
        'adult': ("{t}！ おそと！ おさんぽ、おさんぽ！", "{t}！出去！散步、散步！", "{t}! Outside! Walk, walk!"),
        'young': ('{t}！ おしゃんぽ、いく！', '{t}！窝要去散步！', '{t}! Walkies!')},
    'awake': {
        'adult': ('{t}！ ゆっくりおはよう！ きょうもがんばる！', '{t}！油库里早上好！今天也要加油！', '{t}! Good morning! Working hard today too!'),
        'young': ('{t}！ ゆっくち おはよー！', '{t}！油库气，早上好！', '{t}! Mownin!')},
    'badge_admire': {
        'adult': ('{t}！ すごい！ おしえて、おしえて！', '{t}！好厉害！教教妖梦，教教妖梦！', '{t}! Amazing! Teach me, teach me!'),
        'young': ('{t}！ しゅごい！ おしえちぇ！', '{t}！好厉害！教教窝！', '{t}! Amazin! Teach me!')},
    'badge_ask': {
        'adult': ('バッジ、ぴかぴか！ さわっても いい？ {t}？', '徽章好亮！能摸摸吗？{t}？', 'Your badge is shiny! Can I touch it? {t}?'),
        'young': ('ばっじ、ぴかぴか！ しゃわっても いい？ {t}？', '徽章好亮！窝能摸摸吗？{t}？', 'Your badge is shiny! Can I touch it? {t}?')},
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
        'adult': ("{t}。まだ れんしゅう する。", "{t}。妖梦还要练练。", "{t}. I still need practice."),
        'young': ('{t}。まだ、しゅぎょうちゅう！', '{t}。窝还在修行！', '{t}. Still twaining!')},
    'badge_mock': {
        'adult': ('かいゆのくせに、えらそう！ {t}！', '一个宠物还摆架子！{t}！', 'Just a pet, acting so big! {t}!'),
        'young': ('かいゆのくちぇに、えらしょう！ {t}！', '宠物还摆架子！{t}！', 'Jus a pet, acting big! {t}!')},
    'badge_pinned': {
        'adult': ("{t}！ バッジ、もらった！ ぴかぴか！", "{t}！徽章拿到啦！亮晶晶！", "{t}! Got a badge! Shiny!"),
        'young': ('{t}！ ばっじ、もらっちゃ！ ぴかぴか！', '{t}！拿到徽章惹！亮晶晶！', '{t}! Got a badge! Shiny!')},
    'badge_pity': {
        'adult': ('バッジ、なくなった……？ {t}、だいじょうぶ？', '徽章没了……？{t}，没事吧？', 'Your badge is gone...? {t}, you okay?'),
        'young': ('ばっじ、にゃくなった……？ {t}、だいじょぶ？', '徽章没惹……？{t}，没事吧？', 'Badge gone...? {t}, okay?')},
    'badge_proud': {
        'adult': ("{t}！ みて、バッジ！ つよそうだろ！", "{t}！看，徽章！威风吧！", "{t}! Look at my badge! Looks strong, right?"),
        'young': ('{t}！ みちぇ、ばっじ！ しゅごい？', '{t}！看，徽章！厉害吧？', '{t}! Look, badge! Cool?')},
    'badge_pull_back': {
        'adult': ('バッジを つけた ゆっくりに ちかづくな！ {t}！', '离戴徽章的油远一点！{t}！', 'Keep away from the yukkuri with the badge! {t}!'),
        'young': ('ばっじを つけた ゆっくりに ちかづいちゃ らめ！ {t}！', '噗要靠近戴徽章的油！{t}！', "Don't go near the yukkuri with the badge! {t}!")},
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
        'adult': ('{t}。バッジ、みていいよ。にんげんさんが つけてくれた。', '{t}。徽章给你看。人类先生给我戴的。', '{t}. You can see my badge. Mr. Human pinned it on me.'),
        'young': ('{t}。ばっじ、みちぇいいよ。にんげんしゃんが つけちぇくれた！', '{t}。给你看徽章！人类鲜笋给窝戴的！', '{t}. You can see my badge! Mr. Human put it on me!')},
    'badge_taken': {
        'adult': ("バッジが……ない。{t}……かえして……", "徽章……没了。{t}……还给妖梦……", "My badge... gone. {t}... give it back..."),
        'young': ("ばっじ……にゃい。{t}……かえちて……", "徽章……没惹。{t}……还给窝……", "My badge... gone. {t}... give it back...")},
    'badge_thanks': {
        'adult': ('ありがとう……{t}。やっと、ゆっくりできる。', '谢谢……{t}。总算能油库里了。', 'Thank you... {t}. I can finally relax.'),
        'young': ('ありがちょう……{t}。ゆっくち できりゅ。', '谢谢……{t}。能油库气了。', 'Fank you... {t}. Can wewax now.')},
    'basket_enter': {
        'adult': ('{t}！ わかった、はいる！', '{t}！知道了，进去！', '{t}! Okay, going in!'),
        'young': ('{t}！ はいりゅ！ ゆっくち はいりゅ！', '{t}！进去！慢慢进去！', '{t}! Goin in! Swowly!')},
    'basket_exit': {
        'adult': ("ゆっ、ここ、どこ？ {t}……", "油，这里是哪儿？{t}……", "Yu, where am I? {t}..."),
        'young': ("きょきょ、どこ……？ {t}？", "介里是哪儿……？{t}？", "Where am I...? {t}?")},
    'basket_refuse': {
        'adult': ('{t}！ かごさん！ いや、いや！', '{t}！篮子先生！不要、不要！', '{t}! Mr. Basket! No, no!'),
        'young': ('{t}！ かごしゃん！ やー、やー！', '{t}！篮子鲜笋！噗要、噗要！', '{t}! Mr. Basket! No, no!')},
    'birth': {
        'adult': ('{t}！ うまれる！', '{t}！要生了！', "{t}! It's coming!"),
        'young': ('{t}！ うみゃれりゅ！', '{t}！要生惹！', "{t}! It's comin!")},
    'boat_rock_startle': {
        'adult': ('{t}！ ふねが、ゆれる！', '{t}！船在摇！', '{t}! The boat is rocking!'),
        'young': ('{t}！ ふにぇが、ゆれりゅ！', '{t}！船在摇摇！', '{t}! Boat is wockin!')},
    'braid_rescue_mouth_grip': {
        'adult': ('くわえた！ はなさない！ {t}！', '咬住了！不会松口！{t}！', "Got you! I won't let go! {t}!"),
        'young': ('くわえちゃ！ はなしゃない！ {t}！', '咬住惹！不松口！{t}！', "Got chu! Won't let go! {t}!")},
    'braid_rescue_offer': {
        'adult': ("{t}！ いま いく！ まってて！", "{t}！这就来！等着妖梦！", "{t}! Coming! Hang on!"),
        'young': ("{t}！ いま いく！ まっちぇて！", "{t}！这就来！等等窝！", "{t}! Coming! Hang on!")},
    'braid_rescue_safe': {
        'adult': ("ふぅ……もう だいじょうぶ。{t}……", "呼……没事啦。{t}……", "Phew... you're safe. {t}..."),
        'young': ("ふぅ……だいじょぶ。{t}……", "呼……没事惹。{t}……", "Phew... you're safe. {t}...")},
    'busy': {
        'adult': ('{t}？ いま、いそがしい。', '{t}？现在很忙。', '{t}? Busy right now.'),
        'young': ('{t}？ いま、いしょがちい！', '{t}？窝在忙！', '{t}? Busy!')},
    'caregiver': {
        'adult': ('{t}♪ そばに いてくれると、ほっとする。', '{t}♪ 有你陪着，我就安心了。', '{t}! I feel safe with you here.'),
        'young': ('{t}♪ もうちょっと そばに いちぇ……', '{t}♪ 陪窝一会儿……窝想挨着你。', '{t}! Stay a little longer... I wanna be close.')},
    'chew': {
        'adult': ("むーしゃ、むーしゃ……{t}。", "嚼嚼，嚼嚼……{t}。", "Munch, munch... {t}."),
        'young': ("むーちゃ、むーちゃ……{t}。", "嚼嚼……嚼嚼……{t}。", "Munch... munch... {t}.")},
    'cold': {
        'adult': ("{t}……さむい、さむい……", "{t}……冷、好冷……", "{t}... cold, so cold..."),
        'young': ('{t}……しゃむい……しゃむいよぉ……', '{t}……冷冷……好冷……', '{t}... cold... so cold...')},
    'courtship': {
        'adult': ('{t}♪ ……すき。', '{t}♪……喜欢。', '{t}! ...I like you.'),
        'young': ('{t}♪ ……しゅき。', '{t}♪……喜欢。', '{t}! ...Wike you.')},
    'courtship_held': {
        'adult': ("すーりすーり……がまん、がまん。{t}……", "想擦擦脸……忍住、忍住。{t}……", "Want to nuzzle... must wait. {t}..."),
        'young': ("しゅーりしゅーり……がまん。{t}……", "想渣渣脸……忍住。{t}……", "Want to nuzzle... must wait. {t}...")},
    'date_fond': {
        'adult': ('あのこ、つよそう……また、あいたい。{t}。', '那孩子看起来很强……还想再见。{t}。', 'That one looks strong... I want to meet again. {t}.'),
        'young': ('あのこ、いいこ！ また あいちゃい！ {t}！', '那孩子是好孩子！还想再见！{t}！', 'Dat one is nice! Wanna meet again! {t}!')},
    'date_meet': {
        'adult': ("{t}……ゆ、ゆっくりしていってね……", "{t}……油、油库里洗跌一跌捏……", "{t}... t-take it easy..."),
        'young': ("{t}……ゆ、ゆっくち しちぇいっちぇにぇ……", "{t}……油、油库气洗切一切捏……", "{t}... ch-chake id eaji...")},
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
        'young': ('{t}……こあい……ばっじの しけん、がんばりゅ！', '{t}……好口怕……窝要考到徽章！', '{t}... scawy... but I wanna earn a badge!')},
    'exam_pass': {
        'adult': ("{t}！ バッジ、バッジ！ やった！", "{t}！徽章、徽章！好耶！", "{t}! A badge, a badge! I did it!"),
        'young': ('{t}！ やっちゃ！ ばっじ！ しあわちぇー！', '{t}！太好惹！徽章！好幸福——！', '{t}! Yay! Badge! So happy!')},
    'exam_promise_fail': {
        'adult': ('ずるい！ わたしの おやつは？ {t}！', '耍赖！我的点心呢？{t}！', 'Not fair! Where is my snack? {t}!'),
        'young': ('じゅるい！ わたちの おやつは？ {t}！ ぷきゅーっ！', '耍赖！窝的点心呢？{t}！噗咕——！', 'No fair! Where is my snack? {t}! Hmph!')},
    'exam_promise_wait': {
        'adult': ("おやつさん……がまん、がまん。{t}……", "点心先生……忍住，忍住。{t}……", "Mr. Snack... must wait. {t}..."),
        'young': ('がまん、がまん……{t}……まだかにゃ……', '忍耐，忍耐……{t}……还没好吗……', 'Wait, wait... {t}... not yet...?')},
    'exam_report': {
        'adult': ('{t}！ にんげんさん！ このもんだい、できた！', '{t}！人类先生！这道题我做完了！', '{t}! Mr. Human! I finished the test task!'),
        'young': ('{t}！ にんげんしゃん！ このもんだい、できちゃ！', '{t}！人类鲜笋！这道题窝做完惹！', '{t}! Mr. Human! I finished the test task!')},
    'exam_sorry': {
        'adult': ('ごめんなさい……まちがえた。{t}……', '对不起……弄错了。{t}……', 'Sorry... I got it wrong. {t}...'),
        'young': ('ごめんにゃしゃい……まちがえちゃった……{t}……', '对不起……窝弄错惹……{t}……', 'Sowwy... got it wong... {t}...')},
    'exam_unsure': {
        'adult': ('このもんだい、どうやるの……？ {t}……', '这道题要怎么做……？{t}……', 'How do I do this task...? {t}...'),
        'young': ('このもんだい、どうやりゅの……？ {t}……', '这道题要肿么做……？{t}……', 'How do I do this task...? {t}...')},
    'exercise': {
        'adult': ('{t}！ すぶり！ えいっ、えいっ！', '{t}！挥剑！嘿咻、嘿咻！', '{t}! Sword practice! Hup, hup!'),
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
        'adult': ("ふぅ……だいじょうぶ、だいじょうぶ。{t}……", "呼……没事啦，没事啦。{t}……", "Phew... you're safe, you're safe. {t}..."),
        'young': ('ふう……もう だいじょぶ。{t}。', '呼……没事惹。{t}。', 'Phew... safe now. {t}.')},
    'family_alarm_flee': {
        'adult': ('ここは、あぶない！ はなれる！ {t}！', '这里危险！快离开！{t}！', "It's dangerous here! Move away! {t}!"),
        'young': ('ここ、あぶにゃい！ にげりゅ！ {t}！', '这里危险！快跑！{t}！', 'Dangewous! Wun! {t}!')},
    'family_died': {
        'adult': ("{t}……おきて……もういちど、すーりすーり……", "{t}……醒醒……还想跟你擦擦脸……", "{t}... wake up... one more nuzzle..."),
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
        'adult': ("{t}！ おおきく なった！ みて、みて！", "{t}！妖梦长大啦！看、快看！", "{t}! I'm bigger! Look, look!"),
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
        'adult': ("{t}！ おちびちゃん！ ゆっくりしていってね！", "{t}！小贝比酱！油库里洗跌一跌捏！", "{t}! Little one! Take it easy!"),
        'young': ("{t}！ おちびちゃん！ ゆっくち しちぇいっちぇにぇ！", "{t}！小贝比酱！油库气洗切一切捏！", "{t}! Little one! Take it easy!")},
    'house_listen': {
        'adult': ("ゆっ？ たまごさん、うごいた？ {t}？", "油？卵先生，动了吗？{t}？", "Yu? Mr. Egg, did you move? {t}?"),
        'young': ("ゆっ？ たまごしゃん、うごいちゃ？ {t}？", "油？卵鲜笋，动惹？{t}？", "Yu? Mr. Egg, did you move? {t}?")},
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
        'young': ('{t}！ こあい！ かくれりゅ！', '{t}！好口怕！要躲起来！', '{t}! Scawy! Hiding!')},
    'hungry': {
        'adult': ('{t}……おなかすいた……', '{t}……彭彭饿了……', "{t}... I'm hungry..."),
        'young': ('{t}……ぽんぽんしゃん、ぺこぺこ……', '{t}……彭彭先森饿惹……', '{t}... Mr. Tummy is hungwy...')},
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
        'adult': ('{t}♪ ちびちゃんたち……ぜったい、まもる。', '{t}♪ 小贝比酱……我会护着你们的。', "{t}! My little ones... I'll protect you, always."),
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
        'young': ("{t}……おやしゃん……どこぉ……", "{t}……亲亲油……在哪儿呀……", "{t}... where are you...?")},
    'mine': {
        'adult': ('ぷくーっ！ これは、わたしの！ {t}！', '噗咕——！这是妖梦的！{t}！', 'Puff! This is mine! {t}!'),
        'young': ('ぷきゅーっ！ わたちの！ {t}！', '噗啾——！窝的！{t}！', 'Puff! Mine! {t}!')},
    'mood_bad': {
        'adult': ('{t}……あんまり、ゆっくりできてない……', '{t}……不太能油库里……', "{t}... can't really relax..."),
        'young': ('{t}……あんまり ゆっくち できてにゃい……', '{t}……不太能油库里……', '{t}... not weawy wewaxed...')},
    'mood_good': {
        'adult': ("{t}！ きょうは ゆっくり できる！", "{t}！今天好能油库里！", "{t}! I can really take it easy today!"),
        'young': ("{t}！ ゆっくち できりゅ！", "{t}！好能油库气！", "{t}! I can take it easy!")},
    'mood_ok': {
        'adult': ("{t}。ゆっくり してる……", "{t}。在油库里……", "{t}. Taking it easy..."),
        'young': ("{t}。ゆっくち ちてる……", "{t}。在油库气……", "{t}. Taking it easy...")},
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
        'adult': ("ゆぅ……かって もらえない……{t}……", "油呜……还是没人养妖梦……{t}……", "Yuu... nobody wants to keep me... {t}..."),
        'young': ("ゆぅ……かって くれにゃい……{t}……", "油呜……还是没人养窝……{t}……", "Yuu... nobody wants to keep me... {t}...")},
    'puff': {
        'adult': ('ぷくーっ！ {t}！ かかってこい！', '噗咕——！{t}！放马过来！', 'Puff! {t}! Bring it on!'),
        'young': ('ぷきゅーっ！ {t}！', '噗啾——！{t}！', 'Puff! {t}!')},
    'request_sulk': {
        'adult': ("ふんっ！ ぷくーっ！ {t}！", "哼！噗咕——！{t}！", "Hmph! Puff! {t}!"),
        'young': ("ふんっ！ ぷきゅーっ！ {t}！", "哼！噗咕——！{t}！", "Hmph! Puff! {t}!")},
    'request_thanks': {
        'adult': ("{t}！ ありがと！ ゆっくり できる！", "{t}！谢谢！能油库里啦！", "{t}! Thanks! I can take it easy!"),
        'young': ("{t}！ ありがちょ！ ゆっくち できりゅ！", "{t}！蟹蟹！能油库气惹！", "{t}! Fank you! I can take it easy!")},
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
        'adult': ('おかざり……みずに おいた……{t}……', '头饰……放水上了……{t}……', 'Headwear... on the water now... {t}...'),
        'young': ('おかじゃり……おみずに おいちゃ……{t}……', '饰饰……放到水上惹……{t}……', 'Headwear... on the water now... {t}...')},
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
        'adult': ('わたしも みんなと いっしょに いたい……{t}……', '我也想跟大家待在一起……{t}……', 'I want to stay with everyone too... {t}...'),
        'young': ('わたちも みんなと いっちょに いちゃい……{t}……', '窝也想跟大家待在一起……{t}……', 'I wanna stay with everyone too... {t}...')},
    'settlement_shun': {
        'adult': ('{t}！ しらないゆっくり、ちかづくな！', '{t}！不认识的油库里，别靠近！', '{t}! Strange yukkuri, stay back!'),
        'young': ('{t}？ だれ？ こにゃいで！', '{t}？系谁？不要过来！', "{t}? Who? Don't come!")},
    'settlement_submit': {
        'adult': ("おトイレさん、そうじする……ここに いさせて……{t}……", "我会打扫厕所先生……让我留下吧……{t}……", "I'll clean Mr. Toilet... let me stay... {t}..."),
        'young': ('おといれしゃん、そうじしゅる……ここに いちゃい……{t}……', '窝会打扫厕所鲜笋……窝想留下……{t}……', "I'll cwean Mr. Toilet... I wanna stay... {t}...")},
    'share': {
        'adult': ('{t}♪ はい、どうぞ。', '{t}♪ 给，请用。', '{t}! Here you go.'),
        'young': ('{t}♪ どーじょ！', '{t}♪ 给你！', '{t}! Hewe!')},
    'shop_appeal': {
        'adult': ('{t}！ かって！ つよいから、まもってあげる！', '{t}！买下妖梦吧！妖梦很强，会保护你！', "{t}! Buy me! I'm strong, I'll protect you!"),
        'young': ('{t}！ かっちぇ！ いいこだよ！', '{t}！买下窝！窝是乖孩子！', "{t}! Buy me! I'm good!")},
    'shop_bought': {
        'adult': ("{t}！ にんげんさんと おうちに かえる！", "{t}！要跟人类先生回家啦！", "{t}! I'm going home with Mr. Human!"),
        'young': ("{t}！ にんげんしゃんと おうちに かえりゅ！", "{t}！窝要跟人类鲜笋回家惹！", "{t}! I'm going home with Mr. Human!")},
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
        'adult': ("{t}！ あまあま！ むーしゃむーしゃ……しあわせ！", "{t}！好甜好甜！嚼嚼……好幸福！", "{t}! Sweet, sweet! Munch, munch... so happy!"),
        'young': ("{t}！ あみゃあみゃ！ むーちゃむーちゃ……しゃーわちぇ！", "{t}！搞甜搞甜！嚼嚼……好幸乎！", "{t}! Sweet, sweet! Munch, munch... so happy!")},
    'snack_greedy': {
        'adult': ('{t}！ もっと！ あまあま、もっとちょうだい！', '{t}！还要！再给妖梦甜甜！', '{t}! More! More sweets!'),
        'young': ('{t}！ もっちょ！ あみゃあみゃ、もっちょ！', '{t}！还要！甜甜还要！', '{t}! Mowe! Mowe sweeties!')},
    'snack_refuse': {
        'adult': ("おなか いっぱい……もう いらない。{t}……", "饱了……吃不下了。{t}……", "I'm full... no more. {t}..."),
        'young': ('ぽんぽんしゃん、いっぱい……あとで。{t}。', '彭彭先森已经饱惹……待会儿。{t}。', 'Mr. Tummy is full... later. {t}.')},
    'spray_drowsy': {
        'adult': ("{t}……あまい におい……ねむねむ……", "{t}……甜甜的味道……困了……", "{t}... smells sweet... sleepy..."),
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
        'adult': ("{t}……みんな、まだかな……ここで まってる……", "{t}……大家，还没来呀……妖梦在这儿等……", "{t}... where is everyone...? I'll wait here..."),
        'young': ('{t}……おうちの みんな、まだ かえらない……もうちょっと まっちぇる……', '{t}……家里的油还没肥来……窝再等一会儿……', '{t}... my family still isn’t back... I’ll wait a little more...')},
    'wait_learned': {
        'adult': ('{t}！ ゆっくり、りかいした！', '{t}！懂啦！我会等！', '{t}! I understand now!'),
        'young': ('{t}！ ゆっくち りきゃいちた！', '{t}！懂惹！窝会等！', '{t}! I undewstand!')},
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
        'adult': ('{t}！ いいぼう！ おまえは 観楼剣！', '{t}！好棍子！叫你觀樓劍！', '{t}! Good stick! You are Kanroken!'),
        'juvenile': ('{t}！ つよそうな ぼう！ 観楼剣に する！', '{t}！好强的棍子！叫觀樓劍！', '{t}! A strong stick! My Kanroken!'),
        'young': ('{t}！ いいぼう！ 観楼剣に しゅる！', '{t}！好棍棍！要叫它觀樓劍！', "{t}! Good stick! It's Kanroken now!"),
        'baby': ('{c}♪ ぼー！ ぼー！', '{c}♪ 棒棒！棒棒！', '{c}! Stick! Stick!')},
    'kanroken_lost': {
        'adult': ('観楼剣が……ない……{t}……でも、なかない！', '觀樓劍……不见了……{t}……但是不哭！', "Kanroken... is gone... {t}... but I won't cry!"),
        'juvenile': ('観楼剣、おれちゃった……{t}……またさがす！', '觀樓劍断掉了……{t}……再去找一根！', "Kanroken broke... {t}... I'll find another!"),
        'young': ('観楼剣……にゃい……{t}……にゃかにゃい……', '觀樓劍……没惹……{t}……窝不哭……', 'Kanroken... gone... {t}... not cwying...'),
        'baby': ('ぼー……にゃい……{c}ーっ！', '棒棒……没有了……{c}——！', 'Stick... gone... {c}!')},
    'forage_proud': {
        'adult': ('{t}！ ごはんさん、もってきた！ みて、みて！', '{t}！饭先生带回来啦！快看、快看！', '{t}! Brought Mr. Food back! Look, look!'),
        'juvenile': ('{t}！ ごはんしゃん、もってきた！', '{t}！窝把饭鲜笋带回来了！', '{t}! I brought Mr. Food back!'),
        'young': ('{t}！ ごはんしゃん、もっちぇきちゃ！', '{t}！窝带饭鲜笋肥来惹！', '{t}! I brought Mr. Food back!'),
        'baby': ('{c}♪ まんま！ まんま！', '{c}♪ 饭饭！饭饭！', '{c}! Food! Food!')},
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

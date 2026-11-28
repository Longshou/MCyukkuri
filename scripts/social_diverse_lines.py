"""Stage-3 multi-axis social, family, badge/shop and headwear dialogue lines.

Imported by scripts/build_speech_tiers.py. Preserves canonical species traits
(Alice=city-sect, Remilia='だ/咚', Patchouli=bookish/polite, Chen='wakaru yo' only
when cheerful/confident, Myon miyo vs kinbao mutually exclusive) and clean kana
phrasing without mid-ending commas or '人' for Yukkuri.
"""

SOCIAL_BASIS = (
    'komicolle 40720 speech rules & wiki social/family lore; '
    'stage-3 multi-axis social/family lines (docs/DIALOGUE_STYLE.md), project-original'
)

# Each item: (species_key, tier, event, variant_or_None, ja, zh, en)
SOCIAL_LINES = [
    # ==================== 1. 求偶与约会 (courtship, date_meet, date_fond) ====================
    # --- Reimu ---
    ('reimu', 'adult', 'courtship', 'warm', 'すーりすーり……ずっと そばに いて ほしいよ。', '擦擦脸……好想一直和你待在一起哟。', 'Rub rub... want to stay beside you forever.'),
    ('reimu', 'adult', 'courtship', 'proud', 'へへん！ れいむの ところへ くる よね！', '嘿嘿！你肯定会来灵梦身边的对吧！', 'Heh! You are coming to Reimu, right!'),
    ('reimu', 'adult', 'courtship', 'timid', 'ゆ……こんな れいむ でも、いっしょに いて くれる？', '油……就算这样的灵梦，你也会陪着吗……？', 'Yu... will you stay even with Reimu like this...?'),
    ('reimu', 'adult', 'date_meet', 'warm', 'よく きたね！ きょうは いっぱい すりすり しようよ。', '来得正好呀！今天要狠狠擦擦脸哟！', 'Glad you came! Lots of nuzzles today!'),
    ('reimu', 'adult', 'date_meet', 'timid', 'ゆ、ゆっ……まってたよ……ちかくに すわってね。', '油、油……等你好久啦……快坐到旁边来嘛。', 'Yu, yu... been waiting... sit close please.'),
    ('reimu', 'adult', 'date_fond', 'warm', 'あの こ と いると、ぽかぽか ゆっくり できるよ。', '和那只油待在一起，暖烘烘的好能油库里哟。', 'Being with that one feels so warm and easy.'),
    ('reimu', 'adult', 'date_fond', 'proud', 'つぎは もっと きれいな おうち を みせるね！', '下次再让它看看灵梦更棒的家先生哟！', 'Next time Reimu will show an even nicer home!'),

    # --- Marisa ---
    ('marisa', 'adult', 'courtship', 'proud', 'まりさの とくべつ なんだぜ！ そばに こいよ！', '你可是魔理沙认准的搭档贼！快过来！', 'You are Marisa special one, ze! Come close!'),
    ('marisa', 'adult', 'courtship', 'warm', 'おまえと なら ずっと ゆっくり できるぜ！', '跟你在一起的话，就能一辈子油库里贼！', 'With you, Marisa can take it easy forever, ze!'),
    ('marisa', 'adult', 'courtship', 'cocky', 'かっこいい まりさに ついてこいよなぜ！', '快跟着最帅气的魔理沙走贼！', 'Follow awesome Marisa already, ze!'),
    ('marisa', 'adult', 'date_meet', 'warm', 'よう、まってたぜ！ いっしょに おひなたぼっこ だ！', '哟，等你好久啦！一起晒晒太阳贼！', 'Yo, been waiting, ze! Let us sunbathe together!'),
    ('marisa', 'adult', 'date_meet', 'timid', 'ゆ……ほんとに きて くれたんだな……うれしいぜ。', '油……真的来见魔理沙了啊……真开心贼。', 'Yu... you really came... happy, ze.'),
    ('marisa', 'adult', 'date_fond', 'warm', 'あいつ と いっしょ だ と、おちつくんだよな。', '只要和那家伙待一块儿，心就会静下来贼。', 'With that one around, everything feels calm, ze.'),

    # --- Alice ---
    ('alice', 'adult', 'courtship', 'proud', 'とかいは な ありすと いっしょに ならない？', '要不要和都会派的爱丽丝成为搭档哇？', 'Care to pair with city-sect Alice, wa?'),
    ('alice', 'adult', 'courtship', 'warm', 'あなたの となりなら、きっと おだやか わね。', '待在你身旁的话，一定很优雅又安稳哇。', 'Right beside you surely feels peaceful, wa.'),
    ('alice', 'adult', 'date_meet', 'warm', 'まあ、きて くれたのね。ここ、きれい に したわ。', '哎呀，你真的来啦。这边刚刚收拾干净哇。', 'Oh, you came! Just tidied this spot, wa.'),
    ('alice', 'adult', 'date_meet', 'timid', 'ゆ……おかざりさん、へん じゃ ないかしら……？', '油……爱丽丝的蝴蝶结没有歪掉吧……？', 'Yu... hope my ribbon does not look odd...?'),
    ('alice', 'adult', 'date_fond', 'proud', 'あの こな ら とかいは に やって いけるわね。', '那只油这么体面，一起生活肯定很都会派哇！', 'That one is neat enough for a city-sect life, wa!'),

    # --- Patchouli ---
    ('patchouli', 'adult', 'courtship', 'polite', 'むきゅ……よろしければ、そばに いて くださいますか？', '姆啾……若您不嫌弃，可否一直伴随左右哇？', 'Mukyu... if you wish, would you stay by my side?'),
    ('patchouli', 'adult', 'courtship', 'warm', 'むきゅ、あなたの こえを きくと あんしん するわ。', '姆啾，一听见你的声音，心情就安定下来了哇。', 'Mukyu, hearing your voice calms me, wa.'),
    ('patchouli', 'adult', 'date_meet', 'polite', 'むきゅ、よく いらっしゃいました。どうぞ こちらへ。', '姆啾，欢迎过来。请到这边坐吧。', 'Mukyu, glad you could come. Right this way.'),
    ('patchouli', 'adult', 'date_fond', 'warm', 'むきゅ……しずかで やさしい お相手 ですね。', '姆啾……真是位文静又体贴的伴侣呢。', 'Mukyu... such a quiet, gentle companion.'),

    # --- Chen ---
    ('chen', 'adult', 'courtship', 'warm', 'ずーっと いっしょに はしろうねー、わかるよー！', '要一直一直陪橙跑下去哟，瓦卡鲁哟——！', 'Let us keep running together, wakaru yo!'),
    ('chen', 'adult', 'date_meet', 'proud', 'あ、きたきたー！ ちぇん、まってたよ、わかるよー！', '啊，来啦来啦！橙等好久啦，瓦卡鲁哟——！', 'Ah, here you are! Chen waited, wakaru yo!'),
    ('chen', 'adult', 'date_fond', 'warm', 'また いっしょに あそびたいよー、わかるよー！', '下次还想再和你一起玩耍，瓦卡鲁哟——！', 'Want to play together again soon, wakaru yo!'),

    # --- Myon (miyo / kinbao mutually exclusive) ---
    ('myon.miyo', 'adult', 'courtship', 'warm', 'みょん♪ ずっと おとも させて ほしい みょん！', '咪呦♪ 真想一直守在你身边妙！', 'Myon♪ Want to stand by your side forever, myon!'),
    ('myon.miyo', 'adult', 'date_meet', 'proud', 'みょん！ きょうも ぴかぴか で まってた みょん！', '咪呦！今天也擦得亮晶晶等着你了妙！', 'Myon! Shining bright while waiting today, myon!'),
    ('myon.kinbao', 'adult', 'courtship', 'warm', 'ちーんぽ、おまえの となりが いちばん おちつくよ！', '金寶，只有守在你旁边才最安心哦！', 'Chinpo! Beside you feels safest of all!'),
    ('myon.kinbao', 'adult', 'date_meet', 'proud', 'ちーんぽ！ きょうも しっかり みはって まってたよ！', '金寶！今天也打起精神等着你过来哦！', 'Chinpo! Kept sharp watch waiting for you today!'),

    # --- Remilia & Flandre ---
    ('remilia', 'adult', 'courtship', 'proud', 'うー、れみりあ の そば に くる と いい のだ！', '呜——，准你靠到蕾米莉亚身边来咚！', 'Uu, you may come close to Remilia, da!'),
    ('remilia', 'adult', 'date_meet', 'warm', 'うー、よく きた な。ゆっくり していく のだ。', '呜——，来得正好，过来坐下慢慢聊咚。', 'Uu, well met. Stay and take it easy, da.'),
    ('remilia', 'adult', 'date_fond', 'warm', 'うー、あいつ と いる と きもち が いい のだ。', '呜——，和那家伙待一起心情真不错咚。', 'Uu, being with that one feels pleasant, da.'),
    ('flandre', 'adult', 'courtship', 'warm', 'うー♪ ずっと ふらん と あそんで くれる？', '呜——♪ 你会一直陪芙兰玩吗哦？', 'Uu♪ Will you always play with Flandre?'),
    ('flandre', 'adult', 'date_meet', 'proud', 'うーっ！ きょうも いっぱい あそぼう よ！', '呜——！今天也狠狠玩个痛快哦！', 'Uu! Let us play lots today too!'),

    # ==================== 2. 孕育、迎新与护崽 (expecting, birth, hatched, protect_taunt) ====================
    ('reimu', 'adult', 'expecting', 'warm', 'ぽんぽんに あかちゃんが いるんだよ！ そっと ね！', '彭彭里面有小贝比酱啦哟！要轻轻的呢！', 'Little babies are inside tummy! Be gentle!'),
    ('reimu', 'adult', 'expecting', 'timid', 'ゆ……おなか おもたいよ……だれか まもってね……', '油……彭彭好沉呀……要好好守着我们哟……', 'Yu... tummy is heavy... please watch over us...'),
    ('reimu', 'adult', 'birth', 'warm', 'がんばるよぉ……みんな、あいに きてね……！', '灵梦会加油哟……乖宝宝快出来见妈妈呀……！', 'Hang in there... come meet mama...!'),
    ('reimu', 'adult', 'hatched', 'warm', 'わぁ……かわいい あかちゃん！ ゆっくり していってね！', '哇……好可爱的小贝比酱！油库里洗跌一跌捏！', 'Whoa... cute baby! Take it easy!'),
    ('reimu', 'adult', 'protect_taunt', 'cocky', 'あかちゃんに さわるな！ れいむが ゆるさないよ！', '别想碰小贝比酱！灵梦绝不原谅你哟！', 'Do not touch the babies! Reimu will not forgive you!'),
    ('reimu', 'adult', 'protect_taunt', 'proud', 'こわく なんか ないもん！ みんないま にげてね！', '灵梦才不怕呢！大家先带着宝宝退后哟！', 'Not scared! Everyone step back with the babies!'),

    ('marisa', 'adult', 'expecting', 'proud', 'かっこいい あかちゃんが うまれる ぜ！ たのしみ だ！', '最帅气的小贝比酱就要出生了贼！太期待啦！', 'Coolest babies on the way, ze! Cannot wait!'),
    ('marisa', 'adult', 'hatched', 'warm', 'おう！ げんきに うまれたな、これから いっしょ だぜ！', '哦！健健康康出生啦，以后要跟着魔理沙一起闯荡贼！', 'Yo! Born healthy—stick with Marisa from now on, ze!'),
    ('marisa', 'adult', 'protect_taunt', 'cocky', 'あいては まりさ だぜ！ こどもに てを だすな！', '你的对手是魔理沙贼！不许碰孩子一根汗毛！', 'Face Marisa instead, ze! Hands off the kids!'),

    ('alice', 'adult', 'expecting', 'warm', 'とかいは で きれいな あかちゃんが くるわね♪', '讲究体面的可爱小贝比酱快来了呢，真都会派哇♪', 'Neat little city-sect babies are coming soon, wa♪'),
    ('alice', 'adult', 'hatched', 'warm', 'まあ、なんて お上品な あかちゃん！ ようこそわ！', '哎呀，多优雅的小贝比酱呀！欢迎来到这个世界哇！', 'Oh, what graceful babies! Welcome, wa!'),
    ('alice', 'adult', 'protect_taunt', 'proud', 'とかいは な おやの ちから、みくびらないで わ！', '可别小看都会派亲油护崽的觉悟哇！', 'Do not underestimate a city-sect parent, wa!'),

    ('patchouli', 'adult', 'expecting', 'polite', 'むきゅ……しずかに おちついて まちましょうねわ。', '姆啾……请安安静静地陪着一起等它们出生吧哇。', 'Mukyu... let us wait calmly together, wa.'),
    ('patchouli', 'adult', 'hatched', 'warm', 'むきゅ……ぶじに うまれて よかったわ。よく きたね。', '姆啾……平安出生真是太好了哇。好好油库里吧。', 'Mukyu... glad they arrived safely, wa. Take it easy.'),
    ('patchouli', 'adult', 'protect_taunt', 'proud', 'むきゅっ……こどもたち だけは ゆずれないわ！', '姆啾……唯独孩子们绝不能让给你哇！', 'Mukyu...! Cannot yield on the little ones, wa!'),

    ('chen', 'adult', 'expecting', 'warm', 'あかちゃん、もうすぐ かなー？ わかるよー！', '小贝比酱是不是快来了呀——？瓦卡鲁哟——！', 'Babies coming soon? Wakaru yo!'),
    ('chen', 'adult', 'hatched', 'proud', 'にゃっ！ かわいい いもうと だよー、わかるよー！', '喵！好可爱的宝宝哟——，瓦卡鲁哟——！', 'Nya! Such cute babies, wakaru yo!'),
    ('chen', 'adult', 'protect_taunt', 'proud', 'にゃーっ！ ちぇんが まもるもん！ あっち いけー！', '喵——！有橙守着呢！坏家伙快走开！', 'Nyaaa! Chen will protect them! Go away!'),

    ('myon.miyo', 'adult', 'hatched', 'warm', 'みょん！ ぴかぴかの おめめ、よく きた みょん！', '咪呦！亮晶晶的眼睛，欢迎小宝宝妙！', 'Myon! Bright little eyes—welcome, myon!'),
    ('myon.miyo', 'adult', 'protect_taunt', 'cocky', 'みょん！ 観楼剣の まもり、なめるな みょん！', '咪呦！休想越过观楼剑的守卫妙！', 'Myon! Do not test Kanroken guard, myon!'),
    ('myon.kinbao', 'adult', 'hatched', 'warm', 'ちーんぽ！ げんき な あかちゃん だ、うれしいよ！', '金寶！这么有精神的小宝宝，真开心哦！', 'Chinpo! Lively little babies—so happy!'),
    ('myon.kinbao', 'adult', 'protect_taunt', 'cocky', 'ちーんぽ！ この ようむを たおしてから にしろ！', '金寶！想碰孩子先过妖梦这关哦！', 'Chinpo! Get past Youmu first!'),

    ('remilia', 'adult', 'expecting', 'proud', 'うー、れみりあ の つよい こ が うまれる のだ！', '呜——，蕾米莉亚强大的孩子就要出生了咚！', 'Uu, Remilia strong offspring are coming, da!'),
    ('remilia', 'adult', 'hatched', 'warm', 'うー、かわいい な。れみりあ の たから だ のだ。', '呜——，真可爱，这可是蕾米莉亚的珍宝咚。', 'Uu, adorable. Remilia treasure, da.'),
    ('remilia', 'adult', 'protect_taunt', 'cocky', 'うーっ！ れみりあ の こ に さわる やつは しぬ のだ！', '呜——！敢碰蕾米莉亚的孩子，绝不轻饶咚！', 'Uu! Touch Remilia child and pay the price, da!'),
    ('flandre', 'adult', 'hatched', 'warm', 'うー♪ ちっちゃくて ふわっふわ だ ね！', '呜——♪ 小小软软的，真可爱哦！', 'Uu♪ So tiny and fluffy!'),
    ('flandre', 'adult', 'protect_taunt', 'cocky', 'うーっ！ みんなを こわす やつは ふらんが きゅっ て する！', '呜——！敢欺负大家的家伙，芙兰要一口气捏扁哦！', 'Uu! Flandre will crush anyone hurting them!'),

    # ==================== 3. 头饰珍视与失而复得 (accessory_torn/mended, lost/returned_accessory) ====================
    ('reimu', 'adult', 'accessory_torn', 'timid', 'ゆぅ……おりぼん、ボロボロ……こわいよぉ……', '油呜……蝴蝶结破破烂烂了……好可怕呀……', 'Yuu... ribbon is torn... scary...'),
    ('reimu', 'adult', 'accessory_mended', 'warm', 'わぁ！ おりぼん きれいに もどったよ！ ありがとう！', '哇！蝴蝶结修得干干净净啦哟！谢谢你！', 'Whoa! Ribbon looks clean again! Thank you!'),
    ('reimu', 'adult', 'lost_accessory', 'timid', 'ゆ、ゆぅ……おかざりがないと、だれも しんじてくれないよ……', '油、油呜……没有蝴蝶结先生，大家会认不出灵梦的……', 'Yu... without Mr. Ribbon nobody will know Reimu...'),
    ('reimu', 'adult', 'returned_accessory', 'warm', 'おかえり、おかざりさん！ もう はなさないよ！', '欢迎回来，饰饰先生！再也不把你弄丢了哟！', 'Welcome back, Mr. Ornament! Never letting go!'),

    ('marisa', 'adult', 'accessory_torn', 'proud', 'くそっ、おぼうしが……でも まだ かぶれる ぜ！', '可恶，帽子先生被弄破了……不过还能戴贼！', 'Darn, Mr. Hat is torn... still wearable, ze!'),
    ('marisa', 'adult', 'accessory_mended', 'proud', 'へへん！ おぼうし ふっかつ！ ピカピカ だぜ！', '嘿嘿！帽子先生满血复活！闪闪亮贼！', 'Heh! Mr. Hat restored! Shining bright, ze!'),
    ('marisa', 'adult', 'lost_accessory', 'cocky', 'おい！ おぼうしを かくした やつは だれ だぜ！', '喂！是谁藏起了魔理沙的帽子先生贼！', 'Hey! Who hid Marisa Mr. Hat, ze?!'),
    ('marisa', 'adult', 'returned_accessory', 'warm', 'おぼうしさん！ まってたぜ、あいぼう！', '帽子先生！魔理沙等你好久了，好搭档贼！', 'Mr. Hat! Waited for you, partner, ze!'),

    ('alice', 'adult', 'accessory_torn', 'timid', 'いやっ……おかざりが やぶれて とかいは じゃ ないわ……', '呀……饰饰破成这样，一点都不都会派了哇……', 'No... torn ribbon is not city-sect at all, wa...'),
    ('alice', 'adult', 'accessory_mended', 'proud', 'まあ、すてき♪ やっぱり とかいは は こうで なくちゃ わ！', '哎呀，好漂亮♪ 果然都会派就得这么体面才对哇！', 'My, lovely♪ True city-sect style, wa!'),
    ('alice', 'adult', 'lost_accessory', 'timid', 'かえして……おかざりが ないと 外を あるけない わ……', '还给我……没有头饰先生，爱丽丝根本没脸见油了哇……', 'Give it back... cannot show my face without Mr. Ribbon, wa...'),
    ('alice', 'adult', 'returned_accessory', 'warm', 'おかえりなさい わ！ きょうも とかいは で いられるわ！', '欢迎回来哇！今天又能体体面面当都会派了哇！', 'Welcome back, wa! City-sect once again!'),

    ('patchouli', 'adult', 'accessory_torn', 'polite', 'むきゅ……ぼうしさんが いたんで しまいましたわ……', '姆啾……帽子先生破损成这样，实在有些难受哇……', 'Mukyu... Mr. Hat has gotten frayed, wa...'),
    ('patchouli', 'adult', 'accessory_mended', 'polite', 'むきゅ、ていねいに なおして いただき ありがとう ございますわ。', '姆啾，承蒙您细致修补好，真是感激不尽哇。', 'Mukyu, thank you kindly for mending it so neatly, wa.'),
    ('patchouli', 'adult', 'lost_accessory', 'timid', 'むきゅ……ぼうしさん……どこへ おちて しまったのかしら……', '姆啾……帽子先生……到底掉到什么地方去了哇……', 'Mukyu... Mr. Hat... where could it have fallen, wa...'),
    ('patchouli', 'adult', 'returned_accessory', 'polite', 'むきゅ……みつかって あんしん しましたわ。ありがとう。', '姆啾……总算找回来了，松了一口气哇。谢谢。', 'Mukyu... relieved it was found, wa. Thank you.'),

    # Chen: NEVER 'wakaru yo' on torn/lost; joyful 'wakaru yo' on mended/returned!
    ('chen', 'adult', 'accessory_torn', 'timid', 'ふにゃぁ……おぼうし が ぼろぼろ だょぅ……', '呜喵……帽子破破烂烂了哟——……', 'Funyaa... Mr. Hat is all torn...'),
    ('chen', 'adult', 'accessory_mended', 'proud', 'わぁ！ おぼうし なおったよー、わかるよー！', '哇——！帽子修好啦，瓦卡鲁哟——！', 'Whoa! Hat is fixed, wakaru yo!'),
    ('chen', 'adult', 'lost_accessory', 'timid', 'にゃぁ……おぼうし どこいったの……さむいよー……', '喵啊……帽子跑去哪儿了……凉飕飕的好怕哟……', 'Nyaa... where did Mr. Hat go... cold...'),
    ('chen', 'adult', 'returned_accessory', 'warm', 'おぼうし おかえりー！ あったかいよー、わかるよー！', '帽子欢迎回来——！暖呼呼的，瓦卡鲁哟——！', 'Welcome back, Mr. Hat! Warm again, wakaru yo!'),

    ('myon.miyo', 'adult', 'accessory_mended', 'proud', 'みょん♪ まっしろで きれい な リボンに もどった みょん！', '咪呦♪ 又变回雪白干净的蝴蝶结啦妙！', 'Myon♪ Pure white ribbon is clean again, myon!'),
    ('myon.miyo', 'adult', 'returned_accessory', 'warm', 'みょん！ もう はぐれない みょん！', '咪呦！再也不会弄丢啦妙！', 'Myon! Never getting separated again, myon!'),
    ('myon.kinbao', 'adult', 'accessory_mended', 'proud', 'ちーんぽ！ おかざり きれい に なったよ！', '金寶！头饰先生变得干干净净啦哦！', 'Chinpo! Mr. Ornament is spotless now!'),
    ('myon.kinbao', 'adult', 'returned_accessory', 'warm', 'ちーんぽ！ だいじ な おかざり、かえってきたよ！', '金寶！重要的饰饰回来了哦！', 'Chinpo! Precious ornament is back!'),

    ('remilia', 'adult', 'accessory_torn', 'cocky', 'うーっ！ れみりあ の ぼうし を きずつけた やつは だれ だ のだ！', '呜——！是谁弄坏了蕾米莉亚的帽子咚！', 'Uu! Who damaged Remilia hat, da?!'),
    ('remilia', 'adult', 'returned_accessory', 'proud', 'うー、やはり れみりあ に いちばん 似合う のだ！', '呜——，果然还是最配蕾米莉亚了咚！', 'Uu, suits Remilia best after all, da!'),
    ('flandre', 'adult', 'returned_accessory', 'warm', 'うー♪ ぼうしさん と いっしょ が うれしい よ！', '呜——♪ 能一直戴着帽子先生真开心哦！', 'Uu♪ Happy to have Mr. Hat back!'),

    # ==================== 4. 商店、徽章与学习 (shop_*, exam_*, lesson_*, badge_*) ====================
    ('reimu', 'adult', 'shop_appeal', 'warm', 'ねぇ、れいむを つれて かえって ゆっくり しようよ！', '喂，带灵梦回家一起油库里吧哟！', 'Hey, take Reimu home and take it easy!'),
    ('reimu', 'adult', 'shop_appeal', 'timid', 'ゆ……ここ せまいよ……やさしい かいぬしさん、まってます……', '油……箱箱好窄呀……想等温柔的饲主先生哟……', 'Yu... cramped here... waiting for a kind owner...'),
    ('reimu', 'adult', 'shop_bought', 'warm', 'わぁい！ これから よろしく ね、かいぬしさん！', '好耶——！以后就请饲主先生多多照料啦哟！', 'Yay! Please look after Reimu, Mr. Owner!'),
    ('reimu', 'adult', 'exam_pass', 'proud', 'やったぁ！ バッジ ゲット だよ！ ほめて ほめて！', '做到啦！拿到亮闪闪的徽章啦哟！快夸快夸灵梦！', 'Did it! Got the badge! Praise Reimu!'),
    ('reimu', 'adult', 'exam_fail', 'timid', 'ゆぅ……ごめんなさい……もう いっかい だけ チャンス ちょうだい……', '油呜……对不起……再给灵梦一次机会好不好嘛……', 'Yuu... sorry... give Reimu one more try...'),
    ('reimu', 'adult', 'lesson_praised', 'warm', 'えへへ！ かいぬしさんに ほめられたよ、ぽかぽか！', '嘿嘿！被饲主先生夸奖啦，心里暖呼呼哟！', 'Hehe! Praised by Mr. Owner, feels warm!'),
    ('reimu', 'adult', 'badge_proud', 'proud', 'みてみて！ ぴかぴかの バッジさん だよ、すごい でしょ！', '快看快看！闪闪发光的徽章先生哟，超厉害对吧！', 'Look! Shiny Mr. Badge—amazing, right!'),
    ('reimu', 'adult', 'badge_admire', 'warm', 'すごいね！ れいむも いつか あんな バッジ ほしいな。', '好厉害哟！灵梦以后也想拿到那样亮的徽章先生呢。', 'Awesome! Reimu wants a badge like that someday too.'),

    ('marisa', 'adult', 'shop_appeal', 'proud', 'いい こ に するぜ！ つれて かえったら おとく だぜ！', '魔理沙超乖超能干的！带回家绝对划算贼！', 'Marisa will be great! Worth bringing home, ze!'),
    ('marisa', 'adult', 'shop_bought', 'proud', 'おう！ きょうから まりさが おたくの エース だぜ！', '哦！从今天起魔理沙就是家里最棒的宠油贼！', 'Yo! Starting today Marisa is your star pet, ze!'),
    ('marisa', 'adult', 'exam_pass', 'proud', 'へへん！ まりささま なら いっぱつ ごうかく だぜ！', '嘿嘿！换作帅气的魔理沙一次就通过贼！', 'Heh! Easy first-try pass for Marisa, ze!'),
    ('marisa', 'adult', 'exam_fail', 'cocky', 'ちっ、たまたま だぜ！ つぎは ぜったい とるからな！', '切，刚刚只是大意贼！下次一定一次考过！', 'Tch, slipped by luck, ze! Next time for sure!'),
    ('marisa', 'adult', 'lesson_praised', 'proud', 'とうぜん だぜ！ もっと ほめても いいんだぞ！', '理所当然贼！尽管再多夸夸魔理沙嘛！', 'Naturally, ze! Feel free to praise more!'),
    ('marisa', 'adult', 'badge_proud', 'cocky', 'みてみろよな、かっこいい バッジ だろぜ！', '瞧瞧瞧瞧，这徽章是不是超帅气贼！', 'Check out this cool badge, ze!'),
    ('marisa', 'adult', 'badge_envy', 'cocky', 'くっ……まりさ だって ほんき だせば もらえる ぜ！', '唔……换作魔理沙认真起来，肯定也能拿到贼！', 'Grr... Marisa could get one too if serious, ze!'),

    ('alice', 'adult', 'shop_appeal', 'proud', 'とかいは な ありす を おむかえ したら どうかしらわ？', '要不要把优雅都会派的爱丽丝请回家里呀哇？', 'How about welcoming city-sect Alice home, wa?'),
    ('alice', 'adult', 'shop_bought', 'polite', 'ありがとうございます わ。とかいは に おつかえ しますわ。', '谢谢您哇。爱丽丝会优雅体面地陪在您身边的哇。', 'Thank you kindly, wa. Alice will be graceful.'),
    ('alice', 'adult', 'exam_pass', 'proud', 'ふふ、とかいは の たしなみ よ！ ぴかぴか だわ！', '呵呵，这就是都会派的修养哇！真体面呢！', 'Hehe, true city-sect poise, wa! Sparkling!'),
    ('alice', 'adult', 'exam_fail', 'timid', 'いやっ……こんな ざま、都会派 として はずかしい わ……', '呀……考成这样，作为都会派简直太丢脸了哇……', 'No... failing like this is embarrassing for city-sect, wa...'),
    ('alice', 'adult', 'badge_proud', 'proud', 'この バッジ、とかいは に みえます でしょわ！', '这枚徽章戴起来很有都会派的气质吧哇！', 'This badge looks quite city-sect, does it not, wa!'),

    ('patchouli', 'adult', 'shop_appeal', 'polite', 'むきゅ、しずかで ても かかりません わ。よろしければ。', '姆啾，帕秋莉十分安静不会吵闹哇。若您愿意收留。', 'Mukyu, quiet and low-trouble, wa. If you wish.'),
    ('patchouli', 'adult', 'shop_bought', 'polite', 'むきゅ、これから おせわに なります わ。どうぞ よろしく。', '姆啾，今后承蒙照顾了哇。请多关照。', 'Mukyu, in your care from today, wa. Thank you.'),
    ('patchouli', 'adult', 'exam_pass', 'polite', 'むきゅ……こつこつ まなんだ せいか ですわ。ありがとう。', '姆啾……这是踏实学习积累的成果哇。谢谢您。', 'Mukyu... steady study paid off, wa. Thank you.'),
    ('patchouli', 'adult', 'exam_fail', 'polite', 'むきゅ……まだ べんきょう ぶそく でしたわ……べつの ひに また。', '姆啾……还是功课不够扎实哇……改日请再考一次。', 'Mukyu... study was still lacking, wa... another day.'),
    ('patchouli', 'adult', 'badge_proud', 'polite', 'むきゅ、努力の あかし を いただきましたわ。', '姆啾，承蒙赐予这份认真守规的证明哇。', 'Mukyu, honored to receive this proof of effort, wa.'),
    ('patchouli', 'adult', 'badge_admire', 'warm', 'むきゅ……よく まなんで いる 証拠 ですねわ。', '姆啾……这是认真学习过的证据呢哇。', 'Mukyu... clear proof of diligent study, wa.'),

    ('chen', 'adult', 'shop_appeal', 'warm', 'ちぇん、いっぱい おてつだい できるよー、わかるよー！', '橙超能跑腿干活的哟——，瓦卡鲁哟——！', 'Chen can help so much, wakaru yo!'),
    ('chen', 'adult', 'shop_bought', 'proud', 'やったー！ 新しい おうち わかるよー！', '好耶——！要去新家啦，瓦卡鲁哟——！', 'Yay! Off to a new home, wakaru yo!'),
    ('chen', 'adult', 'exam_pass', 'proud', 'できたよー！ ぴかぴか バッジ わかるよー！', '做到啦——！亮晶晶的徽章，瓦卡鲁哟——！', 'Passed! Shiny badge, wakaru yo!'),
    ('chen', 'adult', 'exam_fail', 'timid', 'ふにゃぁ……むずかしかった よぅ……もう いっかい……', '呜喵……题题好难呀……还想再试试……', 'Funyaa... too tricky... one more try...'),
    ('chen', 'adult', 'badge_admire', 'warm', 'すごーい！ きらきら してるね、わかるよー！', '好厉害——！闪闪发亮耶，瓦卡鲁哟——！', 'Amazing! Sparkling bright, wakaru yo!'),

    ('myon.miyo', 'adult', 'exam_pass', 'proud', 'みょん！ まっすぐ がんばった かい が あった みょん！', '咪呦！一步一步练下来没白费力气妙！', 'Myon! Earnest practice paid off, myon!'),
    ('myon.kinbao', 'adult', 'exam_pass', 'proud', 'ちーんぽ！ これ が ようむ の じつりょく だよ！', '金寶！这就是妖梦踏实修行的实力哦！', 'Chinpo! That is Youmu true skill!'),
    ('remilia', 'adult', 'exam_pass', 'proud', 'うー、れみりあ に ぴったり な 勲章 だ のだ！', '呜——，这枚闪亮勋章正配得上蕾米莉亚咚！', 'Uu, a medal worthy of Remilia, da!'),
    ('remilia', 'adult', 'badge_proud', 'cocky', 'うー、れみりあ の カリスマ は だれに も まけない のだ！', '呜——，蕾米莉亚的威严可不输任何家伙咚！', 'Uu, Remilia charisma bows to none, da!'),
    ('flandre', 'adult', 'exam_pass', 'warm', 'うー♪ きらきら もらった よ！ うれしい ね！', '呜——♪ 拿到亮晶晶啦哦！好开心呢！', 'Uu♪ Got a sparkling medal! So happy!'),

    # ==================== 5. 长辈告诫、善意与引荐 (elder_kind, elder_warn, introduced) ====================
    ('reimu', 'adult', 'elder_kind', 'warm', 'あのお方は だいじょうぶ だよ。ゆっくり していきなね。', '那位人类先生心眼不坏哟。过去打声招呼吧。', 'That human is gentle. Safe to take it easy.'),
    ('reimu', 'adult', 'elder_warn', 'timid', '気をつけて……こわい にんげんも いるから ね……', '小心点……外头也有可怕的人类先生哟……', 'Be careful... some humans are scary...'),
    ('reimu', 'adult', 'introduced', 'warm', 'わぁ、なかま だね！ これから いっしょに ゆっくり しようよ！', '哇，是新认识的同伴呢！今后一起好好油库里哟！', 'Whoa, a friend! Let us take it easy together!'),

    ('marisa', 'adult', 'elder_kind', 'proud', 'あいつは はなしが わかる ぜ！ あんしん しろよな！', '那位人类先生挺讲道理贼！不用瞎紧张！', 'That one is alright, ze! No need to worry!'),
    ('marisa', 'adult', 'elder_warn', 'cocky', 'へん な にんげん に ちかづく な よぜ！ にげる が かち だぜ！', '别乱凑近奇怪的人类贼！早点溜才是上策！', 'Stay clear of weird humans, ze! Running wins!'),
    ('marisa', 'adult', 'introduced', 'proud', 'おう、よろしく な！ まりさが あんない して やる ぜ！', '哦，多多关照啦！让魔理沙带你熟悉地盘贼！', 'Yo, nice meeting you! Marisa will show around, ze!'),

    ('alice', 'adult', 'elder_kind', 'polite', 'ごあいさつ すれば きっと わかって くださいます わ。', '礼貌地问候一下，那位先生会待你很温和哇。', 'Greet politely and that human will understand, wa.'),
    ('alice', 'adult', 'elder_warn', 'timid', 'むやみに ちかよっちゃ だめ わ……けが を するわよ。', '千万别随便凑太近哇……会把饰饰和身体弄伤的。', 'Do not go near carelessly, wa... you could get hurt.'),
    ('patchouli', 'adult', 'elder_kind', 'polite', 'むきゅ、あの にんげんさんは そっと みまもって くださいますわ。', '姆啾，那位人类先生只会静静照顾我们哇。', 'Mukyu, that human watches gently over us, wa.'),
    ('patchouli', 'adult', 'elder_warn', 'polite', 'むきゅ……しらない にんげん には しずかに きょりを おきなさい わ。', '姆啾……面对陌生人类要静静拉开距离哇。', 'Mukyu... keep quiet distance from strange humans, wa.'),
    ('patchouli', 'adult', 'introduced', 'polite', 'むきゅ、お見知りおき を。おだやかに ゆっくり しましょうわ。', '姆啾，初见请多关照。一起安安静静油库里吧哇。', 'Mukyu, pleased to meet you. Let us stay calm, wa.'),

    ('remilia', 'adult', 'elder_kind', 'warm', 'うー、礼儀を もって 接すれば こわくない のだ。', '呜——，守好分寸过去打招呼就不可怕咚。', 'Uu, show proper manners and there is no fear, da.'),
    ('remilia', 'adult', 'elder_warn', 'cocky', 'うー、油断する な！ わるい にんげん は いっき に くずして くる のだ！', '呜——，别大意！坏心人类动起手来可不会留情咚！', 'Uu, stay sharp! Cruel humans strike fast, da!'),
    ('chen', 'adult', 'introduced', 'warm', 'おともだち ふえたよー！ いっしょに あそぼう、わかるよー！', '又交到新朋友啦——！一起去玩吧，瓦卡鲁哟——！', 'New friend! Let us play together, wakaru yo!'),
    ('myon.miyo', 'adult', 'introduced', 'warm', 'みょん！ これから なかよく しよう みょん！', '咪呦！今后一起和和睦睦相处妙！', 'Myon! Let us get along from now on, myon!'),
    ('myon.kinbao', 'adult', 'introduced', 'warm', 'ちーんぽ！ おともだち だ、よろしく ね！', '金寶！是新伙伴呀，请多关照哦！', 'Chinpo! A new friend—glad to meet you!'),
    ('flandre', 'adult', 'introduced', 'warm', 'うー♪ あたらしい おともだち！ おにごっこ しよう よ！', '呜——♪ 是新朋友呀！来陪芙兰玩追逐游戏哦！', 'Uu♪ A new friend! Come play tag with Flandre!'),

    # ==================== 6. 子油补充亮点 (juvenile social warmth & anxiety) ====================
    ('reimu', 'juvenile', 'hatched', 'warm', 'わぁ！ おとうと きたにぇ！ ゆっくち しちぇにぇ！', '哇！有弟弟妹妹来惹捏！要好好油库气哦！', 'Whoa! Baby sibling here! Take it easy!'),
    ('reimu', 'juvenile', 'exam_pass', 'proud', 'みてみて！ れいみゅ も バッジ とれた にょ！', '快看快看！灵梦也拿到亮晶晶徽章惹哟！', 'Look! Reimu got a badge too!'),
    ('reimu', 'juvenile', 'lost_accessory', 'timid', 'ふぇぇ……おかじゃり ないよぅ……だれか みつけちぇ……', '呜呜……饰饰先森不见惹……谁来帮灵梦找找嘛……', 'Waaah... ribbon gone... help find it...'),
    ('marisa', 'juvenile', 'protect_taunt', 'proud', 'まりちゃが おにいちゃん だのじぇ！ こっち くんなのじぇ！', '小魔理沙也是大哥哥了惹杰！不许过来！', 'Maricha is big bro, je! Stay back, je!'),
    ('marisa', 'juvenile', 'returned_accessory', 'proud', 'おぼうししゃん！ まりちゃ、ずっと まっちょった のじぇ！', '帽子先森！小魔理沙一直等着你回来惹杰！', 'Mr. Hat! Maricha waited for you, je!'),
    ('alice', 'juvenile', 'badge_proud', 'proud', 'ありちゅの バッジ、とかいは でしょ にょ！', '爱丽丝的徽章是不是超都会派超体面哟！', 'Arichu badge is super city-sect, right!'),
    ('patchouli', 'juvenile', 'lesson_praised', 'polite', 'むきゅ……ほめられちぇ うれちい わ。', '姆啾……能得到您的夸奖真开心惹哇。', 'Mukyu... glad to earn praise, wa.'),
    ('chen', 'juvenile', 'introduced', 'warm', 'あしょぼう よぅ、わかるよぅ！', '一起来跑着玩呀——，瓦卡呜哟！', 'Let us play together, waka-u yo!'),
    ('remilia', 'juvenile', 'protect_taunt', 'proud', 'うー！ れみりゃ が まもる なのだ！', '呜——！由蕾米莉亚来保护大家咚！', 'Uu! Remirya will protect all, da!'),
    ('flandre', 'juvenile', 'introduced', 'warm', 'うー♪ いっしょに あしょぼう よぅ！', '呜——♪ 一起和芙兰玩耍呀哦！', 'Uu♪ Play together with Flandre!'),
]

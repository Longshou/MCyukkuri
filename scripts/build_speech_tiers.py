"""Generate art/dialogue/speech-tiers.json: baby / juvenile / adult speech tiers, forms of address,
"counting stops at four" lines and the two Myon verbal tics.

Source of the worldview rules: komicolle 40720 floors 11518764 (baby babble, forms of address, "-sensei"
suffix), 11518780 (narrating own actions, counting collapses to "lots" at 4, vocabulary inherited at birth)
and 11518905 (Myon has two mutually exclusive tics, Patchouli may count past 4). Every line is a
project-original composition ('origin': 'project-original'); none is a Hakkei U line and none is voiced.
Myon's full per-event library and the kotatsu-Marisa lines live in scripts/myon_speech_lines.py.

Run: python scripts/build_speech_tiers.py   (then python scripts/export_dialogue.py)
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from myon_speech_lines import KOTATSU, KOTATSU_BASIS, LINES as MYON_LINES, MYON_BASIS, NEW as MYON_NEW, TICS as MYON_TICS  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'art/dialogue/speech-tiers.json'

# Counting. Ordinary individuals count 1, 2, 3 and then say "lots"; babies stop at 2; Patchouli counts to 9.
ORDINARY_CAP = 3
BABY_CAP = 2
PATCHOULI_CAP = 9
COUNT_BASES = ('count_food', 'count_stock', 'count_children', 'count_peers')

THING = {  # (jp, zh, en) of "1".."9" for things; babies lisp the first three
    'jp': ['ひとつ', 'ふたつ', 'みっつ', 'よっつ', 'いつつ', 'むっつ', 'ななつ', 'やっつ', 'ここのつ'],
    'jp_baby': ['ひとちゅ', 'ふたちゅ', 'みっちゅ'],
}
PERSON = {
    'jp': ['ひとり', 'ふたり', 'さんにん', 'よにん', 'ごにん', 'ろくにん', 'ななにん', 'はちにん', 'きゅうにん'],
    'jp_baby': ['ひとり', 'ふたり', 'さんにん'],
}
EN_NUM = ['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine']

# (jp, zh, en)
SPECIES = {
    'reimu': dict(en='Reimu', name=dict(baby=('れいみゅ', '灵梦'), juvenile=('れいみゅ', '灵梦'), adult=('れいむ', '灵梦')),
                  cry=('ゆっ、ゆっ', '油、油', 'Yu, yu'), pre=('', '', ''),
                  end=dict(juvenile=('にぇ', '捏'), adult=('よ', '哦'))),
    'marisa': dict(en='Marisa', name=dict(baby=('まりちゃ', '魔理沙'), juvenile=('まりちゃ', '魔理沙'), adult=('まりさ', '魔理沙')),
                   cry=('ゆっ、ゆっ', '油、油', 'Yu, yu'), pre=('', '', ''),
                   end=dict(juvenile=('のじぇ', '惹杰'), adult=('のぜ', '贼'))),
    'alice': dict(en='Alice', name=dict(baby=('ありちゅ', '爱丽丝'), juvenile=('ありちゅ', '爱丽丝'), adult=('ありす', '爱丽丝')),
                  cry=('ゆっ、ゆっ', '油、油', 'Yu, yu'), pre=('', '', ''),
                  end=dict(juvenile=('にょ', '哟'), adult=('わ', '呢'))),
    'chen': dict(en='Chen', name=dict(baby=('ちぇん', '橙'), juvenile=('ちぇん', '橙'), adult=('ちぇん', '橙')),
                 cry=('ゆ、にゃっ', '油、喵', 'Yu, nya'), pre=('', '', ''),
                 end=dict(juvenile=('よぅ', '哟——'), adult=('よー', '哟——'))),
    'patchouli': dict(en='Patchouli', name=dict(baby=('ぱちゅ', '帕秋莉'), juvenile=('ぱちゅ', '帕秋莉'), adult=('ぱちゅりー', '帕秋莉')),
                      cry=('む、きゅっ', '姆、啾', 'Mu, kyu'), pre=('むきゅ。', '姆啾。', 'Mukyu. '),
                      end=dict(juvenile=('わ', '惹哇'), adult=('わ', '哇'))),
    'remilia': dict(en='Remilia', name=dict(baby=('れみりゃ', '蕾米莉亚'), juvenile=('れみりゃ', '蕾米莉亚'), adult=('れみりあ', '蕾米莉亚')),
                    cry=('うー、うー', '呜——、呜——', 'Uu, uu'), pre=('うー、', '呜——', 'Uu... '),
                    end=dict(juvenile=('なのだ', '呀'), adult=('のだ', '呀'))),
    'flandre': dict(en='Flandre', name=dict(baby=('ふりゃん', '芙兰'), juvenile=('ふりゃん', '芙兰'), adult=('ふらん', '芙兰')),
                    cry=('うー、うー', '呜——、呜——', 'Uu, uu'), pre=('うー、', '呜——', 'Uu... '),
                    end=dict(juvenile=('よぅ', '哦'), adult=('よ', '哦'))),
}
# Myon (komicolle 11518905): two tics that exclude each other; an individual owns exactly one of them.
for quirk, cry, pre in (('miyo', ('みょ、みょん', '咪、咪呦', 'Myo, myon'), ('みょん！', '咪呦！', 'Myon! ')),
                        ('kinbao', ('ちん、ちーんぽ', '金、金寶', 'Chin, chinpo'), ('ちーんぽ！', '金寶！', 'Chinpo! '))):
    jp_name, en_name = ('みょん', 'Myon') if quirk == 'miyo' else ('ようむ', 'Youmu')
    name = (jp_name, '妖梦')
    juvenile_end = 'みょ' if quirk == 'miyo' else 'よぅ'
    SPECIES['myon.' + quirk] = dict(en=en_name, name=dict(baby=name, juvenile=name, adult=name),
                                    cry=cry, pre=pre, end=dict(juvenile=(juvenile_end, '哦'), adult=('よ', '哦')), quirk=quirk)

# --- baby (akayuu): babble plus a few simple words ---------------------------------------------------------
# {c} babble cry, {nb} baby self-name
BABY = {
    'hello': ('{c}！', '{c}！', '{c}!'),
    'newborn': ('{c}！ {c}！', '{c}！{c}！', '{c}! {c}!'),
    'hatched': ('{c}……？ {c}！', '{c}……？{c}！', '{c}...? {c}!'),
    'hungry': ('{c}……ぽんぽんしゃん、ぺこぺこ……', '{c}……彭彭先森饿惹……', '{c}... Mr. Tummy is hungry...'),
    'fed': ("{c}♪ むーちゃ、むーちゃ……しゃーわちぇ！", "{c}♪ 嚼嚼……好幸乎！", "{c}! Munch, munch... so happy!"),
    'chew': ("むーちゃ、むーちゃ……", "嚼嚼……嚼嚼……", "Munch... munch..."),
    'sleep': ('{c}……ねんね……', '{c}……睡睡……', '{c}... nighty-night...'),
    'awake': ('{c}？ ……{c}！', '{c}？……{c}！', '{c}? ...{c}!'),
    'afraid': ('{c}ーっ！ こあい！', '{c}——！好口怕！', '{c}! Scawy!'),
    'wounded': ('{c}ーっ！ いちゃい！', '{c}——！好痛！', '{c}! It hurts!'),
    'caregiver': ('{c}♪ そばに いちぇ……', '{c}♪ 陪陪窝……', '{c}! Stay with me...'),
    'want_nuzzle': ("しゅーり、しゅーり……{c}？", "渣渣脸……{c}？", "Rub-rub... {c}?"),
    'nuzzle': ("しゅーりしゅーり、{c}♪", "渣渣脸……{c}♪", "Rub-rub... {c}!"),
    'nuzzle_done': ('{c}♪ ぽかぽか……', '{c}♪ 暖乎乎……', '{c}! So warm...'),
    'need_toilet': ('{c}……うんうん、うんうん……', '{c}……嗯嗯，嗯嗯……', '{c}... Poo-poo, poo-poo...'),
    'toilet_done': ('しゅっきち！', '清爽啦！', 'All better!'),
    'cold': ('{c}……しゃむい……', '{c}……好冷……', '{c}... cold...'),
    'lost_parent': ('{c}ーっ！ おやしゃ……おやしゃ……', '{c}——！亲亲油……亲亲油……', '{c}! Mama... papa...'),
    'idle': ('{c}～♪', '{c}～♪', '{c}~'),
    'food_found': ('まんま！ まんま！', '饭饭！饭饭！', 'Num-num! Num-num!'),
    'take_food': ('まんま、もらっちゃ！', '饭饭，拿到惹！', 'Num-num, got it!'),
    'share': ('どーじょ……', '给你……', 'Here...'),
    'shy': ('{c}……はずかち……', '{c}……羞羞……', '{c}... shy...'),
    'treated': ('{c}♪ ありがちょ。', '{c}♪ 谢谢。', '{c}! Thank you.'),
    'washed': ('{c}♪ きれーきれー！', '{c}♪ 干净净！', '{c}! All clean!'),
    'toy': ('あしょぶ、あしょぶ！', '玩玩，玩玩！', 'Play, play!'),
    'mine': ('{nb}の！', '是{nb}的！', "That's {nb}'s!"),
    'peer': ('{c}？', '{c}？', '{c}?'),
    'known_peer': ('{c}♪ {c}♪', '{c}♪{c}♪', '{c}! {c}!'),
    'lost_accessory': ('おかじゃり……ない……{c}ーっ！', '饰饰……没有了……{c}——！', 'Hair thing... gone... {c}!'),
    'returned_accessory': ('おかじゃり！ {c}♪', '饰饰！{c}♪', 'Hair thing! {c}!'),
    'family_died': ('{c}……{c}……おきて……', '{c}……{c}……醒醒……', '{c}... {c}... wake up...'),
    # forms of address (a baby only knows the people in front of it)
    'addr_mother': ('おかーしゃ！ {c}♪', '麻麻！{c}♪', 'Mama! {c}!'),
    'addr_father': ("おとーしゃ！ {c}♪", "粑粑！{c}♪", "Papa! {c}!"),
    'addr_human_kind': ("にんげんしゃ！ {c}♪", "人类先森！{c}♪", "Mr. Human! {c}!"),
    'addr_human_bro': ('おにーしゃ！ {c}♪', '大哥哥！{c}♪', 'Big brother! {c}!'),
    'addr_human_sis': ('おねーしゃ！ {c}♪', '大姐姐！{c}♪', 'Big sister! {c}!'),
    'addr_human_owner': ('かいぬししゃ！ {c}♪', '饲主先森！{c}♪', 'Owner-sir! {c}!'),
    'addr_sibling_elder': ('おねーちゃ！ {c}♪', '姐姐！{c}♪', 'Big sis! {c}!'),
    'addr_sibling_younger': ('いもーと……？ {c}♪', '妹妹……？{c}♪', 'Little sis...? {c}!'),
    # body details (komicolle 11518802 / 11518847): bitter and spicy food, letting go from fright, a baked sole, wanting the powder
    'bitter': ('{c}ーっ！ にがにが、ぺっぺっ！', '{c}——！苦苦，呸呸！', '{c}! Bitter, bitter, ptooey!'),
    'food_complain': ('{c}ーっ！ これ、いや！ まずまず、ぺっぺっ！', '{c}——！不要这个！难吃难吃，呸呸！', '{c}! Not this one! Yucky, ptooey!'),
    'leak': ('{c}……あ……でちゃった……', '{c}……啊……漏出来了……', '{c}... oh... it came out...'),
    'sole_burned': ('{c}ーっ！ あんよしゃん、あちゅい！', '{c}——！脚脚先森好烫！', '{c}! Mr. Foot is hot!'),
    'craving': ('{c}……こなこな……ほちい……', '{c}……粉粉……想要……', '{c}... powder... want...'),
    # the centre filling is hurt: a short dying state (docs/FEATURES.md); the classic last words
    'dying': ("{c}……ゆっくち……もっと……", "{c}……还想……油库气……", "{c}... wanted... to take it easy..."),
}
# death and the dead (docs/FEATURES.md; social/GriefLife, social/Cannibalism, social/SelfOffering): last words to kin, the kin's grief, the stench, the child's side of "please eat me"
BABY.update({
    'dying_kin': ("{c}……おやしゃ……ゆっくち……ちてね……", "{c}……亲亲油……要油库气……", "{c}... take it easy..."),
    'kin_dying': ('{c}ーっ！ いかにゃいで、いかにゃいで！', '{c}——！别走，别走！', "{c}! Don't go, don't go!"),
    'stench': ('{c}ーっ！ くちゃい、くちゃい！', '{c}——！臭臭，臭臭！', '{c}! Stinky, stinky!'),
    # "please eat me" (social/SelfOffering): the child's side
    'offer_refuse': ("{c}ーっ！ やー！ おやしゃ、たべにゃいー！", "{c}——！亲亲油！窝不嚼……！", "{c}! No! I won't eat you!"),
    'offer_eat': ("{c}……むーちゃ……むーちゃ……", "{c}……嚼、嚼……", "{c}... munch... munch..."),
    'offer_grief': ('{c}……おやしゃ……いきりゅ……',
                    '{c}……亲亲油……{nb}会活……',
                    '{c}... Mama, Papa... {N} will live...'),
})
# baby counting frames: S is the counted sequence
BABY_COUNT = {
    'count_food': ('ごはん、{S}！', '饭饭，{S}！', 'Food: {S}!'),
    'count_stock': ('おうちのまんま、{S}！', '家里的饭饭，{S}！', 'Home food: {S}!'),
    'count_children': ('ちびちゃ、{S}！', '小宝宝，{S}！', 'Babies: {S}!'),
    'count_peers': ('おともだち、{S}！', '同伴，{S}！', 'Friends: {S}!'),
}

# --- juvenile (koyuu): half-mature, clearer words, "-sensei" nouns, narrates own actions -------------------
# {p} species prefix, {e} species ending, {n} self-name, {N} English name
JUVENILE = {
    'hello': ("{p}ゆっくち しちぇいっちぇにぇ！", "{p}油库气洗切一切捏！", "{p}Chake id eaji!"),
    'hungry': ('{p}ぽんぽんしゃんが ぺこぺこしてる{e}！', '{p}彭彭先生饿了{e}！', '{p}Mr. Tummy is hungry!'),
    'fed': ("{p}ごはんしゃん！ むーちゃ、むーちゃ……しあわせ{e}！", "{p}饭鲜笋！嚼嚼……好幸福{e}！", "{p}Mr. Food! Munch, munch... so happy!"),
    'sleep': ("{p}ねむねむ……おやすみ{e}……", "{p}困困……要睡{e}……", "{p}Sleepy... night-night..."),
    'awake': ("{p}ふわぁ……おきちゃ{e}……", "{p}呼啊……醒了{e}……", "{p}Yawn... I'm awake..."),
    'afraid': ('{p}ゆわぁ！ こあい{e}！', '{p}呜哇！好口怕{e}！', '{p}Waaah! Scawy!'),
    'wounded': ('{p}ゆぅ……いちゃい{e}！', '{p}油呜……好痛{e}！', '{p}Yuu... it hurts!'),
    'lost_parent': ("{p}おやしゃん！ どこにいる{e}……？", "{p}亲亲油！在哪儿{e}……？", "{p}Mama, Papa! Where are you...?"),
    'caregiver': ("{p}もうちょっと そばに いて{e}……くっちゅいて いたいの……", "{p}陪窝一会儿{e}……窝想挨着你……", "{p}Stay with me a little longer... I wanna be close to you..."),
    'food_found': ("{p}ゆっ！ ごはんしゃん みつけた{e}！", "{p}油！找到饭鲜笋{e}！", "{p}Yu! Found Mr. Food!"),
    'share': ("{p}はい、はんぶんこ{e}！", "{p}给！一油一点{e}！", "{p}Here! A bit for each!"),
    'lesson_learned': ("{p}できた、できた{e}！", "{p}会惹、会{e}！", "{p}I did it, I did it!"),
    'lost_accessory': ('{p}かざりさんが、ない{e}……！', '{p}头饰先生不见了{e}……！', '{p}My hair thing is gone...!'),
    'returned_accessory': ('{p}かざりさん、おかえり{e}！', '{p}头饰先生，欢迎回来{e}！', '{p}Welcome back, Mr. Hair Thing!'),
}

# --- shared juvenile / adult body-detail lines (same events as the baby ones above) --------------------------
BODY = {
    'bitter': ("{p}にがっ……ぺっ、ぺっ！", "{p}好苦……呸、呸！", "{p}Bitter... ptooey, ptooey!"),
    'food_complain': ("{p}これは……いや{e}。もっと ましな ごはんさんが いい{e}。", "{p}这个……不要{e}。想要更好的饭先生{e}。", "{p}This one... no. I want a better Mr. Food."),
    'leak': ("{p}ゆっ……でちゃった{e}……", "{p}油……漏出来了{e}……", "{p}Yu... it leaked out..."),
    'sole_burned': ("{p}あしが……あつい、あつい{e}！", "{p}脚……烫、好烫{e}！", "{p}My foot... hot, so hot!"),
    'craving': ('{p}あのこなさんが……ほしい{e}……', '{p}想要……那个粉末先生{e}……', '{p}I want... that powder...'),
    'dying': ("{p}もっと……ゆっくり……したかった{e}……", "{p}还想……再油库里一会儿{e}……", "{p}I wanted... to take it easy... a little longer..."),
}

# --- death and the dead, juvenile and adult (adults only for the taboo meal, the hunt of a hatless monster, self-sacrifice and the parent's side of "please eat me") ---------
DEATH = {
    'dying_kin': ("{p}みんな……ゆっくりしていってね……もっと、いっしょに いたかった{e}……", "{p}大家……要油库里{e}……还想陪你们{e}……", "{p}Everyone... take it easy... I wanted to stay with you..."),
    'kin_dying': ("{p}いかないで……まだ そばに いたい{e}……！", "{p}别走……还想陪着你{e}……！", "{p}Don't go... I still want to stay with you...!"),
    'stench': ("{p}くさっ……くさい{e}！ ゆっくりできない{e}……", "{p}臭……好臭{e}！没法油库里{e}……", "{p}It stinks... it stinks! I can't take it easy..."),
    # "please eat me": the child's side
    'offer_refuse': ("{p}たべたくない……いかないで{e}……！", "{p}不想嚼……别走{e}……！", "{p}I don't want to eat you... don't go...!"),
    'offer_eat': ("{p}ごめんね……むーしゃ……", "{p}对不起……嚼、嚼……", "{p}I'm sorry... munch..."),
    'offer_grief': ('{p}ごめんね……ちゃんと いきていく{e}……',
                    '{p}对不起……会好好活下去{e}……',
                    '{p}I am sorry... I will go on living...'),
}
JUVENILE_OFFER_GRIEF = (
    '{p}おやしゃん……ごめん……ちゃんと いきていく{e}……',
    '{p}亲亲油……对不起……窝会好好活下去{e}……',
    '{p}Mama, Papa... sowwy... I will go on living...')
DEATH_ADULT = {
    'eat_kin': ("{p}くさい……おなか、すいた{e}……ごめんね……", "{p}好臭……好饿{e}……对不起……", "{p}It stinks... I'm hungry... I'm sorry..."),
    'hunt_sweet': ('{p}かいぶつ{e}……でも、おなか すいた{e}……', '{p}是怪物{e}……可彭彭好饿{e}……', '{p}A monster... but I am so hungry...'),
    # "please eat me" (social/SelfOffering): the parent's side, a chain of its own beside the ordinary last words
    'offer_ask': ("{p}{n}を……たべて くれる{e}……？", "{p}来……把{n}吃了吧{e}……", "{p}Here... eat {N}..."),
    'offer_insist': ("{p}{n}を たべて……いきてね……", "{p}把{n}吃了……要活下去{e}……", "{p}Eat {N}... and live..."),
    'offer_give': ("{p}ゆっくり……{n}を たべてね……", "{p}油库里地吃{n}{e}……", "{p}Take your time... eat {N}..."),
    'offer_spared': ("{p}よかった……まだ いっしょに いられる{e}……", "{p}太好了……还能待在一起{e}……", "{p}Thank goodness... we can stay together..."),
    'sacrifice': ("{p}{n}が おとりに なる！ おちびちゃん、にげて！", "{p}{n}来引开坏蛋{e}！小贝比酱，快逃{e}！", "{p}{N} will draw the bully away! Little one, run!"),
}
DEATH_BASIS = ('komicolle 40720 overview end (the dying wish) / 11518847 (cannibalism taboo, stench only yukkuri smell); wiki E05/E08 (please eat me); '
               'project-original wording, unvoiced')

# --- shared juvenile / adult: addresses, narration, counting ------------------------------------------------
# {t} the term of address
ADDRESS = {
    'addr_mother': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_father': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_partner': ("{p}{t}……ゆっくり しよう{e}。", "{p}{t}……一起油库里{e}。", "{p}{t}... take it easy with me."),
    'addr_child': ("{p}{t}！ こっち{e}！", "{p}{t}！这边{e}！", "{p}{t}! Over here!"),
    'addr_sibling_elder': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_sibling_younger': ("{p}{t}！ こっち{e}！", "{p}{t}！这边{e}！", "{p}{t}! Over here!"),
    'addr_leader': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_human_kind': ("{p}{t}！ ゆっくりしていってね！", "{p}{t}！油库里洗跌一跌捏！", "{p}{t}! Take it easy!"),
    'addr_human_bro': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_human_sis': ("{p}{t}！ {t}！", "{p}{t}！{t}！", "{p}{t}! {t}!"),
    'addr_human_owner': ("{p}{t}！ ゆっくりしていってね！", "{p}{t}！油库里洗跌一跌捏！", "{p}{t}! Take it easy!"),
    'addr_human_hostile_old': ('{p}{t}、あっちにいって！', '{p}{t}快走开{e}！', '{p}Go away, {t}!'),
    'addr_human_hostile_man': ('{p}{t}、ごはんをおいてって！', '{p}{t}，把食物交出来{e}！', '{p}Hand over the food, {t}!'),
    # A hostile human who is not an elder draws no insult and no welcome: the yukkuri only wants it gone.
    'addr_human_hostile_wary': ('{p}にんげんさん、こないで{e}！ こわい{e}……', '{p}人类先生，别过来{e}！好可怕{e}……', '{p}Mr. Human, stay back! Scary...'),
    'addr_predator_remilia': ('{p}れみりゃがきた{e}！ こっちにこないで！', '{p}蕾米莉亚来了{e}！别过来！', "{p}Remilia's coming! Stay away!"),
    'addr_predator_flandre': ('{p}ふらんがいる{e}……！ みつからないように……', '{p}芙兰在那里{e}……！别被发现……', "{p}Flandre's there...! Don't get noticed..."),
    'thing_san': ('{p}これは{n}のたからものさん{e}！', '{p}这是{n}的宝物先生{e}！', "{p}This is {N}'s treasure, Mr. Treasure!"),
    'treasure_stolen': ('{p}{n}のたからものさん！ かえして、かえして……！', '{p}{n}的宝物先生！还回来，还回来{e}……！', "{p}{N}'s treasure! Give it back, give it back...!"),
    # the yukkuri says out loud what it is doing (komicolle 11518780)
    'narrate_steal': ("{p}ごはんさん……こっそり つれていく{e}……", "{p}饭先生……{n}悄悄把你带走{e}……", "{p}Mr. Food... {N} is sneaking you away..."),
    'narrate_flee': ('{p}ゆっ……にげなきゃ{e}……', '{p}油……得逃走{e}……', '{p}Yu... have to run...'),
    'narrate_hide': ('{p}みつかってない……まだ みつかってない……', '{p}还没发现{n}……还没发现……', '{p}They have not seen {N}... not yet...'),
}
JUVENILE_STEAL = ('{p}ごはんしゃん……こっそり つれちぇいく{e}……', '{p}饭鲜笋……{n}悄悄把你带走{e}……',
                  "{p}Mr. Food... {N} is sneaking you away...")
JUVENILE_HIDE = ('{p}みちゅかってない……みちゅかってない……', '{p}没有发现{n}，没有发现{n}……',
                 '{p}They have not seen {N}... they have not seen {N}...')
TERMS = {  # tier -> event -> (jp, zh, en)
    'juvenile': {
        'addr_mother': ('おかーしゃん', '妈妈', 'Mama'), 'addr_father': ('おとーしゃん', '爸爸', 'Papa'),
        'addr_sibling_elder': ('おねーちゃん', '姐姐', 'big sis'), 'addr_human_kind': ('にんげんしゃん', '人类先生', 'Mr. Human'),
        'addr_human_bro': ('おにーしゃん', '大哥哥', 'big brother'), 'addr_human_sis': ('おねーしゃん', '大姐姐', 'big sister'),
        'addr_human_owner': ('かいぬししゃん', '饲主先生', 'Mr. Owner'),
    },
    'adult': {
        'addr_mother': ('おかあさん', '妈妈', 'Mama'), 'addr_father': ('おとうさん', '爸爸', 'Papa'),
        'addr_sibling_elder': ('おねえちゃん', '姐姐', 'big sis'), 'addr_human_kind': ('にんげんさん', '人类先生', 'Mr. Human'),
        'addr_human_bro': ('おにいさん', '大哥哥', 'big brother'), 'addr_human_sis': ('おねえさん', '大姐姐', 'big sister'),
        'addr_human_owner': ('かいぬしさん', '饲主先生', 'Mr. Owner'),
    },
}
COMMON_TERMS = {
    'addr_partner': ('あなた', '亲爱的', 'dear'), 'addr_child': ('おちびちゃん', '小贝比酱', 'little one'),
    'addr_sibling_younger': ('いもうと', '妹妹', 'little sis'), 'addr_leader': ('ぞくちょうさん', '族长先生', 'Mr. Chief'),
    'addr_human_hostile_old': ('くそばばあ', '臭老太婆', 'old hag'), 'addr_human_hostile_man': ('くそじじい', '粪老头', 'old geezer'),
}
COUNT_FRAMES = {
    'count_food': ('{p}ごはんさんは……{S}！', '{p}食物先生有……{S}！', '{p}Mr. Food has... {S}!'),
    'count_stock': ('{p}おうちのたくわえさんは……{S}！', '{p}家里的存粮先生有……{S}！', '{p}The stockpile has... {S}!'),
    'count_children': ('{p}こどもは……{S}！', '{p}孩子有……{S}！', '{p}The children are... {S}!'),
    'count_peers': ('{p}なかまは……{S}！', '{p}同伴……{S}！', '{p}Friends: {S}!'),
}
# --- extra wording for frequent events, tagged by the speaker's current style -------------------------------
# A bold, pleased individual and a wary, low one say the same beat differently; NativeDialogue prefers the tag
# that matches speechStyle() and falls back to the plain line. Every row is project-original and unvoiced.
STYLED = {
    'hello': {
        'baby': {
            'proud': ('{c}！ ゆっくち しちぇいっちぇにぇ！', '{c}！油库气洗切一切捏！', '{c}! Take it eashy!'),
            'timid': ('{c}……こんにちは……', '{c}……你好……', '{c}... h-hello...'),
            'plain': ('{c}！ ゆっくち！', '{c}！油库气！', '{c}! Eashy!')},
        'juvenile': {
            'proud': ('{p}ゆっくち しちぇいっちぇにぇ！ {n}、げんき！', '{p}油库气洗切一切捏！{n}很精神！', '{p}Take it easy! {N} is full of energy!'),
            'timid': ('{p}ゆっくち……しちぇいっちぇにぇ……', '{p}油库气……洗切一切捏……', '{p}Take it... easy...'),
            'plain': ('{p}やあ、ゆっくち しちぇいっちぇにぇ。', '{p}呀，油库气洗切一切捏。', '{p}Hi, take it easy.')},
        'adult': {
            'proud': ('{p}ゆっくりしていってね！ {n}は げんき{e}！', '{p}油库里洗跌一跌捏！{n}很精神{e}！', '{p}Take it easy! {N} is doing well!'),
            'timid': ('{p}ゆっくり……していってね……', '{p}油库里……洗跌一跌捏……', '{p}Take it... easy...'),
            'plain': ('{p}やあ、ゆっくりしていってね。', '{p}呀，油库里洗跌一跌捏。', '{p}Hi, take it easy.')}},
    'hungry': {
        'baby': {
            'proud': ('{c}！ まんま、まんま！ ぺこぺこ！', '{c}！饭饭、饭饭！饿饿！', '{c}! Num-num, num-num! Hungry!'),
            'timid': ('{c}……ぽんぽん、ぺこ……', '{c}……彭彭，饿……', '{c}... tummy... hungry...'),
            'plain': ('{c}……まんま……', '{c}……饭饭……', '{c}... num-num...')},
        'juvenile': {
            'proud': ('{p}ぽんぽんしゃん、ぺこぺこ！ ごはん、ちょうだい！', '{p}彭彭先生饿惹！给饭饭嘛！', '{p}Mr. Tummy is hungry! Give food!'),
            'timid': ('{p}ぽんぽんしゃん……ぺこぺこ……{e}……', '{p}彭彭先生……饿……{e}……', '{p}Mr. Tummy... hungry...'),
            'plain': ('{p}ごはんしゃん、ほしい……', '{p}想要饭鲜笋……', '{p}... want Mr. Food...')},
        'adult': {
            'proud': ('{p}ぽんぽんが ぺこぺこ！ ごはん、たべる{e}！', '{p}彭彭饿扁了！要吃饭{e}！', '{p}Tummy is empty! Time to eat!'),
            'timid': ('{p}ぽんぽん……すいた……{e}……', '{p}彭彭……饿了……{e}……', '{p}Tummy... hungry...'),
            'plain': ('{p}ごはんさん、ほしい{e}……', '{p}想要饭先生{e}……', '{p}... want Mr. Food...')}},
    'fed': {
        'baby': {
            'proud': ('{c}♪ まんま、おいちい！', '{c}♪ 饭饭，好吃！', '{c}! Num-num, yummy!'),
            'timid': ('{c}……おなか、いっぱい……', '{c}……彭彭，饱饱……', '{c}... tummy... full...'),
            'plain': ('{c}♪ まんま、たべた！', '{c}♪ 饭饭，吃啦！', '{c}! Ate num-num!')},
        'juvenile': {
            'proud': ('{p}ごはんしゃん、おいちい！ しあわせ{e}！', '{p}饭鲜笋好吃！好幸福{e}！', '{p}Mr. Food is yummy! So happy!'),
            'timid': ('{p}ごはんしゃん……おなか、いっぱい……', '{p}饭鲜笋……彭彭饱了……', '{p}Mr. Food... tummy is full...'),
            'plain': ('{p}ごはんしゃん、たべた{e}。', '{p}吃了饭鲜笋{e}。', '{p}Ate Mr. Food.')},
        'adult': {
            'proud': ('{p}ごはんさん、おいしい！ しあわせ{e}！', '{p}饭先生好吃！好幸福{e}！', '{p}Mr. Food is delicious! So happy!'),
            'timid': ('{p}ごはんさん……もう いっぱい……', '{p}饭先生……已经饱了……', '{p}Mr. Food... full already...'),
            'plain': ('{p}ごはんさん、たべた{e}。', '{p}吃了饭先生{e}。', '{p}Ate Mr. Food.')}},
    'food_found': {
        'baby': {
            'proud': ('{c}！ まんま、みつけた！', '{c}！饭饭，找到啦！', '{c}! Found num-num!'),
            'timid': ('{c}……まんま……ある……', '{c}……饭饭……有……', '{c}... num-num... there...')},
        'juvenile': {
            'proud': ('{p}ごはんしゃん、みつけた{e}！', '{p}找到饭鲜笋了{e}！', '{p}Found Mr. Food!'),
            'timid': ('{p}ごはんしゃん……いた……', '{p}饭鲜笋……在……', '{p}Mr. Food... there...')},
        'adult': {
            'proud': ('{p}ごはんさん、みつけた{e}！', '{p}找到饭先生了{e}！', '{p}Found Mr. Food!'),
            'timid': ('{p}ごはんさん……いた……', '{p}饭先生……在……', '{p}Mr. Food... there...')}},
    'share': {
        'baby': {
            'proud': ('{c}♪ あげる！ どうじょ！', '{c}♪ 给你！给你！', '{c}! For you! Here!'),
            'timid': ('{c}……どーじょ……', '{c}……给你……', '{c}... here...')},
        'juvenile': {
            'proud': ('{p}はい、はんぶんこ{e}！', '{p}给！分一半{e}！', '{p}Here! Half each!'),
            'timid': ('{p}……どーじょ……たべて……', '{p}……给你……吃吧……', '{p}... here... eat...')},
        'adult': {
            'proud': ('{p}はい、どうぞ！ いっしょに たべる{e}！', '{p}给！一起吃{e}！', '{p}Here! Let us eat together!'),
            'timid': ('{p}……どうぞ……', '{p}……给你……', '{p}... here...')}},
    'peer': {
        'baby': {
            'proud': ('{c}！ {c}！ あしょぶ！', '{c}！{c}！玩玩！', '{c}! {c}! Play!'),
            'timid': ('{c}……こあい……', '{c}……好口怕……', '{c}... scawy...'),
            'plain': ('{c}？ {c}？', '{c}？{c}？', '{c}? {c}?')},
        'juvenile': {
            'proud': ('{p}お、なかましゃん！ いっしょに あしょぶ{e}！', '{p}哦，同伴鲜笋！一起玩玩{e}！', '{p}Oh, a peer! Let us play together!'),
            'timid': ('{p}なかましゃん……こあい……', '{p}同伴鲜笋……好口怕……', '{p}... peer... scawy...'),
            'plain': ('{p}あ……なかましゃん……', '{p}啊……同伴鲜笋……', '{p}Ah... a peer...')},
        'adult': {
            'proud': ('{p}おお、なかまさん！ {n}も げんき{e}！', '{p}哦哦，同伴先生！{n}也很精神{e}！', '{p}Oh, a peer! {N} is doing fine too!'),
            'timid': ('{p}なかまさん……こわくない{e}……', '{p}同伴先生……不吓人{e}……', '{p}... peer... not scary...'),
            'plain': ('{p}あ、なかまさんだ{e}。', '{p}啊，是同伴先生{e}。', '{p}Ah, it is a peer.')}},
    'known_peer': {
        'baby': {
            'proud': ('{c}♪ {c}♪ あしょぶ、あしょぶ！', '{c}♪{c}♪ 玩玩，玩玩！', '{c}! {c}! Play, play!'),
            'timid': ('{c}♪ {c}……そばに いちぇ……', '{c}♪{c}……陪陪窝……', '{c}! {c}... stay close...'),
            'plain': ('{c}♪ {c}……', '{c}♪{c}……', '{c}! {c}...')},
        'juvenile': {
            'proud': ('{p}あっ、なかましゃん！ あしょぶ、あしょぶ{e}！', '{p}啊，同伴鲜笋！玩玩、玩玩{e}！', '{p}Ah, my peer! Play, play!'),
            'timid': ('{p}なかましゃん……そばに いても いい……？', '{p}同伴鲜笋……窝可以挨着你吗……？', '{p}... peer... may I stay close...?'),
            'plain': ('{p}なかましゃん、げんき？', '{p}同伴鲜笋，精神吗？', '{p}Peer, doing okay?')},
        'adult': {
            'proud': ('{p}なかまさん！ いっしょに ゆっくりする{e}！', '{p}同伴先生！一起油库里{e}！', '{p}Peer! Let us take it easy together!'),
            'timid': ('{p}なかまさん……いてくれて、うれしい{e}……', '{p}同伴先生……有你陪着，{n}好高兴{e}……', '{p}... peer... glad you are here...'),
            'plain': ('{p}なかまさん、げんきそう{e}。', '{p}同伴先生，看起来很精神{e}。', '{p}Peer, you look well.')}},
    'addr_human_kind': {
        'baby': {
            'proud': ('{c}！ にんげんしゃ！', '{c}！人类先森！', '{c}! Mr. Human!'),
            'timid': ('{c}……にんげんしゃ……', '{c}……人类先森……', '{c}... Mr. Human...')},
        'juvenile': {
            'proud': ('{p}にんげんしゃん！ {n}も ゆっくちしゅる{e}！', '{p}人类鲜笋！{n}也要油库气{e}！', '{p}Mr. Human! {N} can take it easy too!'),
            'timid': ('{p}にんげんしゃん……こんにちは……', '{p}人类先生……你好……', '{p}Mr. Human... hello...')},
        'adult': {
            'proud': ('{p}にんげんさん！ {n}は きょうも ゆっくりできる{e}！', '{p}人类先生！{n}今天也能油库里{e}！', '{p}Mr. Human! {N} can take it easy today too!'),
            'timid': ('{p}にんげんさん……こんにちは……', '{p}人类先生……你好……', '{p}Mr. Human... hello...')}},
    'addr_human_owner': {
        'baby': {
            'proud': ('{c}♪ かいぬししゃ！', '{c}♪ 饲主先森！', '{c}! Owner-sir!'),
            'timid': ('{c}……かいぬししゃ……', '{c}……饲主先森……', '{c}... Owner-sir...')},
        'juvenile': {
            'proud': ('{p}かいぬししゃん！ {n}、うれちい！', '{p}饲主先生！{n}好高兴！', '{p}Mr. Owner! {N} is happy!'),
            'timid': ('{p}かいぬししゃん……だいじょぶ……？', '{p}饲主先生……没事吧……？', '{p}Mr. Owner... okay...?')},
        'adult': {
            'proud': ('{p}かいぬしさん！ {n}、うれしい{e}！', '{p}饲主先生！{n}好高兴{e}！', '{p}Mr. Owner! {N} is glad!'),
            'timid': ('{p}かいぬしさん……だいじょうぶ……？', '{p}饲主先生……没事吧……？', '{p}Mr. Owner... okay...?')}},
    'addr_mother': {
        'baby': {
            'proud': ('{c}♪ おかーしゃ！', '{c}♪ 麻麻！', '{c}! Mama!'),
            'timid': ('{c}……おかーしゃ……', '{c}……麻麻……', '{c}... Mama...')},
        'juvenile': {
            'proud': ('{p}おかーしゃん！ {n}、ここ！', '{p}妈妈！{n}在这儿！', '{p}Mama! {N} is here!'),
            'timid': ('{p}おかーしゃん……どこ……？', '{p}妈妈……在哪儿……？', '{p}Mama... where...?')},
        'adult': {
            'proud': ('{p}おかあさん！ {n}、ここ{e}！', '{p}妈妈！{n}在这儿{e}！', '{p}Mama! {N} is here!'),
            'timid': ('{p}おかあさん……{n}、ここ……', '{p}妈妈……{n}在这儿……', '{p}Mama... {N} is here...')}},
    'addr_father': {
        'baby': {
            'proud': ('{c}♪ おとーしゃ！', '{c}♪ 粑粑！', '{c}! Papa!'),
            'timid': ('{c}……おとーしゃ……', '{c}……粑粑……', '{c}... Papa...')},
        'juvenile': {
            'proud': ('{p}おとーしゃん！ {n}、ここ！', '{p}爸爸！{n}在这儿！', '{p}Papa! {N} is here!'),
            'timid': ('{p}おとーしゃん……どこ……？', '{p}爸爸……在哪儿……？', '{p}Papa... where...?')},
        'adult': {
            'proud': ('{p}おとうさん！ {n}、ここ{e}！', '{p}爸爸！{n}在这儿{e}！', '{p}Papa! {N} is here!'),
            'timid': ('{p}おとうさん……{n}、ここ……', '{p}爸爸……{n}在这儿……', '{p}Papa... {N} is here...')}},
    'addr_human_hostile_wary': {
        'baby': {
            'proud': ('{c}！ にんげんしゃ、こないで！', '{c}！人类先森，别过来！', '{c}! Human-sir, stay back!'),
            'timid': ('{c}——！ こあい……こないで……', '{c}——！好口怕……别过来……', '{c}! Scawy... stay back...')},
        'juvenile': {
            'proud': ('{p}にんげんしゃん！ こっち くんな{e}！', '{p}人类先生！不许过来{e}！', '{p}Mr. Human! Do not come here!'),
            'timid': ('{p}にんげんしゃん……こないで……{e}……', '{p}人类先生……别过来……{e}……', '{p}Mr. Human... stay back...')},
        'adult': {
            'proud': ('{p}にんげんさん、あっちに いって{e}！', '{p}人类先生，走开{e}！', '{p}Mr. Human, go away!'),
            'timid': ('{p}にんげんさん……こわい……{e}……', '{p}人类先生……好可怕……{e}……', '{p}Mr. Human... scary...')}},
    'afraid': {
        'baby': {
            'proud': ('{c}！ こあいけど、にげない！', '{c}！好口怕，但不跑！', '{c}! Scawy, but not running!'),
            'timid': ('{c}——！ こあい！', '{c}——！好口怕！', '{c}! Scawy!'),
            'plain': ('{c}……こあい……', '{c}……好口怕……', '{c}... scawy...')},
        'juvenile': {
            'proud': ('{p}こあいけど……{n}、がんばる{e}！', '{p}好口怕……但{n}要撑住{e}！', '{p}Scawy... but {N} will hold on!'),
            'timid': ('{p}こあい……{e}……', '{p}好口怕……{e}……', '{p}Scawy...'),
            'plain': ('{p}こあい……どうしよ……', '{p}好口怕……怎么办……', '{p}Scawy... what do I do...')},
        'adult': {
            'proud': ('{p}こわいけど……{n}、まけない{e}！', '{p}好可怕……但{n}不会输{e}！', '{p}Scary... but {N} will not give in!'),
            'timid': ('{p}こわい……{e}……', '{p}好可怕……{e}……', '{p}Scary...'),
            'plain': ('{p}こわい……どうしよう……', '{p}好可怕……怎么办……', '{p}Scary... what should I do...')}},
    'wounded': {
        'baby': {
            'proud': ('{c}！ いちゃいけど、がんばる！', '{c}！好痛，但窝要撑住！', '{c}! It hurts, but I will be brave!'),
            'timid': ('{c}——！ いちゃい……', '{c}——！好痛……', '{c}! It hurts...'),
            'plain': ('{c}……いちゃい……', '{c}……好痛……', '{c}... it hurts...')},
        'juvenile': {
            'proud': ('{p}いちゃいけど……{n}、まだ いける{e}！', '{p}好痛……但{n}还能撑{e}！', '{p}It hurts... but {N} can still go!'),
            'timid': ('{p}いちゃい……{e}……', '{p}好痛……{e}……', '{p}It hurts...'),
            'plain': ('{p}いちゃい……なおして……', '{p}好痛……治一治……', '{p}It hurts... fix it...')},
        'adult': {
            'proud': ('{p}いたいけど……{n}、まだ たたかえる{e}！', '{p}好痛……但{n}还能打{e}！', '{p}It hurts... but {N} can still fight!'),
            'timid': ('{p}いたい……{e}……', '{p}好痛……{e}……', '{p}It hurts...'),
            'plain': ('{p}いたい……なおしてほしい{e}……', '{p}好痛……想被治好{e}……', '{p}It hurts... I want it fixed...')}},
    'narrate_flee': {
        'juvenile': {
            'proud': ('{p}にげる！ {n}、はやい{e}！', '{p}要跑！{n}跑得快{e}！', '{p}Running! {N} is fast!'),
            'timid': ('{p}にげなきゃ……{e}……', '{p}得跑了……{e}……', '{p}Have to run...')},
        'adult': {
            'proud': ('{p}にげる！ {n}、まけない{e}！', '{p}要跑！{n}不会输{e}！', '{p}Running! {N} will not lose!'),
            'timid': ('{p}にげなきゃ……{e}……', '{p}得跑了……{e}……', '{p}Have to run...')}},
    'narrate_hide': {
        'juvenile': {
            'proud': ('{p}みつからない！ {n}、かくれじょうず{e}！', '{p}没被发现！{n}躲得好{e}！', '{p}Not found! {N} hides well!'),
            'timid': ('{p}みつかってない……まだ……', '{p}没发现……还没……', '{p}Not seen... not yet...')},
        'adult': {
            'proud': ('{p}みつからない！ {n}、かくれじょうず{e}！', '{p}没被发现！{n}躲得好{e}！', '{p}Not found! {N} hides well!'),
            'timid': ('{p}みつかってない……まだ……', '{p}没发现……还没……', '{p}Not seen... not yet...')}},
}

# --- emotion chain and naming (docs/FEATURES.md; mind/MindLife, mind/Naming) ----------------------------
# komicolle 40720 11518890: unable to take it easy the filling sweetens; past the limit it goes mad and can only
# scream "yukkuchi" (油庫己); 11518829: a given name denies "Reimu is Reimu". tier -> event -> (jp, zh, en)
EMOTION_BASIS = 'komicolle 40720 11518890 (sweetening, non-yukkuri) / 11518829 (naming denies identity); project-original wording, unvoiced'
EMOTION = {
    'baby': {
        'uneasy': ('{c}……ゆっくち、できにゃい……', '{c}……不能油库里……', "{c}... can't take it easy..."),
        'non_yukkuri': ('ゆっくち！ ゆっくち！ ゆっくちぃぃ！', '油库己！油库己！油库己——！', 'Yukkuchi! Yukkuchi! Yukkuchiii!'),
        'renamed_upset': ('{nb}は{nb}ぢゃよぉぉ！ {c}ーっ！', '{nb}就是{nb}啦——！{c}——！', '{nb} is {nb}! {c}!'),
    },
    'juvenile': {
        'uneasy': ('{p}{n}、ゆっくりできない{e}……', '{p}{n}没法油库里{e}……', "{p}{N} can't take it easy..."),
        'non_yukkuri': ('ゆっくち……ゆっくち……ゆっくちぃぃぃ！', '油库己……油库己……油库己——！', 'Yukkuchi... yukkuchi... yukkuchiii!'),
        'renamed_upset': ('{p}{n}は{n}{e}！ そんななまえじゃない{e}！', '{p}{n}就是{n}{e}！才不是那种名字{e}！', "{p}{N} is {N}! That's not my name!"),
    },
    'adult': {
        'uneasy': ('{p}{n}、ぜんぜんゆっくりできない{e}……', '{p}{n}一点都没法油库里{e}……', "{p}{N} can't take it easy at all..."),
        'non_yukkuri': ('ゆっくち！ ゆっくち！ ゆっ、ゆっ、ゆっくちぃぃ！', '油库己！油库己！油、油、油库己——！', 'Yukkuchi! Yukkuchi! Yu-yu-yukkuchiii!'),
        'renamed_upset': ('{p}{n}は{n}{e}！ ゆぅぅ……{n}{e}！', '{p}{n}就是{n}{e}！油呜……{n}{e}！',
                          "{p}{N} is {N}! Yuu... {N}!"),
    },
}

# L36 lists 「おそらをとんでるみたい」 as the pleased sensation of being lifted or tossed. These are unvoiced
# subtitles: the source supplies the phrase, while tier wording follows the project's existing speech rules.
AIRBORNE_BASIS = 'wiki/hakkei-reference/dialogue/common-phrases.md:31 (「おそらをとんでるみたい」; source L36 describes the lifted/tossed sensation); project-original tier wording, unvoiced'
AIRBORNE = {
    'baby': ('{c}！ おそらをとんでるみたい！', '{c}！好像在天上飞！', '{c}! It feels like I am flying in the sky!'),
    'juvenile': ('{p}{n}、おそらをとんでるみたい{e}！', '{p}{n}好像在天上飞{e}！', '{p}{N} feels like flying in the sky!'),
    'adult': ('{p}{n}、おそらをとんでるみたい{e}！', '{p}{n}好像在天上飞{e}！', '{p}{N} feels like flying in the sky!'),
}

# --- settlement and headwear events (hunt, rations, rules, famine, migration, torn / mended accessories, hatless scorn) ---
# Chief and hunt calls are adult / juvenile only; hungry babies only get the plain beats (ration, scolding, torn ornament).
# event -> tier -> (jp, zh, en); {p} prefix, {n} self-name, {e} ending, {N} English name, {c} babble cry
SETTLE_BASIS = ('komicolle 40720 11518764/11518780 speech rules (third-person self-name, "-san" nouns, count 1-2-3 then "lots"); '
                'settlement and headwear events of this project; project-original wording, unvoiced')
SETTLE = {
    'hunt_call': {
        'adult': ("{p}みんな、こっち{e}！ ごはんさん、さがす{e}！", "{p}大家，这边{e}！找饭先生去{e}！", "{p}Everyone, over here! Let's find Mr. Food!")},
    'hunt_join': {
        'juvenile': ('{p}{n}もいく{e}！ みんなに ついていく{e}！', '{p}{n}也去{e}！窝跟着大家走{e}！', '{p}{N} will go too! I will follow everyone!'),
        'adult': ('{p}わかった{e}。{n}もてつだう{e}。', '{p}知道了{e}。{n}也来帮忙{e}。', '{p}Got it. {N} will help too.')},
    'hunt_refuse': {
        'juvenile': ('{p}いやだ{e}！ {n}はいかない！', '{p}不要{e}！{n}不去！', '{p}No! {N} is not going!'),
        'adult': ('{p}{n}はいかない{e}。きょうはゆっくりしたい{e}。', '{p}{n}不去{e}。今天想油库里{e}。', '{p}{N} is not going. I want to take it easy today.')},
    'hunt_back': {
        'adult': ("{p}ただいま{e}！ ごはんさん、もってきた{e}！", "{p}到家了{e}！饭先生带回来了！", "{p}I'm back! I brought Mr. Food!")},
    'ration_give': {
        'adult': ("{p}ごはんさん！ みんな、わけて たべてね！", "{p}饭先生！大家分着嚼嚼{e}！", "{p}Mr. Food! Share it, everyone!")},
    'ration_get': {
        'baby': ("{c}♪ まんま、もらった！ ありがちょ！", "{c}♪ 饭饭！蟹蟹！", "{c}! Food! Fank you!"),
        'juvenile': ('{p}ごはんしゃん、もらった{e}！ ありがちょ！', '{p}拿到饭鲜笋了{e}！蟹蟹！', '{p}Got Mishter Food! Fank you!'),
        'adult': ('{p}ごはんさん、もらった{e}！ ありがとう！', '{p}领到饭先生了{e}！谢谢！', '{p}I got my portion! Thank you!')},
    'rule_blocked': {
        'baby': ("{c}……おちびちゃ、まだ だめ？", "{c}……还噗能生小贝比酱？", "{c}... can't have little ones yet?"),
        'juvenile': ('{p}まだ おちびちゃん、うんじゃ だめ{e}……？', '{p}还噗能生小贝比酱{e}……？', "{p}Can't I have little ones yet...?"),
        'adult': ("{p}おちびちゃん……まだ うんじゃ だめ{e}……", "{p}小贝比酱……现在还不能生{e}……", "{p}Little ones... I can't have any yet...")},
    'famine': {
        'adult': ("{p}ごはんさん、たりない{e}……ゆっくりできない{e}……", "{p}饭先生不够了{e}……没法油库里{e}……", "{p}There isn't enough food... we can't take it easy...")},
    'migrate_discuss': {
        'adult': ("{p}ここ、ゆっくりできない{e}……べつの おうち、さがす{e}？", "{p}这里不能油库里了{e}……找别的家先生{e}？", "{p}We can't take it easy here... look for another home?")},
    'migrate_object': {
        'juvenile': ('{p}おうちしゃん！ ここが いい{e}！', '{p}家鲜笋！窝就要待介里{e}！', '{p}Mr. Home! I want to stay here!'),
        'adult': ('{p}おうちさん……はなれたくない{e}！', '{p}家先生……不想走{e}！', "{p}Mr. Home... I don't want to leave!")},
    'migrate_agree': {
        'juvenile': ('{p}わかった{e}。みんなといっしょにいく{e}。', '{p}知道了{e}。和大家一起去{e}。', '{p}Okay. I will go with everyone.'),
        'adult': ("{p}わかった{e}……みんなと いく{e}。", "{p}知道了{e}……一起走{e}。", "{p}Okay... I'll go with everyone.")},
    'migrate_pack': {
        'baby': ('{c}ーっ……おうち、ばいばい……', '{c}——……家家，拜拜……', '{c}... bye-bye, home...'),
        'juvenile': ("{p}おうちしゃん……ばいばい{e}……", "{p}家鲜笋……拜拜{e}……", "{p}Bye-bye, Mr. Home..."),
        'adult': ("{p}もう いく{e}……おうちさん、さよなら……", "{p}要走了{e}……家先生，再见……", "{p}Time to go... goodbye, Mr. Home...")},
    'accessory_torn': {
        'baby': ('{c}ーっ！ おかじゃり、びりびり……', '{c}——！饰饰，破破……', '{c}! Hair thing, rip-rip...'),
        'juvenile': ('{p}かざりさんが、やぶれた{e}……！', '{p}头饰先生破掉了{e}……！', '{p}Mr. Hair Thing got torn...!'),
        'adult': ("{p}かざりさんが……びりびり{e}……", "{p}头饰先生……破了{e}……", "{p}Mr. Ornament... torn...")},
    'shard_gathered': {
        'baby': ('{c}♪ かけりゃ、あった！', '{c}♪ 碎碎，找到啦！', '{c}! Found a bit!'),
        'juvenile': ('{p}かけらしゃん、みつけた{e}！', '{p}碎片鲜笋！找到了{e}！', '{p}Found Mr. Piece!'),
        'adult': ('{p}かけらさん……みつけた{e}……', '{p}碎片先生……找到了{e}……', '{p}Mr. Piece... found you...')},
    'accessory_mended': {
        'baby': ('{c}♪ おかじゃり、なおった！', '{c}♪ 饰饰，好啦！', '{c}! Hair thing, all fixed!'),
        'juvenile': ('{p}かざりさん、なおった{e}！ おかえり！', '{p}头饰先生修好了{e}！欢迎回来！', '{p}Mr. Hair Thing is fixed! Welcome back!'),
        'adult': ("{p}かざりさん！ なおった{e}！", "{p}头饰先生！修好了{e}！", "{p}Mr. Ornament! You're fixed!")},
}
# hatless scorn / monster for the species that had no line yet
HATLESS = {
    'hatless_scorn': {
        'adult': ('{p}かざりも ないなんて、ゆっくりじゃない{e}！', '{p}没有头饰的家伙，才不是油库里{e}！', '{p}Someone with no headwear is no yukkuri!')},
    'hatless_monster': {
        'adult': ('{p}かざりのないかいぶつ{e}……！ ちかづかないで{e}！', '{p}没有头饰的怪物{e}……！别过来{e}！', '{p}A monster with no hair thing...! Stay away!')},
}
HATLESS_SPECIES = ('myon.miyo', 'myon.kinbao', 'remilia', 'flandre')

PROTECTION_BASIS = 'Project design: staged parental predator diversion, 2026-10-22 user request; unvoiced.'
PROTECTION_TAUNT = ('ぷくー！こっちだよ！おちびちゃんに さわらないでね！', '噗咕——！看这边！不许碰小贝比酱！',
                    'Puff! Over here! Leave the little one alone!')
PROTECTION_CHILD = {
    'protect_child_worry': {
        'baby': ('まっちぇ！ いっちょに にげてぇ！', '等等窝！一挤跑！', 'Wait! Wun with me!'),
        'juvenile': ('まっちぇ！ いっちょに にげよ！', '等等！一挤跑呀！', 'Wait! Run with me!'),
        'adult': ('まって！ いっしょに にげよう！', '等等！一起跑呀！', 'Wait! Run with me!'),
    },
    'protect_child_selfish': {
        'baby': ('わりゅいの、おやしゃ おっかけてる！ にげりゅ！', '坏蛋追亲亲油惹！窝跑！', 'Meanie chasing my parent! I run!'),
        'juvenile': ('わるいの、おやしゃを おっかけてる！ わたち、さきに にげる！', '坏蛋去追亲亲油惹！窝先跑！', "The meanie's chasing my parent! I'm running first!"),
        'adult': ('わるいやつは おやを おいかけてる！ さきに にげるよ！', '坏蛋追亲油去了！先逃啦！', "The bully's after my parent! I'm getting out first!"),
    },
    'protect_child_flee': {
        'baby': ('こあい、ゆわぁ！', '好口怕，呜哇！', 'Scawy, waaah!'),
        'juvenile': ('ゆわぁ！ にげるよ！', '呜哇！得跑了！', 'Waaah! Have to run!'),
        'adult': ('こわいよー！ にげるよー！', '好可怕！要逃走啦——！', 'Scary! I am running away!'),
    },
}
PROTECTION_ANGERED = ('うー！おまえを つかまえるよ！', '呜——！要抓住你！', 'Uu! I will catch you!')

MANY = {'baby': ('いっぱい', '好多好多', 'lots'), 'juvenile': ('いっぱい、いっぱい', '好多好多', 'lots and lots'),
        'adult': ('たくさん、たくさん', '好多好多', 'lots and lots')}


def sequence(base, bucket, tier, lisp):
    """The counted phrase for one bucket: 1..cap spelled out, then the 'lots' word when the count overflows."""
    person = base in ('count_children', 'count_peers')
    table = (PERSON if person else THING)['jp_baby' if lisp else 'jp']
    number = ORDINARY_CAP if bucket == 'many' else int(bucket)
    # lisping tiers only know the first three words; the rest fall back to the plain table
    jp = [table[i] if i < len(table) else (PERSON if person else THING)['jp'][i] for i in range(number)]
    zh = [f'{i + 1}个' for i in range(number)]
    en = EN_NUM[:number]
    return jp, zh, en


def counted(base, bucket, tier, species):
    lisp = tier == 'baby'
    cap = BABY_CAP if tier == 'baby' else PATCHOULI_CAP if species == 'patchouli' else ORDINARY_CAP
    if bucket == 'many':
        number = cap
        jp, zh, en = sequence(base, str(number), tier, lisp)
        many = MANY[tier]
        jp = jp + [many[0]]
        zh = zh + [many[1]]
        en = en + [many[2]]
    else:
        jp, zh, en = sequence(base, bucket, tier, lisp)
    return '、'.join(jp), '、'.join(zh), ', '.join(en)


def buckets(tier, species):
    cap = BABY_CAP if tier == 'baby' else PATCHOULI_CAP if species == 'patchouli' else ORDINARY_CAP
    return [str(i) for i in range(1, cap + 1)] + ['many']


def make(key, tier, event, frame, ctx, lines, basis=None, *, prefix='s_', variant=None):
    species, _, quirk = key.partition('.')
    ident = f'{key}.{tier}.{prefix}{event}'
    jp = frame[0].format(**ctx['jp'])
    zh = frame[1].format(**ctx['zh'])
    en = frame[2].format(**ctx['en'])
    line = {'id': ident, 'species': species, 'age': tier, **({'quirk': quirk} if quirk else {}),
            'events': [event], 'japanese': jp, 'chinese': zh, 'english': en, 'origin': 'project-original',
            'basis': basis or 'komicolle 40720 11518764/11518780/11518905 speech rules; project-original wording, unvoiced'}
    if variant:
        line['variant'] = variant
    lines.append(line)


def context(sp, tier, term=None, seq=None):
    d = SPECIES[sp]
    nm = d['name']['baby' if tier == 'baby' else tier]
    cry = d['cry']
    pre = d['pre']
    end = d['end'].get(tier, ('', ''))
    ctx = {'jp': dict(c=cry[0], nb=nm[0], n=nm[0], p=pre[0], e=end[0], N=d['en']),
           'zh': dict(c=cry[1], nb=nm[1], n=nm[1], p=pre[1], e=end[1], N=d['en']),
           'en': dict(c=cry[2], nb=nm[0], n=nm[0], p=pre[2], e='', N=d['en'])}
    ctx['en']['nb'] = d['en']
    ctx['en']['n'] = d['en']
    if term:
        ctx['jp']['t'], ctx['zh']['t'], ctx['en']['t'] = term
    if seq:
        ctx['jp']['S'], ctx['zh']['S'], ctx['en']['S'] = seq
    return ctx


def main():
    lines = []
    for sp in SPECIES:
        for event, frame in BABY.items():
            make(sp, 'baby', event, frame, context(sp, 'baby'), lines, DEATH_BASIS if event in ('dying_kin', 'kin_dying', 'stench', 'offer_refuse', 'offer_eat', 'offer_grief') else None)
        for base, frame in BABY_COUNT.items():
            for b in buckets('baby', sp.partition('.')[0]):
                make(sp, 'baby', f'{base}_{b}', frame, context(sp, 'baby', seq=counted(base, b, 'baby', sp.partition('.')[0])), lines)
        for tier in ('juvenile', 'adult'):
            if tier == 'juvenile':
                for event, frame in JUVENILE.items():
                    make(sp, tier, event, frame, context(sp, tier), lines)
            for event, frame in BODY.items():
                make(sp, tier, event, frame, context(sp, tier), lines)
            for event, frame in DEATH.items():
                if tier == 'juvenile' and event == 'offer_grief':
                    frame = JUVENILE_OFFER_GRIEF
                make(sp, tier, event, frame, context(sp, tier), lines, DEATH_BASIS)
            if tier == 'adult':
                for event, frame in DEATH_ADULT.items():
                    make(sp, tier, event, frame, context(sp, tier), lines, DEATH_BASIS)
            for event, frame in ADDRESS.items():
                if tier == 'juvenile' and event == 'narrate_hide':
                    frame = JUVENILE_HIDE
                if tier == 'juvenile' and event == 'narrate_steal':
                    frame = JUVENILE_STEAL
                term = TERMS[tier].get(event) or COMMON_TERMS.get(event)
                make(sp, tier, event, frame, context(sp, tier, term=term), lines)
            for base, frame in COUNT_FRAMES.items():
                for b in buckets(tier, sp.partition('.')[0]):
                    make(sp, tier, f'{base}_{b}', frame, context(sp, tier, seq=counted(base, b, tier, sp.partition('.')[0])), lines)
    for sp in SPECIES:
        for event, tiers in STYLED.items():
            for tier, variants in tiers.items():
                for i, (variant, frame) in enumerate(variants.items(), 1):
                    make(sp, tier, event, frame, context(sp, tier), lines, prefix=f'v{i}_', variant=variant)
    for sp in SPECIES:
        for tier, events in EMOTION.items():
            for event, frame in events.items():
                make(sp, tier, event, frame, context(sp, tier), lines, EMOTION_BASIS)
        for tier, frame in AIRBORNE.items():
            make(sp, tier, 'airborne', frame, context(sp, tier), lines, AIRBORNE_BASIS)
    for sp in SPECIES:
        for event, tiers in SETTLE.items():
            for tier, frame in tiers.items():
                make(sp, tier, event, frame, context(sp, tier), lines, SETTLE_BASIS)
    for sp in HATLESS_SPECIES:
        for event, tiers in HATLESS.items():
            for tier, frame in tiers.items():
                make(sp, tier, event, frame, context(sp, tier), lines, SETTLE_BASIS)
    # Myon's full library (scripts/myon_speech_lines.py): {t} is the individual's own tic, {c} the baby babble cry.
    for quirk, tic in MYON_TICS.items():
        key = 'myon.' + quirk
        cry = SPECIES[key]['cry']
        for event, tiers in {**MYON_LINES, **MYON_NEW}.items():
            for tier, frame in tiers.items():
                ctx = {lang: dict(t=tic[i], c=cry[i]) for i, lang in enumerate(('jp', 'zh', 'en'))}
                make(key, tier, event, frame, ctx, lines, MYON_BASIS)
    for event, tiers in KOTATSU.items():
        for tier, frame in tiers.items():
            make('marisa', tier, event, frame, {lang: {} for lang in ('jp', 'zh', 'en')}, lines, KOTATSU_BASIS)
    protection_context = {lang: {} for lang in ('jp', 'zh', 'en')}
    for sp in ('reimu', 'marisa', 'alice', 'chen', 'patchouli', 'myon'):
        make(sp, 'adult', 'protect_taunt', PROTECTION_TAUNT, protection_context, lines, PROTECTION_BASIS, prefix='retest_')
        for event, tiers in PROTECTION_CHILD.items():
            for tier, frame in tiers.items():
                make(sp, tier, event, frame, protection_context, lines, PROTECTION_BASIS, prefix='retest_')
    for sp in ('remilia', 'flandre'):
        make(sp, 'adult', 'protect_angered', PROTECTION_ANGERED, protection_context, lines, PROTECTION_BASIS, prefix='retest_')
    ids = [l['id'] for l in lines]
    assert len(ids) == len(set(ids))
    doc = {'origin': 'project-original', 'status': 'Unvoiced project-original subtitles; wording awaits listening review.',
           'tiers': {'baby': 'Stage.BABY', 'juvenile': 'Stage.JUVENILE', 'adult': 'Stage.ADULT',
                     'young': 'legacy childish tier (kept; fallback for baby/juvenile and for childish-voiced adults)'},
           'counting': {'ordinary_cap': ORDINARY_CAP, 'baby_cap': BABY_CAP, 'patchouli_cap': PATCHOULI_CAP, 'bases': list(COUNT_BASES)},
           'lines': lines}
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + '\n', encoding='utf-8', newline='\n')
    print(len(lines), 'lines')


if __name__ == '__main__':
    main()

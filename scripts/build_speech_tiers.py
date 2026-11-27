"""Generate art/dialogue/speech-tiers.json: baby / juvenile / adult speech tiers, forms of address,
"counting stops at four" lines and the two Myon verbal tics.

Source of the worldview rules: komicolle 40720 floors 11518764 (baby babble, forms of address, "-sensei"
suffix), 11518780 (narrating own actions, counting collapses to "lots" at 4, vocabulary inherited at birth)
and 11518905 (Myon has two mutually exclusive tics, Patchouli may count past 4). Every line is a
project-original composition ('origin': 'project-original'); none is a Hakkei U line.
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
    'hunt_sweet': ('{p}かざりが ない{e}……でも、おなか すいた{e}……', '{p}没有头饰{e}……可彭彭好饿{e}……', '{p}No ornament... but I am so hungry...'),
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
FOOD_BASIS = 'komicolle 40720 11518764/11518780/11518905 speech rules; FG-03 graded eating reaction; project-original wording, voiced'
FOOD_STYLED = {
    'baby': {
        'proud': {
            'food_delight': ('{c}！ まんま、おいち！ もっと！', '{c}！饭饭好吃！还要、还要！', '{c}! Num-num yummy! More, more!'),
            'food_grudge': ('{c}！ まじゅまじゅ！ むーちゃ！', '{c}！难吃吃！嚼嚼！', '{c}! Yucky! Munch-munch!'),
            'food_reluctant': ('{c}……ぽんぽんしゃん、ぺこぺこ……', '{c}……彭彭先森饿惹……', '{c}... Mr. Tummy... so hungry...'),
            'food_spit': ('{c}！ いや！ ぺっ、ぺっ！', '{c}！不要！呸、呸！', '{c}! No! Ptooey, ptooey!'),
            'food_complain': ('{c}！ まんましゃん、やー！', '{c}！饭饭{{mister}}，不要哒！', '{c}! No, Mr. Num-num!'),
        },
        'timid': {
            'food_delight': ('{c}……まんま、おいち……', '{c}……饭饭好吃……', '{c}... Num-num is yummy...'),
            'food_grudge': ('{c}……まじゅ……むちゃ……', '{c}……难吃吃……嚼嚼……', '{c}... Yucky... munch...'),
            'food_reluctant': ('{c}……ひっく……おなか、ぺこ……', '{c}……呜呜……彭彭饿……', '{c}... Hic... tummy hungry...'),
            'food_spit': ('{c}……ぺっ……いや……', '{c}……呸……不要……', '{c}... Ptooey... no...'),
            'food_complain': ('{c}……こあい……やぁ……', '{c}……口怕……不要哒……', '{c}... Scawy... no...'),
        },
        'plain': {
            'food_delight': ('{c}、まんま、おいちい。', '{c}，饭饭好吃。', '{c}, num-num yummy.'),
            'food_grudge': ('{c}、むーちゃ。まじゅい。', '{c}，嚼嚼。难吃吃。', '{c}, munch-munch. Yucky.'),
            'food_reluctant': ('{c}……ぺこぺこ……むーちゃ……', '{c}……饿饿……嚼嚼……', '{c}... Hungry... munch-munch...'),
            'food_spit': ('{c}、ぺっ。いや。', '{c}，呸。不要。', '{c}, ptooey. No.'),
            'food_complain': ('{c}、いらにゃい。', '{c}，窝不要。', '{c}, no want.'),
        },
    },
    'juvenile': {
        'proud': {
            'food_delight': ('{p}ごはんしゃん、さいこう！ もっと たべたい{e}！', '{p}饭先生最棒！还想吃{e}！', '{p}Mr. Food is the best! I want more!'),
            'food_grudge': ('{p}まじゅいけど、{n}は むーちゃできる{e}！', '{p}难吃归难吃，{{self}}还是能嚼嚼{e}！', '{p}Yucky, but {N} can munch it!'),
            'food_reluctant': ('{p}ぽんぽんしゃん、ぺこぺこ{e}……むーちゃ……', '{p}彭彭先生饿扁惹{e}……嚼嚼……', '{p}Mr. Tummy is empty... munch-munch...'),
            'food_spit': ('{p}ぺっ、ぺっ！ まずい、いらない{e}！', '{p}呸、呸！难吃，不要{e}！', '{p}Ptooey, ptooey! Yucky! No!'),
            'food_complain': ('{p}こんな ごはんしゃん、いらない{e}！', '{p}这种饭先生不要{e}！', '{p}I do not want this Mr. Food!'),
        },
        'timid': {
            'food_delight': ('{p}ごはんしゃん、おいちい……しあわせ{e}……', '{p}饭先生好吃……好幸胡{e}……', '{p}Mr. Food is yummy... so happy...'),
            'food_grudge': ('{p}ごはんしゃん、まずい……でも がまん{e}……', '{p}饭先生难吃……忍一忍{e}……', '{p}Mr. Food is yucky... I can bear it...'),
            'food_reluctant': ('{p}ぺこぺこ……なみだ、ぽろぽろ……むーちゃ……', '{p}彭彭饿……眼泪啪嗒啪嗒……嚼嚼……', '{p}Tummy so hungry... drip, drip... munch-munch...'),
            'food_spit': ('{p}ぺっ……ごめん……いや{e}……', '{p}呸……对不起……不要{e}……', '{p}Ptooey... sorry... no...'),
            'food_complain': ('{p}ごめん……これ、むり{e}……', '{p}对不起……窝吃不下{e}……', '{p}Sorry... I cannot eat this...'),
        },
        'plain': {
            'food_delight': ('{p}ごはんしゃん、おいちい{e}。', '{p}饭先生好吃{e}。', '{p}Mr. Food is yummy.'),
            'food_grudge': ('{p}ごはんしゃん、まずい{e}。たべる。', '{p}饭先生难吃{e}。还是嚼嚼。', '{p}Mr. Food is yucky. Munch anyway.'),
            'food_reluctant': ('{p}おなか ぺこぺこ……まずいけど たべる{e}……', '{p}彭彭饿……难吃也吃{e}……', '{p}My tummy is hungry... yucky, but I will eat...'),
            'food_spit': ('{p}ぺっ、ぺっ。まずい{e}。いらない。', '{p}呸、呸。难吃{e}。不要。', '{p}Ptooey, ptooey. Yucky. No.'),
            'food_complain': ('{p}これ、いらない{e}。べつのが いい。', '{p}介个不要{e}。想要别的。', '{p}No to this. I want another.'),
        },
    },
    'adult': {
        'proud': {
            'food_delight': ('{p}{n}は しあわせ{e}！', '{p}{{self}}好幸福{e}！', '{p}{N} is so happy!'),
            'food_grudge': ('{p}ふん、こんな ごはんさん……たべてやる{e}。', '{p}哼，这种饭先生……也嚼得下去{e}。', '{p}Hmph, this Mr. Food... I can eat it anyway.'),
            'food_reluctant': ('{p}いやだが、たおれる わけには いかない。たべる{e}。', '{p}虽然不想要，可不能倒下。吃{e}。', '{p}I do not want it, but I cannot collapse. I will eat.'),
            'food_spit': ('{p}ぺっ！ まずい ごはんさん、ゆるさない{e}！', '{p}呸！难吃的饭先生，不原谅{e}！', '{p}Ptooey! Yucky Mr. Food, I will not forgive it!'),
            'food_complain': ('{p}ふん、こんな ごはんさん、いらない{e}。', '{p}哼，这种饭先生不要{e}。', '{p}Hmph, I do not want this Mr. Food.'),
        },
        'timid': {
            'food_delight': ('{p}おいしい……しあわせ{e}……', '{p}好吃……好幸福{e}……', '{p}It is tasty... I am happy...'),
            'food_grudge': ('{p}まずいけど……すてるのは もったいない{e}……', '{p}难吃……可是扔掉太浪费{e}……', '{p}It is yucky... but throwing it away is wasteful...'),
            'food_reluctant': ('{p}いや……ぺこぺこ……なみだ でる{e}……', '{p}不要……彭彭饿……眼泪掉下来了{e}……', '{p}No... my tummy is hungry... here come the tears...'),
            'food_spit': ('{p}ぺっ……ごめん……たべられない{e}……', '{p}呸……对不起……吃不下{e}……', '{p}Ptooey... sorry... I cannot eat it...'),
            'food_complain': ('{p}ごめん……これ、むり{e}……べつの おねがい。', '{p}对不起……这个吃不下{e}……换一个吧。', '{p}Sorry... I cannot manage this... another, please.'),
        },
        'plain': {
            'food_delight': ('{p}ごはんさん、おいしい{e}。', '{p}饭先生好吃{e}。', '{p}Mr. Food is tasty.'),
            'food_grudge': ('{p}ごはんさん、まずい{e}。でも たべる。', '{p}饭先生难吃{e}。不过还是吃。', '{p}Mr. Food is yucky. But I will eat.'),
            'food_reluctant': ('{p}まずい……でも おなかが すいた{e}。', '{p}难吃……可是彭彭饿了{e}。', '{p}Yucky... but my tummy is hungry.'),
            'food_spit': ('{p}ぺっ、ぺっ。まずい{e}。いらない。', '{p}呸、呸。难吃{e}。不要。', '{p}Ptooey, ptooey. Yucky. No.'),
            'food_complain': ('{p}この ごはんさんは いらない{e}。べつのが ほしい。', '{p}这个饭先生不要{e}。想要别的。', '{p}I do not want this Mr. Food. I want another.'),
        },
    },
}

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
# The taunt a parent of any age and species calls from beside its young. Adults of the five non-tic species keep the line above.
PROTECTION_TAUNT_TIERS = {
    'baby': ('{p}ぷくー！ こっちー！ ちびちゃんに ちゃわらにゃいで！', '{p}噗咕——！这边！不许碰小贝比！', '{p}Puff! Over here! Do not touch the little one!'),
    'juvenile': ('{p}ぷくー！ こっちらよ！ おちびちゃんに ちゃわらにゃいで！', '{p}噗咕——！看这边！不许碰小贝比酱！',
                 '{p}Puff! Over here! Do not touch the little one!'),
    'adult': ('{p}ぷくー！こっちだよ！おちびちゃんに さわらないでね！', '{p}噗咕——！看这边！不许碰小贝比酱！',
              '{p}Puff! Over here! Leave the little one alone!'),
}

# Reaction pools that had no line: the response was silent. Each is a style-tagged triple per tier (NativeDialogue picks the tag that
# matches the speaker's current style). Wording stays inside what the body knows: no tool, shop or factory names, no human-only
# concepts; the speaker names only what it sees or feels.
GAP_BASIS = 'wp-gap-lines: reaction pool with no line before; project-original wording, voiced'
GAP = {
    # A struck animal with no keeper it trusts asks the one who hit it to stop.
    'hurt_plea': {
        'baby': {
            'proud': ('{c}！ いちゃい！ やめちぇ！', '{c}！好痛！别打！', '{c}! It hurts! Stop!'),
            'timid': ('{c}……いちゃい……やめちぇ……', '{c}……好痛……别打……', '{c}... it hurts... stop...'),
            'plain': ('{c}……いちゃいの、やーよ……', '{c}……痛痛，不要……', '{c}... no more ouchies...')},
        'juvenile': {
            'proud': ('{p}いちゃいよ！ もう やめちぇ！', '{p}好痛！别打了！', '{p}It hurts! Stop it!'),
            'timid': ('{p}いちゃい……やめちぇ……', '{p}好痛……别打了……', '{p}It hurts... stop...'),
            'plain': ('{p}いちゃいの……もう いやぁ……', '{p}好痛……不要了……', '{p}It hurts... no more...')},
        'adult': {
            'proud': ('{p}いたいよ！ もう やめて！', '{p}好痛！别打了！', '{p}It hurts! Stop it!'),
            'timid': ('{p}いたい……やめて……', '{p}好痛……别打了……', '{p}It hurts... stop...'),
            'plain': ('{p}いたいの、もう いや……やめてほしい{e}……', '{p}好痛，不要了……想让你停下{e}……', '{p}It hurts, no more... please stop...')}},
    # Pleading before any injury (a threat, restraint or a blow that has not landed yet).
    'plea_spare': {
        'baby': {
            'proud': ('{c}！ いじめにゃいで！', '{c}！不要欺负窝！', '{c}! No bullying!'),
            'timid': ('{c}……こあい……ぶたにゃいで……', '{c}……好口怕……别打窝……', '{c}... scawy... do not hit...'),
            'plain': ('{c}……やーの……やーの……', '{c}……不要……不要……', '{c}... no... no...')},
        'juvenile': {
            'proud': ('{p}ぶたにゃいで！ {n}、いいこ{e}！', '{p}别打{n}！{n}是乖孩子{e}！', "{p}Don't hit me! {N} is good!"),
            'timid': ('{p}こあい……ぶたにゃいで……', '{p}好口怕……别打……', "{p}Scawy... don't hit me..."),
            'plain': ('{p}おねがい……いじめにゃいで……', '{p}求求你……别欺负窝……', "{p}Please... don't be mean...")},
        'adult': {
            'proud': ('{p}ひどいことは やめて！ {n}は わるくない{e}！', '{p}别做过分的事！{n}没有做错{e}！', '{p}Stop that! {N} did nothing wrong!'),
            'timid': ('{p}こわい……ひどいこと、しないで……', '{p}好可怕……别做过分的事……', "{p}Scary... please don't hurt me..."),
            'plain': ('{p}おねがい……ゆるしてほしい{e}……', '{p}拜托……想被饶过{e}……', '{p}Please... let me off...')}},
    # Handed over to a stranger and set down on the floor: asks not to be taken away (a defiant one has the factory insult).
    'handover_plea': {
        'baby': {
            'proud': ('{c}！ おうち、かえりゅ！', '{c}！回家家！', '{c}! Go home!'),
            'timid': ('{c}……どこ いくの……こあい……', '{c}……要去哪……好口怕……', '{c}... where to... scawy...'),
            'plain': ('{c}……つれてかにゃいで……', '{c}……别带窝走……', '{c}... do not take me...')},
        'juvenile': {
            'proud': ('{p}やー！ おうちに かえりゅ{e}！', '{p}不要！窝要回家{e}！', '{p}No! I want to go home!'),
            'timid': ('{p}どこに いくの……こあい……', '{p}要去哪里……好口怕……', '{p}Where to... scawy...'),
            'plain': ('{p}つれていかにゃいで……おねがい……', '{p}别带窝走……求求你……', '{p}Do not take me away... please...')},
        'adult': {
            'proud': ('{p}いや！ おうちに かえる{e}！', '{p}不要！{n}要回家{e}！', '{p}No! {N} wants to go home!'),
            'timid': ('{p}どこへ いくの……こわい……', '{p}要去哪儿……好可怕……', '{p}Where to... scary...'),
            'plain': ('{p}つれていかないで……おねがい……', '{p}别带{n}走……拜托……', '{p}Do not take me away... please...')}},
    # A human holds something up: the animal cannot know what it is and tries to be gentle and good.
    'tool_appease': {
        'baby': {
            'proud': ('{c}♪ にんげんしゃ、にこにこ！', '{c}♪ 人类先森，笑笑！', '{c}! Mr. Human, smiley!'),
            'timid': ('{c}……やしゃしく……ちて……', '{c}……轻、轻一点……', '{c}... be gentle...'),
            'plain': ('{c}……いいこ、いいこ……', '{c}……乖乖，乖乖……', '{c}... good, good...')},
        'juvenile': {
            'proud': ('{p}にんげんしゃん、{n}、いいこ{e}！ にこにこ{e}！', '{p}人类先生，{n}是乖孩子{e}！笑笑{e}！', '{p}Mr. Human, {N} is good! Smile!'),
            'timid': ('{p}にんげんしゃん……やしゃしく ちてほちい{e}……', '{p}人类先生……想被温柔对待{e}……', '{p}Mr. Human... be gentle...'),
            'plain': ('{p}にんげんしゃん……{n}、いいこに ちゅる{e}……', '{p}人类先生……{n}会乖乖的{e}……', '{p}Mr. Human... I will be good...')},
        'adult': {
            'proud': ('{p}にんげんさん、{n}は いいこ{e}！ にこにこ{e}！', '{p}人类先生，{n}是乖孩子{e}！笑笑{e}！', '{p}Mr. Human, {N} is good! Smile!'),
            'timid': ('{p}にんげんさん……やさしく してほしい{e}……', '{p}人类先生……想被温柔对待{e}……', '{p}Mr. Human... please be gentle...'),
            'plain': ('{p}にんげんさん……{n}、いいこに する{e}……', '{p}人类先生……{n}会乖乖的{e}……', '{p}Mr. Human... I will be good...')}},
    # Scorned or bullied by a stranger it did nothing to (an own group says settlement_hurt instead).
    'scorn_hurt': {
        'baby': {
            'proud': ('{c}！ いじわりゅ！', '{c}！坏心眼！', '{c}! Meanie!'),
            'timid': ('{c}……いじわりゅ……ぐしゅ……', '{c}……坏心眼……呜……', '{c}... meanie... sniff...'),
            'plain': ('{c}……なんで……？', '{c}……为什么……？', '{c}... why...?')},
        'juvenile': {
            'proud': ('{p}ひどい{e}！ {n}、なんにも ちてない{e}！', '{p}好过分{e}！{n}什么都没做{e}！', '{p}So mean! {N} did nothing!'),
            'timid': ('{p}ひどい……{n}、なにも ちてないのに……', '{p}好过分……{n}什么都没做呀……', '{p}So mean... I did nothing...'),
            'plain': ('{p}どうちて……いじわる しゅるの……', '{p}为什么……要欺负窝……', '{p}Why... are you so mean...')},
        'adult': {
            'proud': ('{p}ひどい{e}！ {n}は なにも してない{e}！', '{p}好过分{e}！{n}什么都没做{e}！', '{p}So mean! {N} did nothing!'),
            'timid': ('{p}ひどいよ……{n}、なにもしてないのに……', '{p}好过分……{n}什么都没做呀……', '{p}So mean... I did nothing...'),
            'plain': ('{p}どうして……ひどいこと するの……', '{p}为什么……要做过分的事……', '{p}Why... do such a mean thing...')}},
    # Too weak to resist the basket, but it still says no.
    'basket_refuse_weary': {
        'baby': {
            'proud': ('{c}……ちゅかれちゃ……でも、やーの……', '{c}……累累……可是，不要……', '{c}... tired... but no...'),
            'timid': ('{c}……ふらふら……いやぁ……', '{c}……晃晃……不要啊……', '{c}... wobbly... no...'),
            'plain': ('{c}……ちゅかれちゃ……うごけにゃい……', '{c}……累累……动不了……', '{c}... tired... cannot move...')},
        'juvenile': {
            'proud': ('{p}ふらふらでも、かごしゃんは いや{e}！', '{p}就算晃晃悠悠，也不要篮子鲜笋{e}！', '{p}Wobbly, but I still say no to the basket!'),
            'timid': ('{p}ふらふら……かごしゃん、はいれにゃい……', '{p}晃晃悠悠……进不去篮子鲜笋……', '{p}Wobbly... cannot get in the basket...'),
            'plain': ('{p}ちゅかれちゃ……もう うごけにゃい……', '{p}累累……动不了了……', '{p}Tired... cannot move any more...')},
        'adult': {
            'proud': ('{p}ふらふらでも、かごさんは いや{e}！', '{p}就算晃晃悠悠，也不要篮子先生{e}！', '{p}Wobbly, but I still say no to the basket!'),
            'timid': ('{p}ふらふら……かごさん、もう むり{e}……', '{p}晃晃悠悠……篮子先生，已经不行了{e}……', '{p}Wobbly... the basket is too much...'),
            'plain': ('{p}つかれた……うごけない{e}……', '{p}累了……动不了{e}……', '{p}Tired... cannot move...')}},
    # A plain no: food it will not take, a dose, a hat it will not fill.
    'refuse': {
        'baby': {
            'proud': ('{c}！ いやー！ いやいや！', '{c}！不要！不要不要！', '{c}! No! No no!'),
            'timid': ('{c}……いや……いやぁ……', '{c}……不……不要啊……', '{c}... no... noo...'),
            'plain': ('{c}……やーの……', '{c}……不要……', '{c}... no...')},
        'juvenile': {
            'proud': ('{p}いやー{e}！ いらにゃい{e}！', '{p}不要{e}！不需要{e}！', '{p}No! I do not want it!'),
            'timid': ('{p}いや……いらにゃい……', '{p}不……不要……', '{p}No... do not want...'),
            'plain': ('{p}……やーの。いらにゃい{e}。', '{p}……不要。不需要{e}。', '{p}... no. I do not want it.')},
        'adult': {
            'proud': ('{p}いや{e}！ いらない{e}！', '{p}不要{e}！不需要{e}！', '{p}No! I do not want it!'),
            'timid': ('{p}いや……いらない……', '{p}不……不要……', '{p}No... do not want...'),
            'plain': ('{p}ううん、いらない{e}。', '{p}不，不需要{e}。', '{p}No, I do not want it.')}},
}

# Reaction events that still named a line after wp-gap-lines but had no pool at all: the face was shown and the animal
# stayed silent. Same style-tagged triples per tier; wording stays inside what the body feels or sees at that moment.
GAP_FILL_BASIS = 'wp-lines-1121: reaction pool with no line before; project-original wording, voiced'
GAP_FILL = {
    # A plain pain without an attacker to plead with (harmful food, a late mould dose, a bare-handed bash, a soiled wrap).
    'hurt': {
        'baby': {
            'proud': ('{c}！ いちゃい！', '{c}！好痛！', '{c}! Ouch!'),
            'timid': ('{c}……いちゃい……', '{c}……好痛……', '{c}... it hurts...'),
            'plain': ('{c}……いちゃいの……', '{c}……痛痛……', '{c}... ouchie...')},
        'juvenile': {
            'proud': ('{p}いちゃい{e}！', '{p}好痛{e}！', '{p}It hurts!'),
            'timid': ('{p}いちゃい……', '{p}好痛……', '{p}It hurts...'),
            'plain': ('{p}……いちゃいの、いや{e}……', '{p}……好痛，不要{e}……', '{p}... ouch, no more...')},
        'adult': {
            'proud': ('{p}いたい{e}！', '{p}好痛{e}！', '{p}It hurts!'),
            'timid': ('{p}いたい……', '{p}好痛……', '{p}It hurts...'),
            'plain': ('{p}……いたいの、いや{e}……', '{p}……好痛，不要{e}……', '{p}... it hurts, no more...')}},
    # A member of the group is captured, dead or gone: the rest cry out for it.
    'search_family': {
        'baby': {
            'proud': ('{c}！ みんな、どこー！', '{c}！大家，在哪！', '{c}! Everyone, where!'),
            'timid': ('{c}……みんな……いない……', '{c}……大家……不在……', '{c}... everyone... gone...'),
            'plain': ('{c}……どこに いるの……', '{c}……在哪儿……', '{c}... where are you...')},
        'juvenile': {
            'proud': ('{p}みんなー！ どこに いるの！', '{p}大家——！在哪儿！', '{p}Everyone! Where are you!'),
            'timid': ('{p}みんな……いない……どこ……', '{p}大家……不在……在哪……', '{p}Everyone... gone... where...'),
            'plain': ('{p}……みんな、どこに いったの{e}……', '{p}……大家，去哪儿了{e}……', '{p}... everyone, where did you go...')},
        'adult': {
            'proud': ('{p}みんな！ どこに いるの{e}！', '{p}大家！在哪儿{e}！', '{p}Everyone! Where are you!'),
            'timid': ('{p}みんな……いない……', '{p}大家……不在……', '{p}Everyone... gone...'),
            'plain': ('{p}……みんな、どこに いったの{e}……', '{p}……大家，都去哪儿了{e}……', '{p}... everyone, where have you gone...')}},
    # A prepared serving is put in its mouth: it says the food has come before it swallows.
    'receive_mash': {
        'baby': {
            'proud': ('{c}♪ まんま、きた！ まんま！', '{c}♪ 饭饭来啦！饭饭！', '{c}! Food is here! Food!'),
            'timid': ('{c}……まんま……くれるの……？', '{c}……饭饭……给窝的……？', '{c}... food... for me...?'),
            'plain': ('{c}……まんま、きた……', '{c}……饭饭，来啦……', '{c}... food came...')},
        'juvenile': {
            'proud': ('{p}ごはんしゃん、きた{e}！ たべる{e}！', '{p}饭鲜笋来啦{e}！窝要吃{e}！', '{p}Mr. Food is here! I will eat!'),
            'timid': ('{p}ごはんしゃん……くれるの{e}……？', '{p}饭鲜笋……是给窝的吗{e}……？', '{p}Mr. Food... is it for me...?'),
            'plain': ('{p}ごはんしゃん、きた{e}……', '{p}饭鲜笋来啦{e}……', '{p}Mr. Food is here...')},
        'adult': {
            'proud': ('{p}ごはんさん、きた{e}！ たべる{e}！', '{p}饭先生来啦{e}！开吃{e}！', '{p}Mr. Food is here! I am eating!'),
            'timid': ('{p}ごはんさん……くれるの{e}……？', '{p}饭先生……是给我的吗{e}……？', '{p}Mr. Food... is it for me...?'),
            'plain': ('{p}ごはんさん、きた{e}……', '{p}饭先生来啦{e}……', '{p}Mr. Food is here...')}},
    # A clap of lightning: a big sound with no attacker it can see.
    'thunder': {
        'baby': {
            'proud': ('{c}！ おおきい おと！', '{c}！好大的声音！', '{c}! Big sound!'),
            'timid': ('{c}……びっくりした……', '{c}……吓一跳……', '{c}... startled...'),
            'plain': ('{c}……こわい おと……', '{c}……可怕的声音……', '{c}... scary sound...')},
        'juvenile': {
            'proud': ('{p}おおきい おと{e}！ びっくりした{e}！', '{p}好大的声音{e}！吓一跳{e}！', '{p}A big sound! I am startled!'),
            'timid': ('{p}びっくりした……こわい……', '{p}吓一跳……好可怕……', '{p}Startled... scary...'),
            'plain': ('{p}……おおきい おと、こわい{e}……', '{p}……好大的声音，好可怕{e}……', '{p}... big sound, scary...')},
        'adult': {
            'proud': ('{p}おおきい おと{e}！ なに{e}！', '{p}好大的声音{e}！什么{e}！', '{p}A big sound! What was that!'),
            'timid': ('{p}びっくりした……こわい……', '{p}吓了一跳……好可怕……', '{p}Startled... scary...'),
            'plain': ('{p}……おおきい おとが した{e}……', '{p}……响起了好大的声音{e}……', '{p}... there was a big sound...')}},
    # The body broadens into its adult form: it feels the change happen.
    'deibu_developed': {
        'baby': {
            'proud': ('{c}！ からだ、おおきい！', '{c}！身体，好大！', '{c}! Body, big!'),
            'timid': ('{c}……からだ……おおきい……', '{c}……身体……好大……', '{c}... body... big...'),
            'plain': ('{c}……からだが、かわった……？', '{c}……身体，变了……？', '{c}... my body changed...?')},
        'juvenile': {
            'proud': ('{p}からだが おおきくなった{e}！', '{p}身体变大了{e}！', '{p}My body got bigger!'),
            'timid': ('{p}……からだが、おおきい……', '{p}……身体，好大……', '{p}... my body is big...'),
            'plain': ('{p}……なんだか、からだが ひろい{e}……', '{p}……总觉得，身体变宽了{e}……', '{p}... somehow my body feels wide...')},
        'adult': {
            'proud': ('{p}からだが おおきくなった{e}！', '{p}身体变大了{e}！', '{p}My body got bigger!'),
            'timid': ('{p}……からだが、おおきい……', '{p}……身体，好大……', '{p}... my body is big...'),
            'plain': ('{p}……なんだか、からだが ひろい{e}……', '{p}……总觉得，身体变宽了{e}……', '{p}... somehow my body feels wide...')}},
    # Washed against its will: it struggles and tells the one rubbing it to stop.
    'wash_refuse': {
        'baby': {
            'proud': ('{c}！ やめちぇ！ やめちぇ！', '{c}！别弄！别弄！', '{c}! Stop! Stop!'),
            'timid': ('{c}……やめちぇ……やだ……', '{c}……别弄……不要……', '{c}... stop... no...'),
            'plain': ('{c}……いやの……やめちぇ……', '{c}……不要……别弄……', '{c}... no... stop...')},
        'juvenile': {
            'proud': ('{p}いやだ{e}！ もう やめちぇ！', '{p}不要{e}！别弄了！', '{p}No! Stop it!'),
            'timid': ('{p}やめちぇ……いやだよぉ……', '{p}别弄……好讨厌……', '{p}Stop... I hate it...'),
            'plain': ('{p}……いや。やめちぇ。', '{p}……不要。别弄。', '{p}... no. Stop it.')},
        'adult': {
            'proud': ('{p}いや{e}！ やめて！', '{p}不要{e}！停下！', '{p}No! Stop it!'),
            'timid': ('{p}やめて……いやよ……', '{p}停下……不要……', '{p}Stop... no...'),
            'plain': ('{p}……いや。やめてほしい{e}。', '{p}……不要。想让你停下{e}。', '{p}... no. Please stop.')}},
}

RULE_AWE_BASIS = 'wp-rule-awe: a pet answers its owner (caught breaking a house rule, or told a rule changed) in words of awe; project-original wording, unvoiced'
# One neutral line per tier, short enough to fit one beat; which beat speaks it is chosen by the performance from the pet's numbers
# (Penance, RuleReaction). Every line names the owner (adult かいぬしさま, juvenile かいぬししゃま, baby lisped かいぬちしゃま;
# zh 饲主大人, a deliberate raise over the 饲主先生 of ordinary address; en Owner): a pet speaks up to it, never as to an equal.
RULE_AWE = {
    # A rule was added: the pet that does not mind says it will keep it.
    'rule_tighten': {
        'baby': ('{c}……あい……まもりゅ、かいぬちしゃま……', '{c}……好……会遵守，饲主大人……', '{c}... yes... I will obey, Owner...'),
        'juvenile': ('{p}はい……まもりゅ、かいぬししゃま。', '{p}是……会遵守，饲主大人。', '{p}Yes... I will keep it, Owner.'),
        'adult': ('{p}はい……まもります、かいぬしさま。', '{p}是……会遵守的，饲主大人。', '{p}Yes... I will keep it, Owner.')},
    # A rule was added and the pet fears its owner: it cowers and begs not to be punished.
    'rule_cower': {
        'baby': ('{c}……こあい……かいぬちしゃま、ごみぇんなちゃい……', '{c}……好口怕……饲主大人，对不起……', '{c}... scawy... sowwy, Owner...'),
        'juvenile': ('{p}ひっ……まもりゅ……おこらないで、かいぬししゃま……', '{p}呜……会遵守……别生气，饲主大人……', '{p}Eek... I will keep it... do not be angry, Owner...'),
        'adult': ('{p}ひ……まもります……おこらないで、かいぬしさま……', '{p}呜……会遵守……请别生气，饲主大人……', '{p}Eek... I will keep it... please do not be angry, Owner...')},
    # A rule was added, the pet sulked, and now it gives in: the owner decides.
    'rule_yield': {
        'baby': ('{c}……ぷくー……あい、かいぬちしゃま……', '{c}……噗咕……好嘛，饲主大人……', '{c}... puff... okay, Owner...'),
        'juvenile': ('{p}……ふん。……ちたがいましゅ、かいぬししゃま。', '{p}……哼。……听从，饲主大人。', '{p}...Hmph. ...I will obey, Owner.'),
        'adult': ('{p}……ふん。……したがいます、かいぬしさま。', '{p}……哼。……听从您，饲主大人。', '{p}...Hmph. ...I will obey, Owner.')},
    # A rule was lifted: delight and thanks (the breeding rule keeps its own permit line).
    'rule_loosen': {
        'baby': ('{c}！ やっちゃー！ ありがちょ、かいぬちしゃま！', '{c}！太好惹！谢谢饲主大人！', '{c}! Yay! Thank you, Owner!'),
        'juvenile': ('{p}ゆるくにゃっちゃ！ ありがちょう、かいぬししゃま！', '{p}变宽松惹！谢谢，饲主大人！', '{p}It got looser! Thank you, Owner!'),
        'adult': ('{p}ゆるくなった……！ ありがとうございます、かいぬしさま！', '{p}变宽松了……！谢谢您，饲主大人！', '{p}It got looser...! Thank you, Owner!')},
    # After the glad jump the pet bows to the owner who allowed it.
    'rule_grateful': {
        'baby': ('{c}……ありがちょ……かいぬちしゃま……', '{c}……谢谢……饲主大人……', '{c}... thank you... Owner...'),
        'juvenile': ('{p}かいぬししゃまの おかげ……ありがちょう。', '{p}多亏饲主大人……谢谢。', '{p}Thanks to you, Owner... thank you.'),
        'adult': ('{p}かいぬしさまの おかげです……ありがとうございます。', '{p}多亏饲主大人……谢谢您。', '{p}Thanks to you, Owner... thank you.')},
    # Caught breaking a rule: the first gasp at seeing the owner.
    'rule_gasp': {
        'baby': ('{c}！ ひっ……！ かいぬちしゃま……！', '{c}！呀……！饲主大人……！', '{c}! Eek...! Owner...!'),
        'juvenile': ('{p}ひっ……！ かいぬししゃま……！', '{p}呀……！饲主大人……！', '{p}Eek...! Owner...!'),
        'adult': ('{p}ひっ……！ かいぬしさま……！', '{p}呀……！饲主大人……！', '{p}Eek...! Owner...!')},
    # Caught and afraid of the owner: it begs for mercy.
    'rule_beg': {
        'baby': ('{c}……かいぬちしゃま……ゆるちちぇ……', '{c}……饲主大人……原谅窝……', '{c}... Owner... forgive me...'),
        'juvenile': ('{p}ゆるちちぇ……かいぬししゃま……', '{p}原谅窝……饲主大人……', '{p}Forgive me... Owner...'),
        'adult': ('{p}ゆるしてください……かいぬしさま……', '{p}请原谅……饲主大人……', '{p}Please forgive me... Owner...')},
    # Caught and guilty: the apology's second breath, an oath.
    'rule_vow': {
        'baby': ('{c}……かいぬちしゃま、ごみぇんなちゃい……もう ちましぇん……', '{c}……饲主大人，对不起……再也不敢……', '{c}... sowwy, Owner... never again...'),
        'juvenile': ('{p}ごみぇんなちゃい、かいぬししゃま……もう ちましぇん……', '{p}对不起，饲主大人……再也不敢了……', '{p}Sorry, Owner... never again...'),
        'adult': ('{p}ごめんなさい、かいぬしさま……もう しません……', '{p}对不起，饲主大人……再也不会了……', '{p}I am sorry, Owner... never again...')},
    # Caught and wary or sulking: it gives its reason, small, and still apologises.
    'rule_excuse': {
        'baby': ('{c}……だっちぇ……がまん できにゃい……かいぬちしゃま……', '{c}……可是……忍不住……饲主大人……', '{c}... but... could not hold it... Owner...'),
        'juvenile': ('{p}だっちぇ……がまん できにゃかった{e}……かいぬししゃま……', '{p}可是……实在忍不住{e}……饲主大人……', '{p}But... I could not hold it in... Owner...'),
        'adult': ('{p}だって……がまん できなかった{e}……かいぬしさま……', '{p}可是……实在忍不住{e}……饲主大人……', '{p}But... I could not hold it in... Owner...')},
    # Waiting for the verdict, lying low.
    'rule_wait': {
        'baby': ('{c}……かいぬちしゃま、おこっちぇる……？', '{c}……饲主大人，生气了……？', '{c}... Owner, are you angwy...?'),
        'juvenile': ('{p}かいぬししゃま……おこっちぇる……？', '{p}饲主大人……生气了……？', '{p}Owner... are you angry...?'),
        'adult': ('{p}かいぬしさま……おこって……？', '{p}饲主大人……生气了吗……？', '{p}Owner... are you angry...?')},
    # The stroke: forgiven.
    'rule_thanks': {
        'baby': ('{c}！ ありがちょ！ かいぬちしゃま！', '{c}！谢谢！饲主大人！', '{c}! Thank you, Owner!'),
        'juvenile': ('{p}ありがちょう、かいぬししゃま！', '{p}谢谢您，饲主大人！', '{p}Thank you, Owner!'),
        'adult': ('{p}ありがとうございます、かいぬしさま！', '{p}谢谢您，饲主大人！', '{p}Thank you, Owner!')},
    # The scold or the blow taken: it accepts the owner's judgement.
    'rule_accept': {
        'baby': ('{c}……あい……かいぬちしゃま、ごみぇんなちゃい……', '{c}……嗯……饲主大人，对不起……', '{c}... yes... sowwy, Owner...'),
        'juvenile': ('{p}あい……もう ちましぇん、かいぬししゃま……', '{p}嗯……再也不敢了，饲主大人……', '{p}Yes... never again, Owner...'),
        'adult': ('{p}はい……もう しません、かいぬしさま……', '{p}是……再也不会了，饲主大人……', '{p}Yes... never again, Owner...')},
    # The owner said nothing: it slinks off.
    'rule_withdraw': {
        'baby': ('{c}……かいぬちしゃま、ごみぇんなちゃい……', '{c}……饲主大人，对不起……', '{c}... Owner, sowwy...'),
        'juvenile': ('{p}……ごみぇんなちゃい、かいぬししゃま……', '{p}……对不起，饲主大人……', '{p}... sorry, Owner...'),
        'adult': ('{p}……ごめんなさい、かいぬしさま……', '{p}……对不起，饲主大人……', '{p}... I am sorry, Owner...')},
}

SEVERANCE_BASIS = 'wp-sever-voice: stat-driven severance reactions for driven-out children and witnessing siblings; project-original wording, unvoiced'
# Severed child and witnessing sibling lines, split by tier and character style (timid / plain / proud) with two varied lines per
# pool so repeated plays and different individuals read differently. Every yukkuri self-ref uses {n}/{nb} and species tics {e};
# yukkuri are always referred to as 油 (never 人).
SEVERANCE = {
    'sever_shock': {
        'baby': {
            'timid': [
                ('{c}……えっ……うしょ……？', '{c}……诶……骗油的吧……？', '{c}... eh... no way...?'),
                ('{c}……おやしゃ……どうして……？', '{c}……亲亲油……为什么……？', '{c}... mama... why...?')],
            'plain': [
                ('{c}……ほんちょなの……？', '{c}……真的吗……？', '{c}... weally...?'),
                ('{c}……えっ……{nb}、いくの……？', '{c}……诶……要{nb}走吗……？', '{c}... eh... {nb} go...?')],
            'proud': [
                ('{c}！？ うしょだよね！', '{c}？！骗油的吧！', '{c}?! Not twue!'),
                ('{c}！ どうして{nb}なの！', '{c}！为什么是{nb}！', '{c}! Why {nb}!')]},
        'juvenile': {
            'timid': [
                ('え……ほんとなの……？', '诶……真、真的吗……？骗油的吧……', 'Eh... r-really...? You are joking...'),
                ('ゆ……うそだよね……？', '油……？亲亲油……在开玩笑吧……？', 'Yu...? Parent... that is a joke, right...?')],
            'plain': [
                ('えっ……ほんとなの{e}……？', '诶……真的吗……？为什么要赶{n}走{e}……？', 'Eh... really...? Why are you sending {N} away...?'),
                ('ゆゆっ……でていけって{e}……？', '油油……？刚刚是叫{n}走吗{e}……？', 'Yu-yu...? Did you just tell {N} to leave...?')],
            'proud': [
                ('えっ……うそでしょ{e}！', '诶……骗油的吧！怎么可能赶走可爱的{n}{e}！', 'Eh... no way! You cannot send cute {N} away!'),
                ('ゆっ！？ まちがいだよね{e}！', '油？！等等，搞错了吧{e}！为什么是{n}啊！', 'Yu?! Wait, that is a mistake! Why {N}?!')]},
        'adult': {
            'timid': [
                ('え……ほんとうに……？', '诶……真的吗……？骗油的吧……', 'Eh... really...? That cannot be true...'),
                ('ゆ……どうして……？', '油……为什么……要赶{n}走……？', 'Yu... why... are you driving {N} out...?')],
            'plain': [
                ('えっ……でていけって{e}……？', '诶……真的要叫{n}走吗{e}……？', 'Eh... are you really telling {N} to leave...?'),
                ('ゆゆ……きゅうにどうして{e}……', '油油……突然这么说，{n}怎么信啊{e}……', 'Yu-yu... how can you say that all of a sudden...')],
            'proud': [
                ('はっ……じょうだんでしょ{e}！', '哈……开玩笑吧！凭什么要{n}走{e}！', 'Huh... you are joking! Why should {N} leave?!'),
                ('ゆっ！？ なにいってるの{e}！', '油？！等等！突然在说什么胡话{e}！', 'Yu?! Wait! What nonsense are you saying?!')]},
    },
    'sever_plea': {
        'baby': {
            'timid': [
                ('{c}……いやだ……おいていかないで……', '{c}……不要哒……别丢下{nb}……', '{c}... no... do not leave {nb}...'),
                ('{c}……{nb}、いいこに しゅるから……', '{c}……{nb}会乖乖的……', '{c}... {nb} will be good...')],
            'plain': [
                ('{c}！ {nb}、なんにも わゆいこと してないよ……！', '{c}！{nb}什么都没做错……！', '{c}! {nb} did nothing bad...!'),
                ('{c}……いっしょに ゆっくち しちぇ……', '{c}……还要一起油库气嘛……', '{c}... take it easy togevew...')],
            'proud': [
                ('{c}！ かわいい{nb}を おいださないで！', '{c}！不要赶走可爱的{nb}！', '{c}! Do not send cute {nb} away!'),
                ('{c}！ ずっちょ いっしょだよ！', '{c}！要一直在一起哒！', '{c}! Stay togevew!')]},
        'juvenile': {
            'timid': [
                ('おいださないで……！ {n}、いいこにするから、おねがい{e}……！', '不要赶{n}走……！{n}会乖乖听话的，求求你了{e}……！', 'Do not drive {N} out...! {N} will be good, please...!'),
                ('ゆぐっ……{n}、はなれたくないよ……おいていかないで{e}……', '呜呜……{n}不想分开……别丢下{n}啊{e}……', 'Sniff... {N} does not want to part... do not leave {N} behind...')],
            'plain': [
                ('{n}はなんにもわるいことしてないよ！ かわいい{n}をおいださないで{e}……！', '{n}什么都没做错！不要赶可爱的{n}走啊{e}……！', '{N} did nothing wrong! Do not send cute {N} away...!'),
                ('まって！ {n}、ちいさくなってじゃましないから……おいださないで{e}！', '等等！{n}会缩得小小的、不占地方的……别赶{n}走{e}！', 'Wait! {N} will curl up tiny and stay out of the way... please!')],
            'proud': [
                ('やだよ！ かわいい{n}がこんなにいいこなのに、おいださないで{e}！', '不要啦！可爱的{n}这么乖，怎么能赶{n}走{e}！', 'No! Cute {N} is such a good kid, do not send {N} away!'),
                ('{n}、ごはんすくなくするから！ おいていかないでよ{e}！', '只要{n}少吃一点就行了嘛！别丢下{n}啊{e}！', '{N} will eat less! Do not leave {N} behind!')]},
        'adult': {
            'timid': [
                ('おねがい……おいださないで……{n}、ちゃんと言うこと聞くから{e}……', '求求你……不要赶{n}走……{n}会乖乖听话的{e}……', 'Please... do not drive {N} out... {N} will listen...'),
                ('ゆぐっ……{n}を おいていかないで……まだいっしょに いたいよ{e}……', '呜呜……别丢下{n}……还想一直在一起{e}……', 'Sniff... do not leave {N} behind... {N} still wants to stay...')],
            'plain': [
                ('まって！ {n}は なんにも わるいこと してないよ！ おいださないで{e}！', '等等！{n}什么都没做错！不要赶{n}走{e}！', 'Wait! {N} did nothing wrong! Do not send {N} away!'),
                ('どうして……{n}も がんばるから、ここに いさせてよ{e}……！', '为什么……{n}也会努力的，让{n}留下来吧{e}……！', 'Why... {N} will try harder too, let {N} stay...!')],
            'proud': [
                ('やだよ！ {n}を こんなふうに おいだすなんて ひどい{e}！', '不要！怎么能这样赶{n}走，太过分了{e}！', 'No! Driving {N} out like this is too cruel!'),
                ('もういちど かんがえなおして！ {n}だって まだ ゆっくりできる{e}！', '再好好想想啊！{n}明明还能一起油库里的{e}！', 'Think again! {N} can still take it easy with you!')]},
    },
    'sever_grievance': {
        'baby': {
            'timid': [
                ('{c}……{nb}、わゆいこ じゃないよ……', '{c}……{nb}才不是坏孩子……', '{c}... {nb} is not a bad kid...'),
                ('{c}……どうして……{nb}だけ……', '{c}……为什么……只有{nb}……', '{c}... why... only {nb}...')],
            'plain': [
                ('{c}……なんにも してないのに……', '{c}……什么都没做错呀……', '{c}... did nothing wwong...'),
                ('{c}……かわいい{nb}なのに……', '{c}……明明是可爱的{nb}……', '{c}... cute {nb}...')],
            'proud': [
                ('{c}！ ひどいよ……！ {nb}、いいこなのに！', '{c}！太过分了……！{nb}明明很乖！', '{c}! So mean...! {nb} was good!'),
                ('{c}！ どうして{nb}に こんなこと するの！', '{c}！为什么要这样对{nb}！', '{c}! Why do this to {nb}!')]},
        'juvenile': {
            'timid': [
                ('{n}、なんにもわるいことしてないのに……どうしてかわいい{n}にこんなことするの{e}……', '{n}什么都没做错……为什么要这样对可爱的{n}{e}……', '{N} did nothing wrong... why do this to cute {N}...'),
                ('ずっと{n}、いいこにしてたのに……どうしてもういらないの{e}……', '明明{n}一直都很乖的……为什么不要{n}了{e}……', '{N} was always so good... why do you not want {N} anymore...')],
            'plain': [
                ('{n}はなんにもわるいことしてないよ！ どうしてかわいい{n}にこんなことするの{e}……！', '{n}什么都没做错！为什么要这样对可爱的{n}{e}……！', '{N} did nothing wrong! Why are you doing this to cute {N}...!'),
                ('ひどいよ……{n}、まだみんなといっしょにゆっくりしたいのに{e}……', '哪有这样对油的……{n}还想和大家一起油库里啊{e}……', 'That is so cruel... {N} still wanted to take it easy with everyone...')],
            'proud': [
                ('ひどいよ！ かわいい{n}がこんなにいいこなのに、どうしておいだすの{e}！', '太过分了！可爱的{n}明明这么乖，为什么要赶{n}走{e}！', 'So unfair! Cute {N} was so good, why send {N} away!'),
                ('どうして{n}だけなの！ {n}、わるいことしてない{e}……！', '为什么偏偏是{n}啊！{n}又没做错什么{e}……！', 'Why does it have to be {N}?! {N} did nothing bad...!')]},
        'adult': {
            'timid': [
                ('{n}、なんにも わるいこと してないのに……どうして こんなことに{e}……', '{n}什么都没做错……为什么会变成这样{e}……', '{N} did nothing wrong... why did it come to this...'),
                ('ずっと ちゃんとしてたのに……どうして{n}だけ もう いらないの{e}……', '明明一直都很守规矩的……为什么偏偏不要{n}了{e}……', '{N} behaved so well... why only cast {N} out...')],
            'plain': [
                ('{n}は なんにも わるいこと してないよ！ どうして こんな ひどいこと するの{e}……！', '{n}什么都没做错！为什么要这样对可爱的{n}{e}……！', '{N} did nothing wrong! Why do something so cruel...!'),
                ('あんまりだよ……{n}だって まだ いっしょに ゆっくりしたいのに{e}……', '太让油伤心了……{n}明明还想一起油库里的{e}……', 'That breaks my heart... {N} still wanted to take it easy together...')],
            'proud': [
                ('ひどいよ！ {n}は なにも わるくないのに、どうしておいだすの{e}！', '太过分了！{n}一点错都没有，凭什么赶走{n}{e}！', 'Unfair! {N} is not at fault, why drive {N} out!'),
                ('どうして{n}だけ こんな目に あうの{e}！ 納得できないよ！', '为什么只有{n}要受这种委屈{e}！真让油气不过！', 'Why does only {N} suffer this?! It makes no sense!')]},
    },
    'sever_protest': {
        'baby': {
            'timid': [
                ('{c}！ いやだ！ {nb}、わゆく ない！', '{c}！不要哒！{nb}才没错！', '{c}! No! {nb} not bad!')],
            'plain': [
                ('{c}！ ぷくー！ {nb}は でちぇいかない！', '{c}！噗咕！{nb}才不走！', '{c}! Puff! {nb} not leaving!')],
            'proud': [
                ('{c}！ ぷくー！ かわいい{nb}を おいだすな！', '{c}！噗咕！不准赶走可爱的{nb}！', '{c}! Puff! Do not kick cute {nb} out!')]},
        'juvenile': {
            'timid': [
                ('{p}いやだよ！ {n}、なんにもわるいことしてないのに{e}！', '{p}不要啦！{n}什么都没做错啊{e}！', '{p}No! {N} did nothing wrong!'),
                ('{p}どうしてなの！ {n}、そんなの ゆるさない{e}！', '{p}凭什么啊！{n}才不答应{e}！', '{p}Why?! {N} will not accept that!')],
            'plain': [
                ('{p}ぷくーっ！ {n}はなんにもわるいことしてない！ どうしてかわいい{n}をおいだすの{e}！', '{p}噗咕——！{n}什么都没做错！凭什么赶走可爱的{n}{e}！', '{p}Puff! {N} did nothing wrong! Why drive cute {N} out?!'),
                ('{p}ひどいよ！ かわいい{n}をこんなふうに あつかうなんて{e}！', '{p}太不讲理了！竟然这样对待可爱的{n}{e}！', '{p}So unfair! Treating cute {N} like this!')],
            'proud': [
                ('{p}ぷくーっ！ じょうだんじゃないよ！ かわいい{n}をおいだすなんて ゆるさない{e}！', '{p}噗咕——！开什么玩笑！赶走可爱的{n}绝对不能原谅{e}！', '{p}Puff! Do not joke! Driving cute {N} out is unforgivable!'),
                ('{p}かってすぎるよ！ ここは{n}の いばしょでもあるのに{e}！', '{p}太任性了！这里明明也有{n}的份{e}！', '{p}How selfish! This is {N}s place too!')]},
        'adult': {
            'timid': [
                ('{p}いやだよ……{n}、なにも わるいこと してないのに{e}！', '{p}不要……{n}什么都没做错啊{e}！', '{p}No... {N} did nothing wrong!'),
                ('{p}そんなの ひどいよ……どうして{n}だけ{e}！', '{p}那样太过分了……为什么只有{n}{e}！', '{p}That is too cruel... why only {N}!')],
            'plain': [
                ('{p}ぷくーっ！ {n}は なにも わるくない！ なんで おいだすの{e}！', '{p}噗咕——！{n}一点错都没有！凭什么赶{n}走{e}！', '{p}Puff! {N} did nothing wrong! Why drive {N} out?!'),
                ('{p}あんまりだよ！ いままで いっしょだったのに{e}！', '{p}太不讲理了！明明一直都在一起的{e}！', '{p}That is outrageous! After all this time together!')],
            'proud': [
                ('{p}ぷくーっ！ かってにおいだすな！ {n}は みとめない{e}！', '{p}噗咕——！少擅自赶油走！{n}才不认呢{e}！', '{p}Puff! Do not just kick {N} out! {N} refuses!'),
                ('{p}ひどい親だね！ こんなあつかい、ゆるさない{e}！', '{p}真过分的亲油！这种对待，绝对不原谅{e}！', '{p}What a cruel parent! Unforgivable!')]},
    },
    'sever_farewell': {
        'baby': {
            'timid': [
                ('{c}……ごみぇんなちゃい……バイバイ……', '{c}……对不起……拜拜……', '{c}... sowwy... bye-bye...'),
                ('{c}……{nb}、いくよ……ゆぐっ……', '{c}……{nb}走惹……呜呜……', '{c}... {nb} going... sniff...')],
            'plain': [
                ('{c}……わかっちゃ……バイバイ……', '{c}……知道惹……拜拜……', '{c}... okay... bye-bye...'),
                ('{c}……かなちいよ……バイバイ……', '{c}……好伤心……拜拜……', '{c}... so sad... bye-bye...')],
            'proud': [
                ('{c}……ひどいよ……もう いくもん……', '{c}……太过分惹……这就走嘛……', '{c}... meanie... going now...')]},
        'juvenile': {
            'timid': [
                ('ゆぐっ……ごめんね……{n}、すぐいくよ……', '呜呜……对不起……{n}这就走……', 'Sniff... sorry... {N} will go right away...'),
                ('……わかったよ……もう、めいわくかけない{e}……', '……{n}知道了……不会再给亲亲油添麻烦了{e}……', '...{N} understands... will not be a bother anymore...')],
            'plain': [
                ('……ゆぐっ……まだいっしょにゆっくりしたかったのに……さよなら{e}……', '……呜呜……{n}明明还想一起油库里的……再见了{e}……', '...Sob... {N} still wanted to take it easy together... goodbye...'),
                ('わかったよ……かなしいよ……{n}、じぶんでいくよ{e}……', '知道了啦……真让油伤心……{n}自己走就是了{e}……', 'Fine... this hurts so much... {N} will go alone...')],
            'proud': [
                ('……いいよ！ {n}がいなくなっても、こうかいしないでね{e}……', '……好吧！{n}走了以后，可别后悔哦{e}……', '...Fine! Do not regret it once {N} is gone...'),
                ('ひどいよ……{n}、ひとりでもゆっくりできるもん……さよなら{e}……', '真过分……{n}自己也能油库里的……再见{e}……', 'So mean... {N} can take it easy alone too... goodbye...')]},
        'adult': {
            'timid': [
                ('ゆぐっ……ごめんなさい……{n}、すぐ でていくよ……', '呜呜……对不起……{n}这就走……', 'Sniff... I am sorry... {N} will leave right away...'),
                ('……わかったよ……もう、じゃまは しない{e}……', '……知道了……不会再碍事了{e}……', '...I understand... {N} will not get in the way anymore...')],
            'plain': [
                ('……さよなら……もう、いっしょに ゆっくり できないんだね{e}……', '……再见了……再也不能一起油库里了呢{e}……', '...Goodbye... we cannot take it easy together anymore...'),
                ('わかったよ……かなしいけど、{n}は じぶんで いくよ{e}……', '知道了……虽然真让油伤心，{n}自己走就是了{e}……', 'I get it... it hurts, but {N} will go on its own...')],
            'proud': [
                ('……いいよ。{n}が いなくても ちゃんと やっていけるもんね{e}……', '……行吧。没有你，{n}自己也能过得好好的{e}……', '...Fine. {N} will manage just fine without you...'),
                ('さよなら……もう、ふりかえらないから{e}……', '再见……{n}不会再回头了{e}……', 'Goodbye... {N} will not look back...')]},
    },
    'sever_look_back': {
        'baby': {
            'timid': [('{c}……バイバイ……げんきでね……', '{c}……拜拜……要好好的……', '{c}... bye-bye... be well...')],
            'plain': [('{c}……みんな、ゆっくち しちぇね……', '{c}……大家，要油库气哦……', '{c}... evewyone, take it easy...')],
            'proud': [('{c}……{nb}のこと、わしゅれないでね……', '{c}……别忘了{nb}哦……', '{c}... do not fowget {nb}...')]},
        'juvenile': {
            'timid': [
                ('……おやしゃ、みんな……ちゃんと ゆっくりしてね{e}……', '……亲亲油，大家……要好好油库里哦{e}……', '...Parent, everyone... please take it easy...'),
                ('……さよなら……{n}のこと、わすれないでね{e}……', '……再见了……可不要忘了{n}啊{e}……', '...Goodbye... please do not forget {N}...')],
            'plain': [
                ('……もう、ほんとに さよならなんだね……みんな、ゆっくりしていってね{e}……', '……真的要说再见了呢……大家要好好油库里哦{e}……', '...It really is goodbye... everyone, take it easy...'),
                ('……バイバイ……{n}も、どこかできっとゆっくりするよ{e}……', '……拜拜了……{n}也会在别处好好油库里的{e}……', '...Bye-bye... {N} will take it easy somewhere too...')],
            'proud': [
                ('……ふんっ、バイバイだよ！ みんな げんきでね{e}……！', '……呜，拜拜啦！大家都要好好的{e}……！', '...Hmph, bye then! Stay well, everyone...!'),
                ('……かわいい{n}のこと、ぜったい わすれないでね{e}……！', '……绝对不许忘了可爱的{n}哦{e}……！', '...Do not ever forget cute {N}...!')]},
        'adult': {
            'timid': [
                ('……さよなら……みんな、ゆっくりしていってね{e}……', '……再见了……大家要好好油库里哦{e}……', '...Goodbye... everyone, take it easy...')],
            'plain': [
                ('……もう、ほんとに おわかれだね……げんきでね{e}……', '……真的要分别了呢……要保重哦{e}……', '...This is really goodbye... take care...')],
            'proud': [
                ('……じゃあね！ {n}も べつの ばしょで ゆっくりするから{e}！', '……再见啦！{n}也会在别处好好油库里的{e}！', '...See you! {N} will take it easy somewhere else!')]},
    },
    'sever_cold_scoff': {
        'baby': {
            'timid': [('{c}……ふーん……いいよ……', '{c}……哦……随你吧……', '{c}... oh... whatevew...')],
            'plain': [('{c}……べつに、いらないもん……', '{c}……切，{nb}才不稀罕呢……', '{c}... do not care anyway...')],
            'proud': [('{c}！ べつに、こっちから いくもん！', '{c}！切，是{nb}自己要走的！', '{c}! Fine, {nb} leaving anyway!')]},
        'juvenile': {
            'timid': [
                ('……ふーん、それでいいよ……', '……哦。不要{n}的话，那就算了……', '...Oh. If you do not want {N}, fine...'),
                ('……べつに、たのまないし……', '……这样啊。反正{n}也没打算求你……', '...I see. {N} was not going to beg anyway...')],
            'plain': [
                ('……ふーん、おいだすんだ{e}。', '……切，说赶就赶啊。以为{n}很想待在这儿吗{e}？', '...Tch, just kicking {N} out. Think {N} even wanted to stay here?'),
                ('ゆ……かってにすれば{e}。', '油……随你便吧。挤巴巴的破帽子，{n}才不稀罕呢{e}。', 'Yu... suit yourself. {N} is sick of that cramped hat anyway.')],
            'proud': [
                ('は？ こっちからでていく{e}！', '哈？赶{n}走？明明是{n}早就不想待这儿了{e}！', 'Huh? Driving {N} out? {N} was about to leave anyway!'),
                ('けち！ たよらないもん{e}！', '切，真小气！可爱的{n}才不稀罕靠你养呢{e}！', 'Stingy! Cute {N} does not need to rely on you!')]},
        'adult': {
            'timid': [
                ('……ふーん。そう いうなら、もう いいよ……', '……哦。既然你这么说，那就算了……', '...Oh. If that is how it is, fine...'),
                ('……べつに、すがる つもりも ないし……', '……反正{n}也没想过挽留……', '...Not like {N} was going to cling to you...')],
            'plain': [
                ('……ふーん、おいだすのね。べつに ここに いたいわけじゃ ない{e}。', '……切，说赶就赶啊。{n}本来也没多想待在这儿{e}。', '...Tch, kicking {N} out. Not that {N} wanted to stay anyway.'),
                ('かってに すれば。{n}も もう うんざり してたところ{e}。', '随你便吧。{n}也早就受够了{e}。', 'Suit yourself. {N} was getting sick of this place too.')],
            'proud': [
                ('は？ おいだすって？ こっちから ごめんだ{e}！', '哈？赶{n}走？是{n}先不要这里的{e}！', 'Huh? Driving {N} out? {N} is walking out on you!'),
                ('けちくさいね！ あんたなんかに たよらなくて いい{e}！', '真小气！{n}才不需要靠你呢{e}！', 'How stingy! {N} does not need your help!')]},
    },
    'sever_cold_leave': {
        'baby': {
            'timid': [('{c}……ふん……ひとりで いくもん……', '{c}……哼……{nb}自己走就是了……', '{c}... hmph... {nb} go alone...')],
            'plain': [('{c}！ ふん！ {nb}、ひとりで ゆっくち しゅるもん！', '{c}！哼！{nb}自己也能油库气！', '{c}! Hmph! {nb} take it easy alone!')],
            'proud': [('{c}！ ふんっ！ もう しらないもん！', '{c}！哼！再也不理你了！', '{c}! Hmph! Do not care anymore!')]},
        'juvenile': {
            'timid': [
                ('ふん……ほっといて、{n}はひとりでいくよ……', '哼……不用你管，{n}自己走就是了……', 'Hmph... leave it alone, {N} will go by itself...'),
                ('……ふん、どうせここではゆっくりできないし……', '……哼，反正这里本来也没法好好油库里……', '...Hmph, could not really take it easy here anyway...')],
            'plain': [
                ('ふん……あんたがいなくても、{n}はひとりでゆっくりできる{e}！', '哼……没有你，{n}自己也能好好油库里{e}！', 'Hmph... even without you, {N} can take it easy just fine!'),
                ('こんなつめたいおや、こっちからいらないよ……ふん、じゃあね{e}！', '才不需要这种冷血亲油呢……哼，再也不见{e}！', 'Do not need a cold parent like you anyway... hmph, goodbye!')],
            'proud': [
                ('ふん！ もっといいゆっくりプレイスをみつけても、よんであげない{e}！', '哼！等{n}找到更好的悠闲之地，才不带你玩呢{e}！', 'Hmph! When {N} finds a better easy place, you are not invited!'),
                ('こんなぼうしだれがいるもんか！ ふん、{n}のほうがゆっくりできる{e}！', '破帽子谁稀罕啊！哼，{n}自己过得更油库里{e}！', 'Who cares about this dumb hat! Hmph, {N} will be way easier alone!')]},
        'adult': {
            'timid': [
                ('ふん……もう いいよ、{n}は ひとりで いくから……', '哼……算了，{n}自己走就是了……', 'Hmph... whatever, {N} will go alone...'),
                ('……ふん、どうせ ここでは ゆっくり できなかったし……', '……哼，反正这里也从来没法好好油库里……', '...Hmph, could never really take it easy here anyway...')],
            'plain': [
                ('ふん……あんたが いなくても、{n}は ひとりで ゆっくりできる{e}！', '哼……没有你，{n}自己也能好好油库里{e}！', 'Hmph... even without you, {N} can take it easy on its own!'),
                ('こんな つめたい おや、こっちから 願い下げだよ……ふん、じゃあね{e}！', '这么冷淡的亲油，{n}才不稀罕……哼，再也不见{e}！', 'A cold parent like this is no loss... hmph, bye!')],
            'proud': [
                ('ふん！ もっと いい ばしょを みつけて、{n}だけ ゆっくりしてやる{e}！', '哼！{n}要去找更好的地方，自己痛痛快快油库里{e}！', 'Hmph! {N} will find a better place and take it easy all alone!'),
                ('せいせいしたよ！ ふん、もう にどと かおを みせないでね{e}！', '正好清静！哼，再也别让{n}看见你{e}！', 'Good riddance! Hmph, never show your face to {N} again!')]},
    },
    'sever_cold_mutter': {
        'baby': {
            'timid': [('{c}……ふん……ふりかえらないもん……', '{c}……哼……才不回头……', '{c}... hmph... not looking back...')],
            'plain': [('{c}……ふん、べつに へいきだもん……', '{c}……哼，才不在乎呢……', '{c}... hmph, do not care...')],
            'proud': [('{c}！ ふんっ、あっちのほうが いいもん！', '{c}！哼，前面才更好玩呢！', '{c}! Hmph, ahead is bettew!')]},
        'juvenile': {
            'timid': [
                ('……べつに、さみしくなんてないよ……ふん……', '……才没有觉得孤单呢……哼……', '...Not like {N} feels lonely or anything... hmph...'),
                ('……ふりかえったりしないもん……ふん……', '……才不会回头看呢……哼……', '...Not going to look back... hmph...')],
            'plain': [
                ('……ぜったい ふりかえらないからね……ふんっ！', '……绝对不会回头看的……哼！', '...Definitely not looking back... hmph!'),
                ('……まったく、なんだっていうの……じぶんでいいばしょさがすよ{e}。', '……真是的，搞什么嘛……{n}自己找个好地方油库里去{e}。', '...Honestly, what was that about... {N} will find a good spot itself.')],
            'proud': [
                ('……ふんっ！ あんなせまいところ、でてきてせいかいだった{e}！', '……哼！那种挤死油的地方，出来才对呢{e}！', '...Hmph! Leaving a cramped place like that was the right call!'),
                ('……つぎはもっとひろいゆっくりプレイスみつけるもんね{e}！', '……下次{n}要找个更宽敞的悠闲之地{e}！', '...Next {N} will find an even bigger easy place!')]},
        'adult': {
            'timid': [
                ('……べつに、さみしくなんて ないし……ふん……', '……反正一点也不难过……哼……', '...Not sad at all... hmph...')],
            'plain': [
                ('……ふりかえらないよ……じぶんで ゆっくりできる ばしょを さがす{e}。', '……才不回头呢……{n}自己找能油库里的地方去{e}。', '...Not looking back... {N} will find its own easy place.')],
            'proud': [
                ('……ふんっ！ ひとりになって せいせいした{e}！', '……哼！自己一只反而清静多了{e}！', '...Hmph! Way better off on my own!')]},
    },
    'child_curse_parent': {
        'baby': {
            'timid': [('{c}！ ひどい！ きらい！', '{c}！太过分惹！讨厌！', '{c}! Meanie! Hate you!')],
            'plain': [('{c}！ くそおや！ もう いらない！', '{c}！粪亲油！不要惹！', '{c}! Bad pawent! Do not want!')],
            'proud': [('{c}！ ぷくー！ くそおや、きらい！', '{c}！噗咕！粪亲油，讨厌！', '{c}! Puff! Bad pawent, hate you!')]},
        'juvenile': {
            'timid': [
                ('ひどいよ……！ {n}、もうこんなおうちいらない{e}……！', '太过分了……！{n}再也不要这种家了{e}……！', 'So cruel...! {N} does not want a home like this anymore...!'),
                ('どうして おすの……！ きらい、もう しらない{e}……！', '干嘛推{n}啊……！讨厌你，再也不理你了{e}……！', 'Why push {N}...! Hate you, never talking to you again...!')],
            'plain': [
                ('ぷくーっ！ かわいい{n}をおすなんて！ くそおや、こんなおうちいらない{e}！', '噗咕——！竟然推可爱的{n}！粪亲油，{n}才不要这种家{e}！', 'Puff! How dare you shove cute {N}! Shitty parent, {N} does not want this home!'),
                ('{n}はなんにもわるくないのに！ ばかおや、もうしらない{e}！', '{n}什么都没做错！笨蛋亲油，{n}再也不理你了{e}！', '{N} did nothing wrong! Stupid parent, {N} is done with you!')],
            'proud': [
                ('くそおや！ よくもかわいい{n}をおいだしたね！ おぼえてなさいよ{e}！', '粪亲油！竟敢赶走可爱的{n}！给{n}记住{e}！', 'Shitty parent! How dare you cast out cute {N}! You will remember this!'),
                ('ぷくーっ！ さいあくのおやだ！ こんなところこっちからすててやる{e}！', '噗咕——！最差劲的亲油！这种破地方是{n}不要的{e}！', 'Puff! Worst parent ever! {N} is ditching this dump!')]},
        'adult': {
            'timid': [
                ('ひどいよ……！ こんなおうち、もう いらない{e}……！', '太过分了……！这种家，{n}再也不要了{e}……！', 'Too cruel...! {N} does not want this home anymore...!')],
            'plain': [
                ('くそおや！ こんな おうち、もう こっちから いらない{e}！', '粪亲油！这种家，{n}才不稀罕呢{e}！', 'Shitty parent! {N} does not want a home like this anymore!'),
                ('ぷくーっ！ なんにも わるくないのに！ もう しらない{e}！', '噗咕——！明明什么错都没有！再也不理你了{e}！', 'Puff! Did nothing wrong! Done with you!')],
            'proud': [
                ('くそおや！ こんな 仕打ち、ぜったい ゆるさない{e}！', '粪亲油！这种对待，绝对不原谅{e}！', 'Shitty parent! {N} will never forgive this!'),
                ('ぷくーっ！ おぼえてなさい！ こんな ところ、すててやる{e}！', '噗咕——！给{n}记住！这种破地方谁稀罕{e}！', 'Puff! You just wait! {N} is ditching this place!')]},
    },
    'sibling_sever_cry': {
        'baby': {
            'timid': [
                ('{c}……いかにゃいで……かなちいよ……', '{c}……不要走……好伤心……', '{c}... do not go... so sad...'),
                ('{c}……いっしょに ゆっくち しゅるのに……', '{c}……还要一起油库气呢……', '{c}... take it easy togevew...')],
            'plain': [
                ('{c}ーっ！ おいていかないで……もどってきちぇ……！', '{c}——！别走呀……快回来……！', '{c}! Do not go... come back...!'),
                ('{c}……バイバイ、いやだ……！', '{c}……不要拜拜……！', '{c}... no bye-bye...!')],
            'proud': [
                ('{c}！ いかないできちぇ！ いっしょに あしょぶの！', '{c}！不要走！还要一起玩呢！', '{c}! Stay! Play togevew!')]},
        'juvenile': {
            'timid': [
                ('ゆぐっ……いかないで……またいっしょにゆっくりしようよ{e}……', '呜呜……不要走……再一起油库里嘛{e}……', 'Sniff... do not go... let us take it easy together again...'),
                ('おやしゃ……おいださないで……かなしいよ{e}……', '亲亲油……不要赶走啊……好让油伤心{e}……', 'Parent... do not send them away... it makes {N} so sad...')],
            'plain': [
                ('いかないで……！ {n}をおいていかないで……いっしょにゆっくりしようよ{e}！', '不要走……！别丢下{n}啊……一起油库里呀{e}！', 'Do not go...! Do not leave {N}... let us take it easy together!'),
                ('どうして……ずっと いっしょの かぞくなのに……もどってきてよ{e}！', '怎么这样……明明是一直在一起的家油……快回来啊{e}！', 'Why... we are family... please come back!')],
            'proud': [
                ('まってよ！ ほんとに いかないで……{n}、さみしいよ{e}……！', '等等啊！别真的走……{n}会想你的{e}……！', 'Wait! Do not really leave... {N} will miss you...!'),
                ('いやだよ！ みんなで いっしょに ゆっくり するんでしょ{e}！', '不要嘛！说好大家要一起油库里的呀{e}！', 'No! We were supposed to take it easy together!')]},
        'adult': {
            'timid': [
                ('ゆぐっ……いかないで……こんなの かなしすぎるよ{e}……', '呜呜……不要走……这样太让油伤心了{e}……', 'Sniff... do not go... this is too sad...')],
            'plain': [
                ('まって……いかないで……また いっしょに ゆっくり しようよ{e}……！', '等等……别走……再一起油库里吧{e}……！', 'Wait... do not go... let us take it easy together again...!'),
                ('どうして こんなことに……もどってきてよ{e}……！', '怎么会变成这样……快回来呀{e}……！', 'How did it come to this... please come back...!')],
            'proud': [
                ('まってよ！ ほんとに でていかなくて いいじゃない{e}……！', '等等啊！不用真的走吧{e}……！', 'Wait! You do not really have to leave...!')]},
    },
    'sibling_sever_protest': {
        'baby': {
            'timid': [('{c}！ おやしゃ、やめちぇ……！', '{c}！亲亲油，不要这样……！', '{c}! Pawent, stop...!')],
            'plain': [('{c}！ ぷくー！ おいださないで！', '{c}！噗咕！不要赶走呀！', '{c}! Puff! Do not send away!')],
            'proud': [('{c}！ ぷくー！ みんな いっしょだよ！', '{c}！噗咕！大家要在一起哒！', '{c}! Puff! Evewyone stays!')]},
        'juvenile': {
            'timid': [
                ('{p}おやしゃ、やめて……！ みんな いっしょが いいよ{e}……！', '{p}亲亲油，别这样……！大家在一起才好呀{e}……！', '{p}Parent, stop...! It is better with everyone together...!'),
                ('{p}おいださないでよ……{n}、ぎゅっとして つめるから{e}……！', '{p}别赶走呀……{n}会挤一挤让地方的{e}……！', '{p}Do not send them away... {N} will squeeze in to make room...!')],
            'plain': [
                ('{p}おやしゃ、ひどいよ！ どうして おいだすの{e}！', '{p}亲亲油太过分了！为什么一定要赶走啊{e}！', '{p}Parent, that is so mean! Why drive them away?!'),
                ('{p}まって！ {n}も ぎゅって つめるから、おいださないでよ{e}！', '{p}等等！{n}也能挤一挤的，不要赶走大家啊{e}！', '{p}Wait! {N} can squeeze in too, do not send anyone away!')],
            'proud': [
                ('{p}ぷくーっ！ こんなの ひどすぎるよ！ みんな いっしょに ゆっくり するんだ{e}！', '{p}噗咕——！这样太让油伤心了！大家要一起油库里才对{e}！', '{p}Puff! This is way too cruel! Everyone should take it easy together!'),
                ('{p}やめてよ おやしゃ！ かぞくを おいだすなんて ダメだ{e}！', '{p}住手呀亲亲油！怎么可以把家油赶走呢{e}！', '{p}Stop it, parent! You cannot drive family away!')]},
        'adult': {
            'timid': [
                ('{p}やめて……かぞくを おいださないでよ{e}……！', '{p}不要这样……别把家油赶走啊{e}……！', '{p}Stop... do not drive family away...!')],
            'plain': [
                ('{p}ひどいよ！ もっと べつの ほうほう が あるはずだよ{e}！', '{p}太过分了！肯定还有别的办法呀{e}！', '{p}That is too cruel! There must be another way!')],
            'proud': [
                ('{p}ぷくーっ！ かぞくを おいだすなんて ゆるせない{e}！', '{p}噗咕——！把家油赶走这种事绝对不行{e}！', '{p}Puff! Driving family out is unacceptable!')]},
    },
    'sibling_sever_scared': {
        'baby': {
            'timid': [
                ('{c}……こあい……{nb}、いいこに しゅる……', '{c}……好口怕……{nb}会乖乖的……', '{c}... scawy... {nb} be good...'),
                ('{c}……ひっ……おこらないで……', '{c}……呜……不要生气……', '{c}... eek... do not be mad...')],
            'plain': [
                ('{c}……つぎは {nb}……？ こあい……', '{c}……下一个……是{nb}吗……？好口怕……', '{c}... next is {nb}...? Scawy...')],
            'proud': [
                ('{c}……びっくり しちゃ……こあいよ……', '{c}……吓一跳……好口怕……', '{c}... stawtled... scawy...')]},
        'juvenile': {
            'timid': [
                ('こわいよぉ……つぎは……{n}じゃないよね……？', '好口怕……下一个……不会轮到{n}吧……？', 'So scary... next... it will not be {N}, right...?'),
                ('ゆぐっ……おやしゃ こわい……{n}、いいこにするから おいださないで……', '呜呜……亲亲油好凶……{n}会乖乖听话的，不要赶{n}走……', 'Sniff... parent is scary... {N} will be good, do not drive {N} out...')],
            'plain': [
                ('ひっ……こわいよ……{n}、ちいさく してよう……', '呜……好可怕……{n}要缩得小小的……', 'Eek... scary... {N} will stay very small...'),
                ('ゆぅ……つぎに おいだされるの、{n}だったら どうしよう{e}……', '油呜……要是下一个被赶走的是{n}怎么办{e}……', 'Yuu... what if {N} is the next one driven out...')],
            'proud': [
                ('うわっ……ほんとに おいだしちゃった……こわいよ{e}……', '哇……真的赶走了……好可怕{e}……', 'Whoa... they really drove them out... scary...'),
                ('ゆ……いまは おとなしく しておこう{e}……', '油……现在还是乖乖别出声吧{e}……', 'Yu... better stay quiet right now...')]},
        'adult': {
            'timid': [
                ('こわい……つぎは {n}の ばん じゃないよね……？', '好可怕……下一个不会轮到{n}吧……？', 'Scary... {N} is not next, right...?'),
                ('ゆぅ……おこらせない ように おとなしく しよう……', '油呜……还是乖乖的，别惹生气了……', 'Yuu... better stay quiet and not anger them...')],
            'plain': [
                ('ひっ……ほんとうに おいだすなんて……こわいよ{e}……', '呜……真的赶走了……好可怕{e}……', 'Eek... actually driving them out... scary...')],
            'proud': [
                ('うわ……こわい 空気だね……だまって おこう{e}……', '哇……气氛好可怕……还是别说话了{e}……', 'Whoa... scary mood... better keep quiet...')]},
    },
    'sibling_sever_cold': {
        'baby': {
            'timid': [('{c}……ふーん……せまかったもん……', '{c}……哦……本来就好挤嘛……', '{c}... oh... was cwamped...')],
            'plain': [('{c}……ふん、はやく いって……', '{c}……哼，快点走吧……', '{c}... hmph, go already...')],
            'proud': [('{c}！ ふん！ ひろく なっちゃ！', '{c}！哼！宽敞多了！', '{c}! Hmph! Mowe woom!')]},
        'juvenile': {
            'timid': [
                ('……どうせ せまかったし、しかたないよね……', '……反正本来就挤不下，没办法嘛……', '...It was too cramped anyway, nothing to do about it...'),
                ('……ふーん、いなくなれば らくになるのかな……', '……哦，少一只的话会轻松点吧……', '...Oh, maybe it will be easier with one less...')],
            'plain': [
                ('……ふーん、おもすぎたんだから しかたないよ。はやく いって{e}。', '……切，谁让它那么重……快点走吧{e}。', '...Tch, too heavy anyway. Just go already.'),
                ('ひとり へれば、ぼうししゃんが ひろくなるね……ふん{e}。', '少一只的话，帽子先生就宽敞多了……哼{e}。', 'With one fewer, Mr. Hat has more room... hmph.')],
            'proud': [
                ('ふんっ！ おもい こが でていって せいせいした{e}！', '哼！沉家伙走了反而清静多了{e}！', 'Hmph! Good riddance to the heavy one!'),
                ('べつに いいんじゃない？ これで {n}が もっと ゆっくりできる{e}！', '走了正好嘛？这样{n}就能更宽敞地油库里了{e}！', 'Who cares? Now {N} gets more room to take it easy!')]},
        'adult': {
            'timid': [
                ('……せまかったし、しかたないよね……', '……反正挤不下，也没办法嘛……', '...It was too cramped anyway, cannot be helped...')],
            'plain': [
                ('……ふーん、おもすぎたんだから しかたない{e}。はやく いけば。', '……切，谁让它那么重{e}。快走吧。', '...Tch, too heavy to carry. Just leave already.')],
            'proud': [
                ('ふんっ、これで ぼうしさんが ひろくなるね{e}！', '哼，这样帽子先生就宽敞多了{e}！', 'Hmph, now Mr. Hat has plenty of room!')]},
    },
    'sibling_sever_uneasy': {
        'baby': {
            'timid': [('{c}……いっちゃうの……？', '{c}……要走惹吗……？', '{c}... leaving...?')],
            'plain': [('{c}……ふーん……そうなんだ……', '{c}……哦……这样啊……', '{c}... oh... okay...')],
            'proud': [('{c}……なんだか へんだよ……', '{c}……总觉得怪怪的……', '{c}... feels weiwd...')]},
        'juvenile': {
            'timid': [
                ('……ほんとに いっちゃうんだ……そうなんだ……', '……真的要走吗……这样啊……', '...Really leaving... I see...'),
                ('……ゆ……なんだかきまずいよ……だまってよう……', '……油……气氛好僵硬……{n}还是别说话了……', '...Yu... this feels awkward... {N} will stay quiet...')],
            'plain': [
                ('……そうなんだ。まあ、しかたないのかな{e}……', '……这样啊。那也没办法吧{e}……', '...I see. Well, guess it cannot be helped...'),
                ('……ゆ、なんだか たいへんそうだね……{n}は みてよう……', '……油，好像很麻烦的样子……{n}先看着吧……', '...Yu, looks like trouble... {N} will just watch...')],
            'proud': [
                ('……ふーん、でていくんだね。まあ、がんばってよ{e}。', '……哦，要走了啊。那自己保重吧{e}。', '...Oh, heading out then. Well, good luck.'),
                ('……なんだかしんみりしてるね。{n}は べつに いいけど{e}。', '……气氛怪沉重的呢。{n}倒是无所谓啦{e}。', '...Pretty heavy mood. {N} does not mind though.')]},
        'adult': {
            'timid': [
                ('……ほんとうに でていくんだ……なんだか 気まずいね……', '……真的要走了啊……总觉得好僵硬……', '...Really leaving... feels awkward...')],
            'plain': [
                ('……そう。まあ、しかたないのかな{e}……', '……这样啊。那也没办法吧{e}……', '...I see. Guess it cannot be helped...')],
            'proud': [
                ('……ふーん、でていくんだ。べつに とめないけど{e}。', '……哦，要走了啊。{n}倒也不会拦着就是了{e}。', '...Oh, leaving then. Not that {N} will stop you.')]},
    },
}

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
                if event == 'basket_refuse':
                    lines[-1]['events'].extend(['basket_refuse_guarded', 'basket_refuse_defiant'])
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
    for sp in SPECIES:
        for event, tiers in GAP.items():
            for tier, variants in tiers.items():
                for i, (variant, frame) in enumerate(variants.items(), 1):
                    make(sp, tier, event, frame, context(sp, tier), lines, GAP_BASIS, prefix=f'g{i}_', variant=variant)
        for event, tiers in GAP_FILL.items():
            for tier, variants in tiers.items():
                for i, (variant, frame) in enumerate(variants.items(), 1):
                    make(sp, tier, event, frame, context(sp, tier), lines, GAP_FILL_BASIS, prefix=f'g{i}_', variant=variant)
        for event, tiers in RULE_AWE.items():
            for tier, frame in tiers.items():
                make(sp, tier, event, frame, context(sp, tier), lines, RULE_AWE_BASIS, prefix='w1_')
        for tier, frame in PROTECTION_TAUNT_TIERS.items():
            if tier == 'adult' and sp in ('reimu', 'marisa', 'alice', 'chen', 'patchouli'):
                continue  # their adult taunt is the older line above; the tic-bearing Myon keys and the two predators had none
            make(sp, tier, 'protect_taunt', frame, context(sp, tier), lines, PROTECTION_BASIS, prefix='retest_')
    for sp in SPECIES:
        for event, tiers in SEVERANCE.items():
            for tier, variants in tiers.items():
                ctx = context(sp, tier)
                for vi, (variant, frames) in enumerate(variants.items(), 1):
                    for fi, frame in enumerate(frames, 1):
                        make(sp, tier, event, frame, ctx, lines, SEVERANCE_BASIS, prefix=f'sv{vi}_{fi}_', variant=variant)
    for sp in SPECIES:
        for tier, variants in FOOD_STYLED.items():
            ctx = context(sp, tier)
            if sp in ('remilia', 'flandre'):
                for words in ctx.values():
                    words['p'] = ''  # Their food lines use the species ending and the baby cry.
            for i, (variant, events) in enumerate(variants.items(), 1):
                for event, frame in events.items():
                    make(sp, tier, event, frame, ctx, lines, FOOD_BASIS, prefix=f'v{i}_', variant=variant)
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

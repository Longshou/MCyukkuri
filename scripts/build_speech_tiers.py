"""Generate art/dialogue/speech-tiers.json: baby / juvenile / adult speech tiers, forms of address,
"counting stops at four" lines and the two Myon verbal tics.

Source of the worldview rules: komicolle 40720 floors 11518764 (baby babble, forms of address, "-sensei"
suffix), 11518780 (narrating own actions, counting collapses to "lots" at 4, vocabulary inherited at birth)
and 11518905 (Myon has two mutually exclusive tics, Patchouli may count past 4). Every line is a
project-original composition ('origin': 'project-original'); none is a Hakkei U line and none is voiced.

Run: python scripts/build_speech_tiers.py   (then python scripts/export_dialogue.py)
"""
import json
from pathlib import Path

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
                   end=dict(juvenile=('なのじぇ', '惹杰'), adult=('だぜ', '贼'))),
    'alice': dict(en='Alice', name=dict(baby=('ありちゅ', '爱丽丝'), juvenile=('ありちゅ', '爱丽丝'), adult=('ありす', '爱丽丝')),
                  cry=('ゆっ、ゆっ', '油、油', 'Yu, yu'), pre=('', '', ''),
                  end=dict(juvenile=('にょ', '哟'), adult=('わ', '呢'))),
    'chen': dict(en='Chen', name=dict(baby=('ちぇん', '橙'), juvenile=('ちぇん', '橙'), adult=('ちぇん', '橙')),
                 cry=('ゆ、にゃっ', '油、喵', 'Yu, nya'), pre=('', '', ''),
                 end=dict(juvenile=('だよぅ', '哟——'), adult=('だよー', '哟——'))),
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
                        ('kinbao', ('ちん、ちんぽっ', '金、金寶', 'Kin, kinpo'), ('ちんぽっ！', '金寶！', 'Kinpo! '))):
    SPECIES['myon.' + quirk] = dict(en='Myon', name=dict(baby=('みょん', '妖梦'), juvenile=('みょん', '妖梦'), adult=('みょん', '妖梦')),
                                    cry=cry, pre=pre, end=dict(juvenile=('みょ', '哦'), adult=('よ', '哦')), quirk=quirk)

# --- baby (akayuu): babble plus a few simple words ---------------------------------------------------------
# {c} babble cry, {nb} baby self-name
BABY = {
    'hello': ('{c}！', '{c}！', '{c}!'),
    'newborn': ('{c}！ {c}！', '{c}！{c}！', '{c}! {c}!'),
    'hatched': ('{c}……？ {c}！', '{c}……？{c}！', '{c}...? {c}!'),
    'hungry': ('{c}……まんま、まんま。', '{c}……饭饭，饭饭。', '{c}... Num-num, num-num.'),
    'fed': ('{c}♪ まんま、おいち！', '{c}♪ 饭饭，好七！', '{c}! Num-num, yummy!'),
    'chew': ('むーちゃ、むーちゃ。', '吧唧，吧唧。', 'Nom, nom.'),
    'sleep': ('{c}……ねんね……', '{c}……睡睡……', '{c}... nighty-night...'),
    'awake': ('{c}？ ……{c}！', '{c}？……{c}！', '{c}? ...{c}!'),
    'afraid': ('{c}ーっ！ こあい、こあい！', '{c}——！怕怕，怕怕！', '{c}! Scary, scary!'),
    'wounded': ('{c}ーっ！ いたい、いたい！', '{c}——！痛痛，痛痛！', '{c}! Ow, ow!'),
    'caregiver': ('{c}♪ ぎゅー……', '{c}♪ 抱抱……', '{c}! Hug...'),
    'want_nuzzle': ('すーり、すーり……{c}？', '蹭蹭，蹭蹭……{c}？', 'Nuzzle, nuzzle... {c}?'),
    'nuzzle': ('すーりすーり、{c}♪', '蹭蹭蹭蹭，{c}♪', 'Nuzzle-nuzzle, {c}!'),
    'nuzzle_done': ('{c}♪ ぽかぽか……', '{c}♪ 暖乎乎……', '{c}! So warm...'),
    'need_toilet': ('{c}……うんうん、うんうん……', '{c}……嗯嗯，嗯嗯……', '{c}... Poo-poo, poo-poo...'),
    'toilet_done': ('しゅっきち！', '清爽啦！', 'All better!'),
    'cold': ('{c}……さむさむ……', '{c}……冷冷……', '{c}... brr, brr...'),
    'lost_parent': ('{c}ーっ！ おやしゃ……おやしゃ……', '{c}——！亲亲油……亲亲油……', '{c}! Mama... papa...'),
    'idle': ('{c}～♪', '{c}～♪', '{c}~'),
    'food_found': ('まんま！ まんま！', '饭饭！饭饭！', 'Num-num! Num-num!'),
    'take_food': ('まんま、ちょーらい！', '饭饭，给我！', 'Num-num, gimme!'),
    'share': ('どーじょ……', '给你……', 'Here...'),
    'shy': ('{c}……はずかち……', '{c}……羞羞……', '{c}... shy...'),
    'treated': ('{c}♪ ありがちょ。', '{c}♪ 谢谢。', '{c}! Thank you.'),
    'washed': ('{c}♪ きれーきれー！', '{c}♪ 干净净！', '{c}! All clean!'),
    'toy': ('あしょぶ、あしょぶ！', '玩玩，玩玩！', 'Play, play!'),
    'mine': ('{nb}の！ {nb}の！', '{nb}的！{nb}的！', 'Mine! Mine!'),
    'peer': ('{c}？', '{c}？', '{c}?'),
    'known_peer': ('{c}♪ {c}♪', '{c}♪{c}♪', '{c}! {c}!'),
    'lost_accessory': ('おかじゃり……ない……{c}ーっ！', '饰饰……没有了……{c}——！', 'Hair thing... gone... {c}!'),
    'returned_accessory': ('おかじゃり！ {c}♪', '饰饰！{c}♪', 'Hair thing! {c}!'),
    'family_died': ('{c}……{c}……おきて……', '{c}……{c}……醒醒……', '{c}... {c}... wake up...'),
    # forms of address (a baby only knows the people in front of it)
    'addr_mother': ('おかーしゃ！ {c}♪', '麻麻！{c}♪', 'Mama! {c}!'),
    'addr_father': ('おとーしゃ！ {c}♪', '拔拔！{c}♪', 'Papa! {c}!'),
    'addr_human_kind': ('にんげんしゃ！ {c}♪', '人类先先！{c}♪', 'Human-sir! {c}!'),
    'addr_human_bro': ('おにーしゃ！ {c}♪', '大哥哥！{c}♪', 'Big brother! {c}!'),
    'addr_human_sis': ('おねーしゃ！ {c}♪', '大姐姐！{c}♪', 'Big sister! {c}!'),
    'addr_human_owner': ('かいぬししゃ！ {c}♪', '饲主先生！{c}♪', 'Owner-sir! {c}!'),
    'addr_sibling_elder': ('おねーちゃ！ {c}♪', '姐姐！{c}♪', 'Big sis! {c}!'),
    'addr_sibling_younger': ('いもーと……？ {c}♪', '妹妹……？{c}♪', 'Little sis...? {c}!'),
    # body details (komicolle 11518802 / 11518847): bitter and spicy food, letting go from fright, a baked sole, wanting the powder
    'bitter': ('{c}ーっ！ にがにが、ぺっぺっ！', '{c}——！苦苦，呸呸！', '{c}! Bitter, bitter, ptooey!'),
    'leak': ('{c}……あ……でちゃった……', '{c}……啊……漏出来了……', '{c}... oh... it came out...'),
    'sole_burned': ('{c}ーっ！ あんよ、あちゅい、あちゅい！', '{c}——！脚脚，烫烫，烫烫！', '{c}! My foot, hot, hot!'),
    'craving': ('{c}……こなこな……ほちい……', '{c}……粉粉……想要……', '{c}... powder... want...'),
}
# baby counting frames: S is the counted sequence
BABY_COUNT = {
    'count_food': ('ごはん、{S}！', '饭饭，{S}！', 'Food: {S}!'),
    'count_stock': ('おうちのまんま、{S}！', '家里的饭饭，{S}！', 'Home food: {S}!'),
    'count_children': ('ちびちゃ、{S}！', '小宝宝，{S}！', 'Babies: {S}!'),
    'count_peers': ('おともだち、{S}！', '朋友，{S}！', 'Friends: {S}!'),
}

# --- juvenile (koyuu): half-mature, clearer words, "-sensei" nouns, narrates own actions -------------------
# {p} species prefix, {e} species ending, {n} self-name, {N} English name
JUVENILE = {
    'hello': ('{p}{n}はここにいる{e}。ゆっくりしていってね！', '{p}{n}在这里{e}。请油库里一下吧！', '{p}{N} is right here. Take it slow with me!'),
    'hungry': ('{p}{n}、おなかがすいた{e}。ごはんさんはどこ？', '{p}{n}肚子饿了{e}。食物先生在哪里？', '{p}{N} is hungry. Where is Mr. Food?'),
    'fed': ('{p}ごはんさん、おいしい{e}。しあわせー！', '{p}食物先生好好吃{e}。好幸福——！', '{p}Mr. Food is tasty. So happy!'),
    'sleep': ('{p}{n}はもうねる{e}。おやすみ。', '{p}{n}要睡了{e}。晚安。', '{p}{N} is going to sleep now. Good night.'),
    'awake': ('{p}ふわぁ……{n}、おきた{e}。', '{p}呼啊……{n}醒了{e}。', '{p}Yawn... {N} is awake.'),
    'afraid': ('{p}こわい{e}！ {n}はにげる！', '{p}好可怕{e}！{n}要逃了！', '{p}Scary! {N} is running away!'),
    'wounded': ('{p}いたい{e}……！ {n}、けがをした……', '{p}好痛{e}……！{n}受伤了……', '{p}It hurts! {N} got hurt...'),
    'lost_parent': ('{p}おかーしゃんも、おとーしゃんも、どこ{e}……？', '{p}妈妈和爸爸都在哪里{e}……？', '{p}Where are Mama and Papa...?'),
    'caregiver': ('{p}そばにいてくれて、うれしい{e}。', '{p}你陪在旁边，好开心{e}。', "{p}I'm glad you're by my side."),
    'food_found': ('{p}ごはんさんをみつけた{e}！ ……いっしょにたべる？', '{p}找到食物先生了{e}！……要一起吃吗？', '{p}Found Mr. Food! Want to eat together?'),
    'share': ('{p}ちょっとだけわけてあげる{e}。', '{p}分你一点点{e}。', "{p}I'll share a little with you."),
    'lesson_learned': ('{p}わかった{e}！ {n}、おぼえた！', '{p}明白了{e}！{n}记住了！', '{p}I get it! {N} remembered!'),
    'lost_accessory': ('{p}かざりさんが、ない{e}……！', '{p}头饰先生不见了{e}……！', '{p}My hair thing is gone...!'),
    'returned_accessory': ('{p}かざりさん、おかえり{e}！', '{p}头饰先生，欢迎回来{e}！', '{p}Welcome back, Mr. Hair Thing!'),
}

# --- shared juvenile / adult body-detail lines (same events as the baby ones above) --------------------------
BODY = {
    'bitter': ('{p}にがい{e}……！ ぺっ、ぺっ！ ……{n}、これはきらい{e}。', '{p}好苦{e}……！呸、呸！……{n}不喜欢这个{e}。', '{p}Bitter...! Ptooey! {N} does not like this.'),
    'leak': ('{p}あ……でちゃった{e}……。みないで……', '{p}啊……漏出来了{e}……。别看……', '{p}Oh... it came out... Please do not look...'),
    'sole_burned': ('{p}あしが……あしがうごかない{e}……！', '{p}脚……脚动不了了{e}……！', '{p}My foot... my foot will not move...!'),
    'craving': ('{p}あのこなさんが……ほしい{e}……', '{p}想要……那个粉末先生{e}……', '{p}I want... that powder...'),
}

# --- shared juvenile / adult: addresses, narration, counting ------------------------------------------------
# {t} the term of address
ADDRESS = {
    'addr_mother': ('{p}{t}、こっちにいる{e}！', '{p}{t}，在这边{e}！', '{p}{t}, over here!'),
    'addr_father': ('{p}{t}、いっしょにいく{e}！', '{p}{t}，一起走吧{e}！', "{p}{t}, let's go together!"),
    'addr_partner': ('{p}{t}、いっしょにゆっくりする{e}。', '{p}{t}，一起慢慢来吧{e}。', "{p}{t}, let's take it slow together."),
    'addr_child': ('{p}{t}、こっちにくる{e}。', '{p}{t}，到这边来{e}。', '{p}{t}, come over here.'),
    'addr_sibling_elder': ('{p}{t}、おいていかない{e}！', '{p}{t}，等等我{e}！', '{p}{t}, wait for me!'),
    'addr_sibling_younger': ('{p}{t}、ついてくる{e}。', '{p}{t}，跟着我{e}。', '{p}{t}, follow me.'),
    'addr_leader': ('{p}{t}、みんなでごはんをさがす{e}。', '{p}{t}，大家一起去找食物{e}。', "{p}{t}, we'll all look for food together."),
    'addr_human_kind': ('{p}{t}、ゆっくりしていってね！', '{p}{t}，请油库里一下吧{e}！', '{p}{t}, take it slow with us!'),
    'addr_human_bro': ('{p}{t}、いっしょにあそぶ{e}！', '{p}{t}，陪我玩{e}！', '{p}{t}, play with me!'),
    'addr_human_sis': ('{p}{t}、ごはんをもらう{e}！', '{p}{t}，给我食物{e}！', '{p}{t}, give me some food!'),
    'addr_human_owner': ('{p}{t}、{n}はいい子にしてた{e}！', '{p}{t}，{n}一直很乖{e}！', '{p}{t}, {N} has been good!'),
    'addr_human_hostile_old': ('{p}{t}、あっちにいって！', '{p}{t}快走开{e}！', '{p}Go away, {t}!'),
    'addr_human_hostile_man': ('{p}{t}、ごはんをおいてって！', '{p}{t}，把食物交出来{e}！', '{p}Hand over the food, {t}!'),
    'addr_predator_remilia': ('{p}れみりゃがきた{e}！ こっちにこないで！', '{p}蕾米莉亚来了{e}！别过来！', "{p}Remilia's coming! Stay away!"),
    'addr_predator_flandre': ('{p}ふらんがいる{e}……！ みつからないように……', '{p}芙兰在那里{e}……！别被发现……', "{p}Flandre's there...! Don't get noticed..."),
    'thing_san': ('{p}これは{n}のたからものさん{e}！', '{p}这是{n}的宝物先生{e}！', "{p}This is {N}'s treasure, Mr. Treasure!"),
    # the yukkuri says out loud what it is doing (komicolle 11518780)
    'narrate_steal': ('{p}{n}はこっそりごはんさんをとる{e}。', '{p}{n}要偷偷拿食物先生{e}。', '{p}{N} is going to sneak the food.'),
    'narrate_flee': ('{p}{n}はこっそりにげる{e}。', '{p}{n}要悄悄逃跑{e}。', '{p}{N} is going to quietly run away.'),
    'narrate_hide': ('{p}みえない{e}、{n}はもうかくれた{e}。', '{p}看不见哦，{n}已经躲起来了{e}。', "{p}You can't see me, {N} is already hiding."),
}
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
    'count_peers': ('{p}なかまは……{S}！', '{p}伙伴有……{S}！', '{p}Friends: {S}!'),
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


def make(key, tier, event, frame, ctx, lines):
    species, _, quirk = key.partition('.')
    ident = f'{key}.{tier}.s_{event}'
    jp = frame[0].format(**ctx['jp'])
    zh = frame[1].format(**ctx['zh'])
    en = frame[2].format(**ctx['en'])
    lines.append({'id': ident, 'species': species, 'age': tier, **({'quirk': quirk} if quirk else {}),
                  'events': [event], 'japanese': jp, 'chinese': zh, 'english': en, 'origin': 'project-original',
                  'basis': 'komicolle 40720 11518764/11518780/11518905 speech rules; project-original wording, unvoiced'})


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
            make(sp, 'baby', event, frame, context(sp, 'baby'), lines)
        for base, frame in BABY_COUNT.items():
            for b in buckets('baby', sp.partition('.')[0]):
                make(sp, 'baby', f'{base}_{b}', frame, context(sp, 'baby', seq=counted(base, b, 'baby', sp.partition('.')[0])), lines)
        for tier in ('juvenile', 'adult'):
            if tier == 'juvenile':
                for event, frame in JUVENILE.items():
                    make(sp, tier, event, frame, context(sp, tier), lines)
            for event, frame in BODY.items():
                make(sp, tier, event, frame, context(sp, tier), lines)
            for event, frame in ADDRESS.items():
                term = TERMS[tier].get(event) or COMMON_TERMS.get(event)
                make(sp, tier, event, frame, context(sp, tier, term=term), lines)
            for base, frame in COUNT_FRAMES.items():
                for b in buckets(tier, sp.partition('.')[0]):
                    make(sp, tier, f'{base}_{b}', frame, context(sp, tier, seq=counted(base, b, tier, sp.partition('.')[0])), lines)
    ids = [l['id'] for l in lines]
    assert len(ids) == len(set(ids))
    doc = {'origin': 'project-original', 'status': 'Unvoiced project-original subtitles; wording awaits listening review.',
           'tiers': {'baby': 'Stage.BABY', 'juvenile': 'Stage.JUVENILE', 'adult': 'Stage.ADULT',
                     'young': 'legacy childish tier (kept; fallback for baby/juvenile and for childish-voiced adults)'},
           'counting': {'ordinary_cap': ORDINARY_CAP, 'baby_cap': BABY_CAP, 'patchouli_cap': PATCHOULI_CAP, 'bases': list(COUNT_BASES)},
           'lines': lines}
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(len(lines), 'lines')


if __name__ == '__main__':
    main()

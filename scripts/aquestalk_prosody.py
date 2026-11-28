"""Native AquesTalk1 #> emotion prosody compiler for Yukkuri runtime dialogue."""
import re

PROSODY_VERSION = 'sharp-emotion-v1'

# Multi-character kanji spans first, covering every kanji appearing in runtime corpus.
KANJI_READINGS = (
    ('お見知りおき', 'おみしりおき'),
    ('当たり前', 'あたりまえ'),
    ('願い下げ', 'ねがいさげ'),
    ('大丈夫', 'だいじょうぶ'),
    ('大収穫', 'だいしゅうかく'),
    ('都会派', 'とかいは'),
    ('安らかに', 'やすらかに'),
    ('新しい', 'あたらしい'),
    ('本当に', 'ほんとうに'),
    ('接すれば', 'せっすれば'),
    ('お行儀', 'おぎょうぎ'),
    ('行っちゃった', 'いっちゃった'),
    ('ひと休み', 'ひとやすみ'),
    ('お飾り', 'おかざり'),
    ('お相手', 'おあいて'),
    ('見た目', 'みため'),
    ('仕打ち', 'しうち'),
    ('気まずい', 'きまずい'),
    ('気持ち', 'きもち'),
    ('こんな目', 'こんなめ'),
    ('いい子', 'いいこ'),
    ('似合う', 'にあう'),
    ('観楼剣', 'かんろうけん'),
    ('礼儀', 'れいぎ'),
    ('油断', 'ゆだん'),
    ('努力', 'どりょく'),
    ('証拠', 'しょうこ'),
    ('勲章', 'くんしょう'),
    ('食料', 'しょくりょう'),
    ('一緒', 'いっしょ'),
    ('上品', 'じょうひん'),
    ('今日', 'きょう'),
    ('元気', 'げんき'),
    ('半分', 'はんぶん'),
    ('大事', 'だいじ'),
    ('空気', 'くうき'),
    ('納得', 'なっとく'),
    ('部屋', 'へや'),
    ('行儀', 'ぎょうぎ'),
    ('何して', 'なにして'),
    ('聞こえ', 'きこえ'),
    ('聞きたい', 'ききたい'),
    ('聞く', 'きく'),
    ('落とした', 'おとした'),
    ('確かめ', 'たしかめ'),
    ('暖かい', 'あたたかい'),
    ('楽しい', 'たのしい'),
    ('食べ', 'たべ'),
    ('遊ぼう', 'あそぼう'),
    ('遊ぶ', 'あそぶ'),
    ('起きて', 'おきて'),
    ('言う', 'いう'),
    ('見て', 'みて'),
    ('続き', 'つづき'),
    ('眠い', 'ねむい'),
    ('痛い', 'いたい'),
    ('汚れ', 'よごれ'),
    ('探す', 'さがす'),
    ('届く', 'とどく'),
    ('少し', 'すこし'),
    ('寝た', 'ねた'),
    ('寒い', 'さむい'),
    ('大き', 'おおき'),
    ('増えた', 'ふえた'),
    ('動いて', 'うごいて'),
    ('動く', 'うごく'),
    ('会えた', 'あえた'),
    ('会う', 'あう'),
    ('休む', 'やすむ'),
    ('飾り', 'かざり'),
    ('親', 'おや'),
    ('外', 'そと'),
    ('声', 'こえ'),
    ('何', 'なに'),
    ('方', 'かた'),
    ('子', 'こ'),
    ('目', 'め'),
    ('休', 'やす'),
    ('食', 'た'),
    ('遊', 'あそ'),
    ('起', 'お'),
    ('見', 'み'),
    ('言', 'い'),
    ('聞', 'き'),
    ('落', 'お'),
    ('行', 'い'),
    ('汚', 'よご'),
    ('痛', 'いた'),
    ('眠', 'ねむ'),
    ('確', 'たし'),
    ('続', 'つづ'),
    ('探', 'さが'),
    ('届', 'とど'),
    ('少', 'すこ'),
    ('寝', 'ね'),
    ('寒', 'さむ'),
    ('暖', 'あたた'),
    ('楽', 'たの'),
    ('大', 'おお'),
    ('増', 'ふ'),
    ('動', 'うご'),
    ('会', 'あ'),
    ('下', 'さ'),
    ('気', 'き'),
    ('願', 'ねが'),
)

SLEEPY_EVENTS = {'sleep', 'lane_blocked_sleep', 'spray_drowsy'}
URGENT_EVENTS = {
    'afraid', 'wounded', 'hurt', 'hurt_flee', 'hurt_freeze', 'hurt_plea', 'hurt_tame_plea',
    'thunder', 'dying', 'dying_kin', 'kin_dying', 'sole_burned', 'boat_rock_startle',
    'house_startled', 'lead_alarm', 'rapem_alarm', 'rapem_flee', 'rapem_victim',
    'rapem_victim_climax', 'predator_screech', 'sever_shock', 'sibling_sever_scared',
    'rule_gasp', 'protect_child_flee', 'junk_afraid', 'doll_snatch_flee', 'doll_stolen',
    'hat_drifting', 'self_rescue_slip', 'deficient_babble', 'airborne', 'leak', 'rain_run',
    'heat_start', 'shaken_after',
}
ANGRY_EVENTS = {
    'fume', 'puff', 'food_complain', 'food_grudge', 'food_spit', 'beg_curse', 'beg_grudge',
    'beg_refuse', 'beg_robbed', 'child_curse_parent', 'driven_curse', 'protect_angered',
    'protect_taunt', 'predator_defy', 'hurt_defy', 'doll_snatch_defy', 'basket_refuse_defiant',
    'refuse', 'wash_refuse', 'heat_refuse', 'hunt_refuse', 'offer_refuse', 'snack_refuse',
    'lesson_refused', 'sever_protest', 'sever_cold_scoff', 'sibling_sever_protest',
    'scorn_hurt', 'mold_scorn', 'mold_resist', 'hatless_jeer', 'hatless_monster',
    'hatless_sanction', 'hatless_sanction_drive', 'hatless_scorn', 'hatless_shoo',
    'contest_faceoff', 'contest_tussle', 'human_food_demand', 'sukkiri_defiant', 'sulk_huff',
    'vow_blame_parent', 'partner_blame', 'factory_receive_insult', 'badge_mock',
    'badge_retort', 'badge_plaza_retort', 'badge_envy_snark', 'junk_indignant',
    'kanroken_draw', 'settlement_drive', 'rapem_act', 'rapem_hunt', 'rapem_buffet',
    'pit_order', 'wait_impatient', 'renamed_upset', 'mood_bad',
}
SAD_EVENTS = {
    'hungry', 'famine', 'cold', 'mourn_miss', 'mourn_recall', 'mourn_sob', 'family_died',
    'farewell_cry', 'farewell_say_teary', 'drive_child_tearful', 'driven_farewell',
    'sever_plea', 'sever_grievance', 'sever_farewell', 'sever_look_back',
    'sibling_sever_cry', 'sibling_sever_uneasy', 'plea_sad', 'plea_spare', 'handover_plea',
    'wild_plea_keep', 'mold_child_cry', 'mold_plea', 'mold_sorrow', 'mold_sorrow_mate',
    'beg_cry', 'beg_plead_again', 'lost_accessory', 'lost_parent', 'hat_out_of_reach',
    'kanroken_lost', 'search_family', 'give_up_search', 'lane_giveup', 'offer_grief',
    'exam_fail', 'exam_hide_fail', 'exam_sorry', 'caught_guilty', 'scolded', 'rule_beg',
    'rule_cower', 'rule_guilty_shy', 'rule_sulk', 'rule_tighten_sad', 'shop_sold_sad',
    'sumo_lose', 'contest_lost', 'food_reluctant', 'bitter', 'stench', 'uneasy', 'shy',
    'basket_refuse_weary', 'lead_struggle_tired', 'lead_spent', 'request_sulk', 'badge_pity',
    'badge_taken', 'left_ashore_cold', 'sever_cold_mutter', 'sever_cold_leave',
    'sibling_sever_cold',
}
CURIOUS_EVENTS = {
    'badge_ask', 'ask_food', 'ask_play', 'ask_snack', 'ask_walk', 'ask_water',
    'exam_unsure', 'junk_unsure', 'house_peek', 'unrecognized',
}
PROUD_EVENTS = {
    'badge_proud', 'shop_sold_proud', 'contest_won_bed', 'contest_won_food',
    'contest_won_toy', 'sumo_win', 'home_declare', 'grown', 'exam_pass',
    'lesson_praised', 'deibu_developed', 'doll_snatch_claim',
}
CHEERFUL_EVENTS = {
    'fed', 'food_found', 'food_delight', 'glow', 'glow_hum', 'awake', 'nuzzle',
    'nuzzle_done', 'share', 'caregiver', 'known_peer', 'date_fond', 'date_meet',
    'courtship', 'birth', 'hatched', 'newborn', 'house_hatched', 'hat_recovered',
    'accessory_mended', 'returned_accessory', 'kanroken_found', 'walk_enjoy', 'toy',
    'kotatsu_warm', 'snack_eat', 'hunt_sweet', 'beg_thanks', 'badge_thanks',
    'badge_admire', 'badge_pinned', 'junk_thanks', 'request_thanks', 'rule_grateful',
    'rule_thanks', 'permit_glad', 'mood_good', 'braid_rescue_safe',
    'braid_rescue_safe_ashore', 'self_rescue_safe', 'rapem_victim_safe', 'lead_relief',
    'treated', 'washed', 'toilet_done', 'left_ashore_warm', 'farewell_bless',
}

SMALL_AND_MORAS = set('っんーゃゅょぁぃぅぇぉ')
SMALL_COMBO = set('ゃゅょぁぃぅぇぉ')
SKIP_ACCENT_TAILS = {'だぜ', 'のぜ', 'のじぇ', 'わ', 'よ', 'ね', 'なの', 'だ', 'ぜ'}
KEEP_HA = {'とかいは', 'あは', 'はは'}

LONG_WORD_REPLACEMENTS = (
    ('とうじょう', 'とーじょー'),
    ('きょう', 'きょー'),
    ('おはよう', 'おはよー'),
    ('ありがとう', 'ありがとー'),
    ('ごちそうさま', 'ごちそーさま'),
    ('どうぞ', 'どーぞ'),
    ('やあ', 'やー'),
    ('おう', 'おー'),
)

EMOTION_TABLE = {
    'neutral':  {'label': '平静日常 · 舒缓', 'speed_delta': -10, 'pitch_delta': 0,  'peak_dbfs': -2.8},
    'cheerful': {'label': '开心满足',        'speed_delta': -4,  'pitch_delta': 2,  'peak_dbfs': -2.2},
    'proud':    {'label': '自夸得意',        'speed_delta': -2,  'pitch_delta': 1,  'peak_dbfs': -2.2},
    'curious':  {'label': '好奇发问',        'speed_delta': -8,  'pitch_delta': 1,  'peak_dbfs': -2.6},
    'angry':    {'label': '嚣张生气',        'speed_delta': 10,  'pitch_delta': -2, 'peak_dbfs': -1.2},
    'sad':      {'label': '委屈低落',        'speed_delta': -22, 'pitch_delta': -3, 'peak_dbfs': -6.2},
    'sleepy':   {'label': '困倦入睡',        'speed_delta': -30, 'pitch_delta': -3, 'peak_dbfs': -7.2},
    'urgent':   {'label': '受惊慌张',        'speed_delta': 12,  'pitch_delta': 5,  'peak_dbfs': -1.0},
}


def classify_emotion(events, variant, japanese):
    """Map dialogue metadata to one of 8 Round-9 directed emotions."""
    evs = [str(e) for e in (events or ())]
    ev_set = set(evs)
    var = str(variant or '')

    if ev_set & SLEEPY_EVENTS or any('sleep' in e or 'drowsy' in e for e in evs):
        return 'sleepy'
    if (
        ev_set & URGENT_EVENTS
        or any(tok in e for e in evs for tok in ('alarm', 'startle', 'scared', 'shock', 'flee', 'burned', 'screech', 'dying'))
        or (not evs and 'ゆぴー' in japanese)
    ):
        return 'urgent'
    if ev_set & ANGRY_EVENTS or var in ('cocky', 'stray_cocky'):
        return 'angry'
    if ev_set & SAD_EVENTS or var in ('timid', 'stray_timid'):
        return 'sad'
    if japanese.rstrip().endswith(('？', '?')) or (ev_set & CURIOUS_EVENTS):
        return 'curious'
    if ev_set & PROUD_EVENTS or var in ('proud', 'pet_proud'):
        return 'proud'
    if ev_set & CHEERFUL_EVENTS or var in ('warm', 'pet_warm'):
        return 'cheerful'
    return 'neutral'


def kata_to_hira(text):
    out = []
    for ch in text:
        code = ord(ch)
        out.append(chr(code - 0x60) if 0x30A1 <= code <= 0x30F6 else ch)
    return ''.join(out)


def preprocess_japanese(text):
    value = text
    for src, dst in KANJI_READINGS:
        value = value.replace(src, dst)
    value = value.replace('　', ' ')
    value = re.sub(r'[「」『』（）\(\)\[\]【】・･\*＊"“”\'’‘]', '', value)
    value = value.replace('!', '！').replace('?', '？').replace(',', '、').replace('.', '。')
    value = re.sub(r'[~～〜—–−\-]', 'ー', value)
    value = value.replace('゛', '').replace('゜', '').replace('♪', '')
    return value


def norm_token(tok):
    t = kata_to_hira(tok.strip())
    t = t.replace('ゔ', 'ぶ').replace('ゐ', 'い').replace('ゑ', 'え').replace('ヶ', 'か').replace('ヵ', 'か')
    t = t.replace('ぢ', 'じ').replace('づ', 'ず')
    t = re.sub(r'[^ぁ-んー]', '', t)
    if not t:
        return ''
    if t == 'こんにちは':
        t = 'こんにちわ'
    elif t == 'へ':
        t = 'え'
    elif t.endswith('は') and len(t) >= 2 and t not in KEEP_HA:
        t = t[:-1] + 'わ'
    t = t.replace('を', 'お')
    for src, dst in LONG_WORD_REPLACEMENTS:
        t = t.replace(src, dst)
    t = t.replace('ゆぅ', 'ゆう').replace('よぅ', 'よー').replace('だょ', 'だよ')
    t = re.sub(r'([あかさたなはまやらわがざだばぱ])ぁ+', r'\1ー', t)
    t = re.sub(r'([いきしちにひみりぎじびぴ])ぃ+', r'\1ー', t)
    t = re.sub(r'([うくすつぬふむゆるぐずぶぷ])ぅ+', r'\1ー', t)
    t = re.sub(r'([えけせてねへめれげぜでべぺ])ぇ+', r'\1ー', t)
    t = re.sub(r'([おこそとのほもよろをごぞどぼぽ])ぉ+', r'\1ー', t)
    # Collapse yoon + duplicate small vowel (e.g. しゅぅ -> しゅー, ひょぉ -> ひょー)
    t = re.sub(r'([ゃゅょぁぃぅぇぉ])([ぁぃぅぇぉ]+)', r'\1ー', t)
    # Remove any remaining non-yoon small vowel after incompatible kana
    t = re.sub(r'(?<![しちじてでふうに])[ぁぃぅぇぉ]+', '', t)
    # Small っ cannot appear right before ー or at token start when isolated
    t = re.sub(r'っー+っ', 'っ', t)
    t = re.sub(r'っー+', 'っ', t)
    t = re.sub(r'ー{3,}', 'ーー', t)
    return t


def split_raw_clauses(ja_pre):
    parts = re.split(r'(……+|…+|！|？|。)', ja_pre.strip())
    clauses = []
    cur = ''
    for p in parts:
        if not p:
            continue
        if p in ('！', '？', '。') or p.startswith('…'):
            if cur.strip():
                clauses.append((cur.strip(), '…' if p.startswith('…') else p))
                cur = ''
            elif clauses and p in ('！', '？'):
                clauses[-1] = (clauses[-1][0], p)
        else:
            cur += p
    if cur.strip():
        clauses.append((cur.strip(), '。'))

    final = []
    for idx, (text, endp) in enumerate(clauses):
        if '、' in text:
            head, tail = text.split('、', 1)
            if idx == 0 and len(head.strip()) <= 5 and tail.strip():
                final.append((head.strip(), ','))
                final.append((tail.strip(), endp))
                continue
            subparts = [s.strip() for s in text.split('、') if s.strip()]
            for si, sp in enumerate(subparts):
                final.append((sp, ',' if si < len(subparts) - 1 else endp))
            continue
        final.append((text, endp))
    return [(t, p) for t, p in final if t]


def count_moras(tok):
    return sum(1 for c in tok if c not in SMALL_COMBO and c != "'")


def insert_accent(tok, target_mora):
    if not tok or "'" in tok or count_moras(tok) < 2:
        return tok
    mora = 0
    for i, ch in enumerate(tok):
        if ch not in SMALL_COMBO:
            mora += 1
        nxt = i + 1
        if nxt < len(tok) and tok[nxt] in SMALL_COMBO:
            continue
        if mora >= target_mora:
            while nxt < len(tok) and tok[nxt] in SMALL_AND_MORAS:
                nxt += 1
            if 0 < nxt < len(tok):
                return tok[:nxt] + "'" + tok[nxt:]
            return tok
    return tok


def sanitize_clause_payload(body, mark):
    payload = re.sub(r'/+', '/', body).strip('/;')
    # AquesTalk1 #> cannot start with 'っ', 'ん', 'ー', or small combo kana
    payload = payload.lstrip("っんーゃゅょぁぃぅぇぉ'")
    payload = re.sub(r'([/;])([っーゃゅょぁぃぅぇぉ\'])+', r'\1', payload)
    payload = re.sub(r'/+', '/', payload).replace('/;', ';').replace(';/', '/').strip('/;')
    # Also ensure ' never precedes special moras or delimiters
    payload = re.sub(r"'(?=[っんーゃゅょぁぃぅぇぉ/;。?、]|$)", '', payload)
    payload = re.sub(r'っー+', 'っ', payload)
    if not payload or not re.search(r'[ぁ-ん]', payload):
        return ''
    # Guard trailing unsupported small vowel after non-yoon
    if payload.endswith(('ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')) and (len(payload) < 2 or payload[-2] not in 'しちじてでふうに'):
        payload = payload[:-1] + 'ー'
    return payload + mark


def compile_emotion_clauses(ja_text, emo):
    """Compile spaced/raw Japanese text + emotion -> list of (koe_no_sharp, speed_d, pitch_d, peak_db, gap_ms, fade_ms)."""
    pre = preprocess_japanese(ja_text)
    raw_clauses = split_raw_clauses(pre)
    normed = []
    for text, endp in raw_clauses:
        toks = [norm_token(t) for t in text.split() if norm_token(t)]
        if toks:
            normed.append((toks, endp))

    if not normed:
        fallback = norm_token(pre) or 'ゆっ'
        normed = [([fallback], '。')]

    out = []
    total = len(normed)
    for idx, (toks, endp) in enumerate(normed):
        is_first = (idx == 0)
        is_last = (idx == total - 1)

        if emo == 'neutral':
            body = '/'.join(toks)
            mark = '。'
            gap = 200 if (is_first and total > 1) else (210 if not is_last else 0)
            spd_d, pit_d, pk, fade = -10, 0, -2.8, 0

        elif emo == 'cheerful':
            adj = list(toks)
            if is_last and adj[-1].endswith('ね') and not adj[-1].endswith('ねー'):
                adj[-1] = adj[-1] + 'ー'
            body = '/'.join(adj)
            mark = '。'
            spd_d, pit_d, pk = (-2, 3, -2.0) if (is_first and endp in ('！', '♪')) else (-4, 2, -2.2)
            gap, fade = (150 if not is_last else 0), 0

        elif emo == 'proud':
            body = '/'.join(toks)
            mark = '。'
            spd_d, pit_d, pk = (0, 2, -2.0) if is_first else (-2, 1, -2.2)
            gap, fade = (165 if not is_last else 0), 0

        elif emo == 'curious':
            body = '/'.join(toks)
            if endp == '？':
                mark, spd_d, pit_d, pk, gap, fade = '?', -8, 1, -2.6, (0 if is_last else 165), 0
            else:
                mark, spd_d, pit_d, pk, gap, fade = '。', 0, 2, -2.4, (165 if not is_last else 0), 0

        elif emo == 'angry':
            acc_toks = [
                t if (t in SKIP_ACCENT_TAILS or count_moras(t) < 2)
                else insert_accent(t, 1 if count_moras(t) <= 3 else 2)
                for t in toks
            ]
            body = '/'.join(acc_toks)
            last_str = ''.join(toks)
            mark = '?' if (is_last and any(last_str.endswith(sfx) for sfx in ('なの', 'のか', 'ないの', 'だの'))) else '。'
            if is_first and total > 1:
                spd_d, pit_d, pk, gap, fade = 6, 0, -1.2, 155, 0
            elif not is_last:
                spd_d, pit_d, pk, gap, fade = 10, -2, -1.5, 110, 0
            else:
                spd_d, pit_d, pk, gap, fade = 12, -2, -1.1, 0, 0

        elif emo == 'urgent':
            body = '/'.join(toks)
            raw_joined = ''.join(toks)
            moras = count_moras(raw_joined)
            if is_first and total > 1:
                mark, spd_d, pit_d, pk, gap, fade = '。', 8, 6, -1.0, 115, 0
            elif not is_last:
                mark = '?' if (moras >= 3 and not raw_joined.endswith('っ')) else '。'
                spd_d, pit_d, pk, gap, fade = 12, 4, -1.2, 95, 0
            else:
                mark = '?' if not raw_joined.endswith('っ') else '。'
                spd_d, pit_d, pk, gap, fade = 16, 6, -1.0, 0, 0

        elif emo == 'sad':
            adj = list(toks)
            adj[0] = insert_accent(adj[0], 1)
            if is_first and total > 1:
                if len(adj[0]) <= 3 and not adj[0].endswith(('ー', 'っ')):
                    adj[0] += 'ー'
                body = adj[0]
                spd_d, pit_d, pk, gap, fade = -18, -2, -5.0, 250, 0
            elif not is_last:
                body = ';'.join(adj)
                spd_d, pit_d, pk, gap, fade = -22, -3, -6.2, 280, 0
            else:
                if not adj[-1].endswith(('ー', 'っ', 'ん')):
                    adj[-1] += 'ー'
                body = ';'.join(adj)
                spd_d, pit_d, pk, gap, fade = -26, -3, -7.6, 0, 260
            mark = '。'

        elif emo == 'sleepy':
            adj = list(toks)
            if is_first and total > 1:
                body = adj[0] if adj[0].endswith(('っ', 'ん')) else (adj[0].rstrip('ー') + 'ーー')
                spd_d, pit_d, pk, gap, fade = -24, -2, -5.5, 280, 0
            elif not is_last:
                adj[0] = insert_accent(adj[0], 1)
                body = ';'.join(adj)
                spd_d, pit_d, pk, gap, fade = -30, -3, -7.2, 340, 0
            else:
                adj[0] = insert_accent(adj[0], 1)
                if not adj[-1].endswith(('ー', 'っ', 'ん')):
                    adj[-1] = adj[-1] + 'ーー'
                else:
                    adj[-1] = adj[-1].rstrip('ー') + 'ーー'
                body = ';'.join(adj)
                spd_d, pit_d, pk, gap, fade = -36, -4, -9.2, 0, 400
            mark = '。'

        else:
            raise ValueError(f'Unknown emotion {emo!r}')

        koe = sanitize_clause_payload(body, mark)
        if koe:
            out.append((koe, spd_d, pit_d, pk, gap, fade))

    if not out:
        out.append(('ゆっ。', 0, 0, -3.0, 0, 0))

    # Strip trailing gap after the final surviving clause
    last_koe, last_sd, last_pd, last_pk, _g, last_fd = out[-1]
    out[-1] = (last_koe, last_sd, last_pd, last_pk, 0, last_fd)

    for koe, _sd, _pd, _pk, _g, _fd in out:
        assert re.fullmatch(r"[ぁ-んー'/;、。?]+", koe), f'Illegal #> payload: {koe!r} (from {ja_text!r})'
        assert "っー" not in koe and "'っ" not in koe and "'ー" not in koe, f'Bad #> sequence: {koe!r}'

    return out

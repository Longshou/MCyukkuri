#!/usr/bin/env python3
"""Read-only check: every lang key the exam items and the shop/exam/keeper/badge screens can build exists in zh_cn, en_us and ja_jp.

Scans pet/exam/items/*.java (Scene.P prefix, log(c, id, ...) outcomes, the oral word list, interview questions/answers), the literal keys of the
UI classes, ExamSession.REASONS, and reports banned wording per language (terminology table of the pet-shop UI). Does not launch Minecraft.
Usage: python scripts/check_ui_lang_keys.py        (exit code 1 on any problem)
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'src/main/java/io/github/longshou/mcyukkuri'
LANGS = ('zh_cn', 'en_us', 'ja_jp')
UI_FILES = ['client/ShopScreen.java', 'client/ExamScreen.java', 'client/KeeperScreen.java', 'client/BadgeScreen.java', 'client/GuiKit.java',
            'pet/exam/ExamMenu.java', 'pet/exam/ExamSession.java', 'pet/exam/SimpleCheckItem.java', 'pet/PetLessons.java', 'badge/BadgeMenu.java', 'badge/BadgeItem.java']
PREFIXES = ('exam.mcyukkuri.', 'pet.mcyukkuri.', 'shop.mcyukkuri.', 'badge.mcyukkuri.', 'title.mcyukkuri.', 'human.mcyukkuri.', 'predator.mcyukkuri.')
BANNED = {
    'zh_cn': ['社会性', '社交课', '昇格', '考核', '运输箱', '单体运输篮', '助眠喷雾', 'お見合い', '餡統', '宠物油', '金银章手册'],
    'en_us': ['Single Yukkuri Carrier', 'Sleep spray', 'Social lesson'],
    'ja_jp': ['睡眠スプレー', '店員', 'キャリア', '血統', '無断出産', '社交レッスン', '社交の授業'],
}


def read_lang(lang):
    return json.loads((ROOT / f'src/main/resources/assets/mcyukkuri/lang/{lang}.json').read_text(encoding='utf-8'))


def item_keys():
    """(key, origin) built by the exam item classes."""
    keys = []
    prefix = 'exam.mcyukkuri.item.'
    for f in sorted((JAVA / 'pet/exam/items').glob('*.java')):
        src = f.read_text(encoding='utf-8')
        for m in re.finditer(r'\bP \+ "([a-z_.]+)"', src):
            lit = m.group(1)
            if not lit.endswith('.'): keys.append((prefix + lit, f.name))
        for m in re.finditer(r'\blog\(c, "([a-z_]+)"', src):
            keys += [(f'{prefix}{m.group(1)}.{o}', f.name) for o in ('success', 'partial', 'fail')]
        words = re.search(r'WORDS = \{([^}]*)\}', src)
        if words:
            for w in re.findall(r'"([a-z]+)"', words.group(1)):
                keys += [(f'{prefix}oral_read.word.{w}', f.name), (f'{prefix}oral_read.say.{w}', f.name)]
        if 'wits_interview.q.' in src:
            for n in range(1, int(re.search(r'1 \+ c\.random\(\)\.nextInt\((\d)\)', src).group(1)) + 1):
                keys += [(f'{prefix}wits_interview.{part}.{n}', f.name) for part in ('q', 'good', 'silly')]
    return keys


def ui_keys():
    keys = []
    for rel in UI_FILES:
        src = (JAVA / rel).read_text(encoding='utf-8')
        for m in re.finditer(r'"((?:%s)[A-Za-z0-9_.]*)"' % '|'.join(re.escape(p) for p in PREFIXES), src):
            keys.append((m.group(1), rel))
    exam = (JAVA / 'pet/exam/ExamSession.java').read_text(encoding='utf-8')
    reasons = re.search(r'REASONS = List\.of\(([^;]*)\);', exam)
    if reasons: keys += [('exam.mcyukkuri.refuse.' + r, 'ExamSession.REASONS') for r in re.findall(r'"([a-z_]+)"', reasons.group(1)) if r]
    return keys


def main():
    lang = {l: read_lang(l) for l in LANGS}
    problems = []
    fixed = item_keys()
    if len(fixed) < 60: problems.append(f'only {len(fixed)} exam item keys found; the scan pattern is stale')
    for key, origin in fixed:
        for l in LANGS:
            if key not in lang[l]: problems.append(f'{l}: missing {key}  ({origin})')
    for key, origin in ui_keys():
        for l in LANGS:
            if key.endswith(('.', '_')):  # prefix of a dynamically built key
                if not any(k.startswith(key) for k in lang[l]): problems.append(f'{l}: no key under prefix {key}  ({origin})')
            elif key not in lang[l]: problems.append(f'{l}: missing {key}  ({origin})')
    for l in LANGS:
        for k, v in lang[l].items():
            if k.startswith(('dialogue.', 'action.')): continue
            for bad in BANNED[l]:
                if bad in v: problems.append(f'{l}: banned wording "{bad}" in {k}')
            if l == 'en_us' and re.search(r'(\d|%s|%d)\s?E\b', v): problems.append(f'en_us: "E" used for emeralds in {k}')
    for l in LANGS[1:]:
        for k in lang['zh_cn']:
            if k not in lang[l] and k.startswith(PREFIXES): problems.append(f'{l}: missing {k} (present in zh_cn)')
    print(f'{len(fixed)} exam item keys, {len(ui_keys())} UI key references checked in {len(LANGS)} languages')
    for p in problems: print('PROBLEM', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())

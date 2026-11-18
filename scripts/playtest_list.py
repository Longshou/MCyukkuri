"""Map current TODO items to museum scenes by ID or number.

Markdown on stdout lists open items changed between the last played and installed runtime. Menu JSON lists all
failed retests and items ready for playtest, marking the items from that diff as fresh.

    python scripts/playtest_list.py [--since <commit>] [--until <commit>] [--menu-json PATH] [--scenes-json PATH]
"""
import argparse
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

STATUSES = ('已接入待试玩', '部分接入', '复测失败', '数值通过·演出待补')
RANK = {'复测失败': 0, '已接入待试玩': 1, '部分接入': 2, '数值通过·演出待补': 3}
MENU = ('复测失败', '已接入待试玩')
# Dashed or compact, with Chinese text allowed alongside the number but no enclosing identifier or path.
SCENE_NUMBER = re.compile(r'(?<![A-Za-z0-9_/-])(?<![A-Za-z0-9_]\.)([A-Z]{1,2})-?([0-9]{2})'
                          r'(?![A-Za-z0-9_/-]|\.[A-Za-z0-9_])')
PLAY_DIR = Path('.local/experimental-map/play-museum')
WATCH_MAX = 40


def main_root():
    """The main checkout (holds .local); a worktree shares its git directory."""
    common = subprocess.run(['git', '-C', str(HERE), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True, check=True).stdout.strip()
    return Path(common).parent


ROOT = main_root()


def git_show(commit, path):
    r = subprocess.run(['git', '-C', str(HERE), 'show', f'{commit}:{path}'], capture_output=True, text=True, encoding='utf-8')
    if r.returncode:
        sys.exit(f'git show {commit}:{path} failed: {r.stderr.strip()}')
    return r.stdout


def sessions():
    """[(session id, session json dict)] oldest first."""
    suffix = '.session.json'
    return [(p.name[:-len(suffix)], json.loads(p.read_text(encoding='utf-8-sig'))) for p in sorted((ROOT / PLAY_DIR).glob('*' + suffix))]


def installed_commit():
    return json.loads((ROOT / '.local/experimental-map/museum-runtime.json').read_text(encoding='utf-8-sig'))['commit']


def todo_items(text):
    """[(title, text)] of the TODO: top-level bullets and two-space nested bullets, each with its continuation lines.
    A nested item takes the parent's bold title. Headings, table rows and blank lines end an item."""
    items, title, cur = [], '', None

    def close():
        nonlocal cur
        if cur is not None:
            items.append((cur[0], ' '.join(cur[1]).strip()))
        cur = None

    for line in text.splitlines():
        if re.match(r'(  )?- ', line):
            close()
            body = line.lstrip()[2:]
            if not line.startswith(' '):
                m = re.match(r'\*\*(.+?)\*\*', body)
                title = m.group(1) if m else body[:30]
            cur = (title, [body])
        elif not line.strip() or line.startswith(('#', '|')):
            close()
        elif cur is not None and line.startswith(' '):
            cur[1].append(line.strip())
    close()
    return items


def status_of(text):
    hits = [(text.find(s), s) for s in STATUSES if s in text]
    return min(hits)[1] if hits else None


def norm(s):
    """Comparison form of a title/label: links keep their label, dates and punctuation vanish."""
    s = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'\d{1,2}-\d{1,2}', '', s)
    return re.sub(r'[^\w\u4e00-\u9fff]+', '', s).lower()


def item_key(title, text):
    """Identity of an item across TODO reworks: the bold title of a top-level bullet, else its leading label.
    A label that only carries a status or a run note says nothing; the parent title identifies the item."""
    if text.startswith('**'):
        return norm(title)
    label = text.split('：', 1)[0]
    if any(s in label for s in STATUSES) or any(w in label for w in ('本机冒烟', '非验收', '版本待核实')) or len(norm(label)) < 4:
        return norm(title)
    return norm(label)


def overlap(a, b):
    return difflib.SequenceMatcher(None, a, b, autojunk=False).find_longest_match(0, len(a), 0, len(b)).size


def resolve(item, current):
    """The current item that says the same thing, or None (deleted or reworded beyond recognition)."""
    until_text = item[1]
    for c in current:
        if c[1] == until_text:
            return c
    key = item_key(*item)
    hits = [c for c in current if item_key(*c) == key]
    inside = [c for c in current if len(item_key(*c)) >= 6 and item_key(*c) in norm(until_text)]
    for pool in (inside, [c for c in hits if status_of(c[1]) in RANK]):
        if len(pool) == 1:
            return pool[0]
    best = sorted(((overlap(key, item_key(*c)), c) for c in current), key=lambda p: -p[0])[:2]
    if len(best) == 2 and best[0][0] >= 6 and best[0][0] > best[1][0]:
        return best[0][1]
    return hits[0] if hits else None


def scene_ids_at(commit):
    return set(re.findall(r"^add\('([a-z0-9_]+)'", git_show(commit, 'scripts/museum_scenarios.py'), re.M))


def _plain(t):
    return re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', t).replace('`', '')


def _clip(w):
    if len(w) > WATCH_MAX:
        cut = w[:WATCH_MAX]
        stop = max(cut.rfind(p) for p in '，、；：（')
        w = (cut[:stop] if stop > 12 else cut) + '…'
    return w


def _good(seg):
    seg = re.sub(r'\b[0-9a-f]{7,9}\b[：:，,]?', '', seg).strip('，、： ')
    if seg.startswith(('未做', '未解决', '待定', '已知', '剩余', '试玩记录')):
        return ''
    if len(seg) < 8 or seg.count('）') > seg.count('（') or any(w in seg for w in ('本机冒烟', '非验收', '其后已改', '运行版本', '待核实')):
        return ''
    return seg


def display_name(hit):
    """Nested items show their own leading label; the parent's bold title only when the label says nothing."""
    title, text = hit
    if text.startswith('**'):
        return title
    label = text.split('：', 1)[0]
    return label if _good(label) and '｜' not in label else title


def watch_of(hit, current):
    """The shortest what-to-watch phrase for the item: 看点／完成 clauses (own or same-title children),
    else the children's leading phrases, else the text after the status; '' when nothing usable."""
    title, text = hit
    texts = [_plain(text)] + [_plain(t) for ti, t in current[current.index(hit) + 1:] if ti == title]
    cands = []
    for t in texts:
        for m in re.finditer(r'(?:看点|完成标准|完成|看)：|(?:^|[；。])\s*看(?:点)?[：:]?', t):
            seg = re.split(r'[；。]', t[m.end():])[0].strip('，、： ')
            if seg:
                cands.append(seg)
    if not cands and texts[0].startswith('**'):
        cands = [re.split(r'[；。]', t)[0].strip('，、： ') for t in texts[1:]]
    status = status_of(text)
    if not cands and status:
        rest = _plain(text)[_plain(text).find(status) + len(status):].lstrip('：（(，。；、 ')
        rest = re.sub(r'^（[^）]*）', '', rest).lstrip('：，。；、 ')
        cands = [re.split(r'[；。]', rest)[0]]
        cands += [re.split(r'[；。]', r)[0] for r in rest.split('。', 1)[1:2] if r]
    good = [_good(c) for c in cands]
    return _clip(min((g for g in good if g), key=len, default=''))


def load_museum():
    """Scene labels by ID, IDs by scene number, and the field and scene order."""
    import museum_scenarios as ms
    from museum_fields import FIELDS
    scenes = {s.id: (f.title, s.label) for f in FIELDS for s in f.scenarios}
    numbers = {s.number: s.id for s in ms.S}
    return scenes, numbers, [(f.title, [s.id for s in f.scenarios]) for f in FIELDS]


def scene_refs(text, scenes, numbers):
    """Scene ids named by a backticked id or a scene number, dashed (TN-29) or compact (TN29)."""
    ids = [i for i in re.findall(r'`([a-z][a-z0-9_]*)`', text) if i in scenes]
    for letters, digits in SCENE_NUMBER.findall(text):
        number = f'{letters}-{digits}'
        if number in numbers:
            ids.append(numbers[number])
    return list(dict.fromkeys(ids))


def feedback_watches(text, numbers):
    """Keep numbered feedback quotes with the scene named at the start of each quote."""
    watches = {}
    for quote in re.findall(r'\d+「([^」]+)」', text):
        ref = SCENE_NUMBER.match(quote)
        if ref:
            sid = numbers.get(f'{ref[1]}-{ref[2]}')
            watch = quote[ref.end():].strip(' ：:')
            if sid and watch:
                watches.setdefault(sid, []).append(_clip(watch))
    return watches


def build(since, until):
    old = {t for _, t in todo_items(git_show(since, 'docs/TODO.md'))}
    current = todo_items((HERE.parent / 'docs/TODO.md').read_text(encoding='utf-8'))
    scenes, numbers, field_order = load_museum()
    gone_pool = (scene_ids_at(since) | scene_ids_at(until)) - set(scenes)
    rows, unnamed, gone, skipped = {}, [], {}, []
    items = []
    for item in todo_items(git_show(until, 'docs/TODO.md')):
        text = item[1]
        if not status_of(text) or text in old:
            continue
        hit = resolve(item, current)
        if hit is None or status_of(hit[1]) not in RANK:
            skipped.append(item[0])
            continue
        if hit in items:
            continue
        items.append(hit)
        title, ctext = hit
        status = status_of(ctext)
        tokens = list(dict.fromkeys(re.findall(r'`([a-z][a-z0-9_]*)`', ctext)))
        ids = scene_refs(ctext, scenes, numbers)
        w = watch_of(hit, current)
        name = display_name(hit)
        for token in tokens:
            if token in gone_pool:
                gone.setdefault(token, set()).add(name)
        if not ids:
            unnamed.append((name, status, w))
            continue
        watches = feedback_watches(ctext, numbers)
        for i in ids:
            r = rows.setdefault(i, dict(watches=[], status=status))
            for watch in watches.get(i, (w,)):
                if watch and watch not in r['watches']:
                    r['watches'].append(watch)
            if RANK[status] < RANK[r['status']]:
                r['status'] = status
    fields = []
    for ftitle, sids in field_order:
        present = [i for i in sids if i in rows]
        if present:
            fields.append((ftitle, present))
    return dict(scenes=scenes, fields=fields, rows=rows, unnamed=unnamed, gone=gone, skipped=skipped, items=items)


def menu(current, fresh_texts, scenes, numbers, field_order):
    """One row per scene: a performance several items point at shows once, with the worst status of its items,
    every distinct watch text and fresh if any item is fresh. Items without a scene stay one row each."""
    order = {sid: i for i, sid in enumerate(sid for _, ids in field_order for sid in ids)}
    rows = []
    by_scene = {}
    for item in current:
        _, text = item
        status = status_of(text)
        if status not in MENU:
            continue
        ids = scene_refs(text, scenes, numbers)
        if not ids:
            rows.append(dict(status=status, title=display_name(item), watch=watch_of(item, current),
                             fresh=text in fresh_texts, scenes=[]))
            continue
        w = watch_of(item, current)
        watches = feedback_watches(text, numbers)
        for sid in ids:
            r = by_scene.setdefault(sid, dict(status=status, title=scenes[sid][1], watch=[], fresh=False,
                                              scenes=[dict(id=sid, label=scenes[sid][1], field=scenes[sid][0])]))
            if RANK[status] < RANK[r['status']]:
                r['status'] = status
            r['fresh'] = r['fresh'] or text in fresh_texts
            for watch in watches.get(sid, (w,)):
                if watch and watch not in r['watch']:
                    r['watch'].append(watch)
    rows += [dict(status=r['status'], title=r['title'], watch='；'.join(r['watch']), fresh=r['fresh'],
                  scenes=r['scenes']) for r in by_scene.values()]
    return sorted(rows, key=lambda row: (RANK[row['status']], not row['fresh'],
                                         order[row['scenes'][0]['id']] if row['scenes'] else len(order)))


def summary(d):
    n = {s: sum(1 for _, t in d['items'] if status_of(t) == s) for s in STATUSES if any(status_of(t) == s for _, t in d['items'])}
    parts = [f"{len(d['fields'])} 个场地 · {sum(len(s) for _, s in d['fields'])} 个场景 · {len(d['items'])} 条条目"]
    if n:
        parts.append('、'.join(f'{s} {n[s]}' for s in sorted(n, key=lambda s: RANK[s])))
    if d['unnamed']:
        parts.append(f"未指名场景 {len(d['unnamed'])} 条")
    if d['gone']:
        parts.append(f"已不存在场景 {len(d['gone'])} 个")
    return ' ｜ '.join(parts)


def markdown(d, since, until, played_from):
    out = [f'# 试玩清单：{since[:9]}（{played_from}）→ {until[:9]}（已装运行包）', '', summary(d), '']
    if not d['fields'] and not d['unnamed'] and not d['gone']:
        out = [f'# 试玩清单：{since[:9]}（{played_from}）→ {until[:9]}（已装运行包）', '',
               '这两个版本之间没有新增或变化的待试玩条目。']
    for ftitle, sids in d['fields']:
        out.append(f'## {ftitle}')
        out.append('')
        for i in sorted(sids, key=lambda i: RANK[d['rows'][i]['status']]):
            r = d['rows'][i]
            out.append(f"- {d['scenes'][i][1]}｜{r['status']}｜{'；'.join(r['watches'])}")
        out.append('')
    if d['unnamed'] or d['gone']:
        out += ['## 其他', '']
        out += [f'- {t}｜{s}｜{w}' if w else f'- {t}｜{s}' for t, s, w in d['unnamed']]
        out += [f'- 已不存在的场景：{g}（条目「{"、".join(sorted(titles))}」）' for g, titles in d['gone'].items()]
        out.append('')
    return '\n'.join(out) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--since', help='override the last-played commit')
    ap.add_argument('--until', help='override the installed runtime commit')
    ap.add_argument('--menu-json', type=Path, help='write the in-game playtest list')
    ap.add_argument('--scenes-json', type=Path, help='write the in-game scene catalog')
    a = ap.parse_args()
    if a.scenes_json:
        import museum_scenarios  # registers the scenes on each field
        from museum_fields import FIELDS, GROUPS
        groups = [dict(title=title, fields=[
            dict(id=f.id, title=f.title, scenes=[dict(id=s.id, label=s.label, tag=s.tag, watch=s.watch) for s in f.scenarios])
            for f in FIELDS if f.group == group]) for group, title in GROUPS]
        a.scenes_json.parent.mkdir(parents=True, exist_ok=True)
        a.scenes_json.write_text(json.dumps(dict(format=1, groups=groups), ensure_ascii=False) + '\n', encoding='utf-8')
        print(f'catalog fields={len(FIELDS)} scenes={len(museum_scenarios.S)}')
        if not a.menu_json:
            return
    since, played_from = a.since, '--since'
    if not since:
        found = sessions()
        if found:
            sid, data = found[-1]
            since, played_from = data['runtimeConfig']['commit'], f'会话 {sid}'
        elif not a.menu_json:
            sys.exit('no museum session under .local/experimental-map/play-museum; pass --since <commit>')
    until = a.until or installed_commit()
    if a.menu_json:
        current = todo_items((HERE.parent / 'docs/TODO.md').read_text(encoding='utf-8'))
        fresh_texts = {text for _, text in build(since, until)['items']} if since else set()
        rows = menu(current, fresh_texts, *load_museum())
        a.menu_json.parent.mkdir(parents=True, exist_ok=True)
        a.menu_json.write_text(json.dumps(dict(format=1, since=since, until=until, items=rows), ensure_ascii=False) + '\n',
                               encoding='utf-8')
        print(f'menu items={len(rows)} scenes={sum(len(row["scenes"]) for row in rows)} '
              f'fresh={sum(row["fresh"] for row in rows)} unlinked={sum(not row["scenes"] for row in rows)}')
        return
    d = build(since, until)
    print(markdown(d, since, until, played_from), end='')
    for title in d['skipped']:
        print(f'跳过（当前待办已无对应条目）：{title}', file=sys.stderr)


if __name__ == '__main__':
    main()

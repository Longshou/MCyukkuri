"""Feedback intake: copy a museum session's logs to `.local/reports/playtest-<date>-<time>/` and summarise what happened per scene.

    python scripts/feedback_intake.py [--session <id or prefix>]

Copies the session JSON, the session's stdout/stderr logs and (when this is the latest session) the game's `latest.log` /
`debug.log`; originals stay untouched. Scenes the user opened are found from the chat heading the museum prints on every
scene start (`【<title>】看点：`). A scene's window runs from its start to the next scene start (or the log end), so
scenes running side by side in different fields share windows; read the lines with that in mind.
Writes `summary.md` beside the copies and prints it.
"""
import argparse
import json
import re
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from playtest_list import ROOT, PLAY_DIR, sessions  # noqa: E402
import smoke_checks
import timeline

GAME_LOGS = ROOT / '.local/experimental-map/museum/logs'
SERVER = 'MCYukkuri/]: '
CLIP = 230
PER_KIND = 5
START = re.compile(r'\[CHAT\] 【(.+?)】看点：')
STAMP = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
KINDS = [  # (label, pattern on server lines)
    ('死亡', r'\[body\] .* death settled'),
    ('溺水', r'cause=drown\b'),
    ('卡墙 inWall', r'inWall\b'),
    ('饥饿伤害', r'starvation damage accepted=true'),
    ('拒绝/受阻', r'\b(?:refused|rejected|BLOCKED|REFUSED)\b(?!=)'),
    ('异常', r'/ERROR\]|Exception'),
]


def pick_session(arg):
    found = sessions()
    if not found:
        sys.exit('no museum session under .local/experimental-map/play-museum')
    if not arg:
        return found[-1] + (True,)
    hits = [s for s in found if s[0].startswith(arg)]
    if len(hits) != 1:
        sys.exit(f'--session {arg!r} matches {len(hits)} sessions')
    return hits[0] + (hits[0] is found[-1],)


def read_lines(path):
    with path.open(encoding='utf-8', errors='replace') as source:
        return [line.rstrip('\r\n') for line in source if '[trace]' not in line]


def observation_summary(log, dest, scenes):
    """Use trace windows, independent of chat headings and other fields' simultaneous scenes."""
    reports = smoke_checks.report_log(log, dest / 'observations', scenes, evidence='H')
    out = ['## 结构化时间线与自动检查', '',
           '- 完整个体／世界时间线：`observations/timelines/index.json`；检查与异常前后记录：`observations/checks.json`。',
           '- 以下仅为报告，缺读数记 no-evidence，不代表试玩通过。']
    for report in reports:
        out.append(f'- 窗口 {report.get("scope", "无结构化记录")}；种子 {report["seed"]}：' +
                   '，'.join(f'{row["check"]}={row["verdict"]}' for row in report['summary']))
    return out + ['']


def stamp_lines(lines, start):
    """[(datetime or None, line)]: the log carries only HH:MM:SS, so the date is rolled forward from the session start."""
    day, prev, out = start.replace(hour=0, minute=0, second=0, microsecond=0), None, []
    for line in lines:
        m = STAMP.match(line)
        if not m:
            out.append((None, line))
            continue
        t = timedelta(hours=int(m[1]), minutes=int(m[2]), seconds=int(m[3]))
        if prev is not None and t < prev - timedelta(minutes=5):
            day += timedelta(days=1)
        prev = t
        out.append((day + t, line))
    return out


def scene_titles():
    import museum_scenarios as ms
    from museum_fields import BY_ID
    return {s.title: s for s in ms.S}, BY_ID


def clip(line):
    line = re.sub(r'^\[[\d:]+\] \[[^\]]*\] \[[^\]]*\]: ', '', line)
    return line if len(line) <= CLIP else line[:CLIP] + '…'


def summarise(window, scene):
    out = []
    server = [(t, l) for t, l in window if SERVER in l or '/ERROR]' in l]
    for label, pat in KINDS:
        hits = [(t, l) for t, l in server if re.search(pat, l)]
        if not hits:
            continue
        if label == '拒绝/受阻':
            reasons = {}
            for _, l in hits:
                m = re.search(r'(?:reason|why)=(\S+)', l)
                key = m[1] if m else 'unspecified'
                reasons[key] = reasons.get(key, 0) + 1
            top = '，'.join(f'{k}×{n}' for k, n in sorted(reasons.items(), key=lambda kv: -kv[1])[:6])
            out.append(f'- {label} {len(hits)} 行，原因：{top}')
        else:
            out.append(f'- {label} {len(hits)} 行')
        for t, l in hits[:PER_KIND]:
            out.append(f'  - {t:%H:%M:%S} {clip(l)}')
    expects = (scene.smoke or {}).get('expect', []) if scene else []
    if expects:
        out.append('- 场景自带断言行（场景源码 smoke.expect）：')
        text = '\n'.join(l for _, l in server)
        for pat in expects:
            hit = re.search(pat, text)
            shown = pat if len(pat) <= 90 else pat[:90] + '…'
            first = f'；首条 {clip(hit[0].split(chr(10))[0])}' if hit and '[\\s\\S]' not in pat else ''
            out.append(f'  - `{shown}` ' + ('本窗口出现' + first if hit else '本窗口没有'))
    else:
        counts = {}
        for _, l in server:
            m = re.search(r'MCYukkuri/\]: \[([\w-]+)\]', l)
            if m and m[1] != 'species':
                counts[m[1]] = counts.get(m[1], 0) + 1
        top = '，'.join(f'[{k}]×{n}' for k, n in sorted(counts.items(), key=lambda kv: -kv[1])[:8])
        out.append(f'- 无场景断言；服务端前缀计数（不含 [species]）：{top or "无"}')
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--session', help='session id or unique prefix (default: latest)')
    a = ap.parse_args()
    sid, data, is_latest = pick_session(a.session)
    start = datetime.fromisoformat(data['startedAt']).replace(tzinfo=None)
    log = ROOT / PLAY_DIR / f'{sid}.log'
    if not log.exists():
        sys.exit(f'stdout log missing: {log}')
    dest = ROOT / '.local/reports' / f'playtest-{start:%Y%m%d-%H%M%S}'
    dest.mkdir(parents=True, exist_ok=True)
    copies = [ROOT / PLAY_DIR / f'{sid}.session.json', log, ROOT / PLAY_DIR / f'{sid}.err.log']
    notes = []
    if is_latest:
        copies += [GAME_LOGS / 'latest.log', GAME_LOGS / 'debug.log']
    else:
        notes.append('不是最近一场：游戏的 latest.log／debug.log 已被后续场次覆盖，只复制本场 stdout／stderr 日志。')
    for src in copies:
        if src.exists():
            shutil.copy2(src, dest / src.name)
        else:
            notes.append(f'缺少 {src.name}')

    stamped = stamp_lines(read_lines(log), start)
    end = max((t for t, _ in stamped if t), default=start)
    cfg = data.get('runtimeConfig', {})
    by_title, by_field = scene_titles()
    trace_log = dest / log.name
    if not any('[trace]' in line for line in timeline.lines([trace_log])) and (dest / 'latest.log').exists():
        trace_log = dest / 'latest.log'
    opens = [(i, m[1], t) for i, (t, l) in enumerate(stamped) if t and (m := START.search(l))]
    out = [f'# 试玩反馈入档：会话 {sid}', '',
           f'- 运行版本：commit {cfg.get("commit", "版本待核实")}，CI run {cfg.get("run", "未知")}',
           f'- 开始 {start:%Y-%m-%d %H:%M:%S}，日志末行 {end:%Y-%m-%d %H:%M:%S}（时长约 {int((end - start).total_seconds() // 60)} 分钟）',
           f'- 副本：{dest}', f'- 打开过的场景启动 {len(opens)} 次（按聊天标题 `【…】看点：` 识别）']
    out += [f'- 注意：{n}' for n in notes]
    out.append('')
    out += observation_summary(trace_log, dest, {s.id: s.smoke or {} for s in by_title.values()})
    groups = {}
    for n, (i, title, t) in enumerate(opens):
        stop = opens[n + 1][0] if n + 1 < len(opens) else len(stamped)
        groups.setdefault(title, []).append((t, stamped[i + 1:stop]))
    for title, runs in groups.items():
        scene = by_title.get(title)
        where = f'{by_field[scene.field].title} → {scene.hint or scene.title}（{scene.id}）' if scene else '场景源码里已找不到这个标题'
        out += [f'## {title}', '', f'- 位置：{where}', f'- 打开 {len(runs)} 次：{"，".join(f"{t:%H:%M:%S}" for t, _ in runs)}']
        for k, (t, window) in enumerate(runs, 1):
            span = window[-1][0] - t if window and window[-1][0] else timedelta()
            out.append(f'- 第 {k} 次（{t:%H:%M:%S} 起，窗口 {int(span.total_seconds())} 秒）')
            out += ['  ' + line for line in summarise(window, scene)]
        out.append('')
    text = '\n'.join(out) + '\n'
    (dest / 'summary.md').write_text(text, encoding='utf-8')
    print(text, end='')


if __name__ == '__main__':
    main()

"""Store the user's preview decisions and read the latest decision for each id."""
from datetime import datetime, timezone
import json
from pathlib import Path
import re
from threading import Lock

LOG = Path(__file__).resolve().parents[1]/'.local/reports/verdicts.jsonl'
LOCK = Lock()
ID = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}')


def validate_id(ident):
    if not isinstance(ident, str) or not ID.fullmatch(ident):
        raise ValueError('无效的审阅编号。')
    return ident


def append(value, path):
    if not isinstance(value, dict):
        raise ValueError('审阅内容须为 JSON 对象。')
    ident = validate_id(value.get('id'))
    verdict = value.get('verdict')
    if verdict not in ('pass', 'fail'):
        raise ValueError('请选择通过或不通过。')
    note = value.get('note', '')
    if not isinstance(note, str) or len(note) > 500:
        raise ValueError('备注须为最多 500 字的文本。')
    reasons = value.get('reasons', [])
    if (not isinstance(reasons, list) or len(reasons) > 20
            or not all(isinstance(r, str) and 0 < len(r) <= 40 for r in reasons)):
        raise ValueError('原因须为最多 20 个短文本。')
    title = value.get('title')
    if title is not None and (not isinstance(title, str) or not 0 < len(title) <= 80):
        raise ValueError('标题须为最多 80 字的文本。')
    with LOCK:
        record = dict(id=ident, verdict=verdict, reasons=reasons, note=note,
                      ts=datetime.now(timezone.utc).isoformat(timespec='milliseconds'))
        if title is not None:
            record['title'] = title
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')
    return record


def latest(path):
    with LOCK:
        try:
            with path.open(encoding='utf-8') as stream:
                records = (json.loads(line) for line in stream)
                return {record['id']: dict(record, reasons=record.get('reasons', []), note=record.get('note', ''))
                        for record in records}
        except FileNotFoundError:
            return {}

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
    with LOCK:
        record = dict(id=ident, verdict=verdict, note=note,
                      ts=datetime.now(timezone.utc).isoformat(timespec='milliseconds'))
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')
    return record


def latest(path):
    with LOCK:
        try:
            with path.open(encoding='utf-8') as stream:
                records = (json.loads(line) for line in stream)
                return {record['id']: record for record in records}
        except FileNotFoundError:
            return {}

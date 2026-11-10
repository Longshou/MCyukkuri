"""Validate the axis-split authoring corpus without changing dialogue outputs.

Run: python scripts/check_speech_corpus.py --check
"""
import argparse
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'art/dialogue/corpus'
SPEECH_TIERS = ROOT / 'art/dialogue/speech-tiers.json'
REQUIRED = ('id', 'zh', 'ja', 'en', 'basis', 'status')
STATUSES = {'ready', 'rule', 'candidate', 'planned', 'todo'}


def load_json(path, errors):
    try:
        with path.open(encoding='utf-8') as stream:
            value = json.load(stream)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f'{path.relative_to(ROOT)}: {exc}')
        return {}
    if not isinstance(value, dict):
        errors.append(f'{path.relative_to(ROOT)}: top level must be an object')
        return {}
    return value


def check_records(records, label, errors):
    if not isinstance(records, list):
        errors.append(f'{label}: must be an array')
        return
    ids = []
    for index, record in enumerate(records):
        where = f'{label}[{index}]'
        if not isinstance(record, dict):
            errors.append(f'{where}: must be an object')
            continue
        for field in REQUIRED:
            if field not in record:
                errors.append(f'{where}: missing required field {field}')
        for field in ('id', 'zh', 'ja', 'en', 'status'):
            if field in record and not isinstance(record[field], str):
                errors.append(f'{where}: {field} must be a string')
        if 'id' in record:
            ids.append(record['id'])
            if not record['id']:
                errors.append(f'{where}: id must not be empty')
        if 'basis' in record:
            basis = record['basis']
            if not isinstance(basis, list) or not basis or any(not isinstance(item, str) or not item for item in basis):
                errors.append(f'{where}: basis must be a non-empty array of source ids')
        if record.get('status') not in STATUSES:
            errors.append(f'{where}: unknown status {record.get("status")!r}')
        if record.get('status') != 'todo':
            for field in ('zh', 'ja', 'en'):
                if field in record and isinstance(record[field], str) and not record[field]:
                    errors.append(f'{where}: {field} must not be empty unless status is todo')
    for ident, count in Counter(ids).items():
        if count > 1:
            errors.append(f'{label}: duplicate id {ident!r} ({count} records)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='run read-only validation (the default action)')
    parser.parse_args()
    errors = []
    speech = load_json(SPEECH_TIERS, errors)
    speech_lines = speech.get('lines', [])
    known_events = {event for line in speech_lines for event in line.get('events', [])}
    known_species = {line.get('species') for line in speech_lines}

    documents = {}
    for name in ('common', 'stage', 'special', 'situation'):
        documents[name] = load_json(CORPUS / f'{name}.json', errors)
        check_records(documents[name].get('entries', []), f'{name}.entries', errors)

    species_count = 0
    species_files = sorted((CORPUS / 'species').glob('*.json'))
    if not species_files:
        errors.append('species: no JSON files')
    for path in species_files:
        doc = load_json(path, errors)
        species = doc.get('species')
        if species != path.stem or species not in known_species:
            errors.append(f'{path.relative_to(ROOT)}: unknown or mismatched dialogue species {species!r}')
        records = doc.get('entries', [])
        check_records(records, f'species/{path.stem}.entries', errors)
        species_count += len(records) if isinstance(records, list) else 0

    situation = documents['situation']
    common_ids = {entry.get('id') for entry in documents['common'].get('entries', [])}
    events = situation.get('events', [])
    lines = situation.get('lines', [])
    planned = situation.get('planned', [])
    check_records(events, 'situation.events', errors)
    check_records(lines, 'situation.lines', errors)
    check_records(planned, 'situation.planned', errors)
    shared_events = {line.get('event') for line in lines if isinstance(line, dict) and line.get('shared_runtime')}
    known_events |= shared_events

    event_ids = [record.get('event') for record in events if isinstance(record, dict)]
    for event, count in Counter(event_ids).items():
        if count > 1:
            errors.append(f'situation.events: duplicate event {event!r}')
    for event in event_ids:
        if event not in known_events:
            errors.append(f'situation.events: unknown event {event!r}')
    for event in sorted(known_events - set(event_ids)):
        errors.append(f'situation.events: missing existing event {event!r}')

    covered_by_event = Counter()
    for index, line in enumerate(lines):
        if not isinstance(line, dict):
            continue
        event = line.get('event')
        if event not in known_events:
            errors.append(f'situation.lines[{index}]: unknown event {event!r}')
        else:
            covered_by_event[event] += 1
        common_ref = line.get('common_ref')
        if common_ref and not common_ref.startswith('common.'):
            errors.append(f'situation.lines[{index}]: common_ref must be scoped as common.<id>, got {common_ref!r}')
        elif common_ref and common_ref[len('common.'):] not in common_ids:
            errors.append(f'situation.lines[{index}]: unknown common_ref {common_ref!r}')
    for index, event in enumerate(events):
        if not isinstance(event, dict):
            continue
        if event.get('status') == 'ready' and not covered_by_event[event.get('event')]:
            errors.append(f'situation.events[{index}]: ready event has no situation line')
        if event.get('status') == 'rule' and not event.get('axis_refs'):
            errors.append(f'situation.events[{index}]: rule event has no axis_refs')

    proposed_ids = []
    for index, line in enumerate(planned):
        if not isinstance(line, dict):
            continue
        proposed = line.get('proposed_event')
        proposed_ids.append(proposed)
        if not isinstance(proposed, str) or not proposed:
            errors.append(f'situation.planned[{index}]: proposed_event must be a non-empty string')
        elif proposed in known_events:
            errors.append(f'situation.planned[{index}]: proposed_event {proposed!r} already exists')
        if line.get('fallback_event') not in known_events:
            errors.append(f'situation.planned[{index}]: unknown fallback_event {line.get("fallback_event")!r}')
        future_id = line.get('future_line_id', '')
        if not isinstance(future_id, str) or not future_id.endswith(f'.s_{proposed}'):
            errors.append(f'situation.planned[{index}]: future_line_id must end in .s_{proposed}')
    for proposed, count in Counter(proposed_ids).items():
        if count > 1:
            errors.append(f'situation.planned: duplicate proposed_event {proposed!r}')

    if errors:
        for error in errors:
            print(f'FAIL speech corpus: {error}')
        print(f'FAIL speech corpus: {len(errors)} error(s)')
        return 1

    status_counts = Counter(record['status'] for record in events)
    covered = status_counts['ready'] + status_counts['rule']
    common_count = len(documents['common']['entries'])
    stage_count = len(documents['stage']['entries'])
    special_count = len(documents['special']['entries'])
    total = common_count + species_count + stage_count + special_count + len(events) + len(lines) + len(planned)
    print(
        f'PASS speech corpus: {len(documents) + len(species_files)} JSON files, {total} records; '
        f'common={common_count}, species={species_count}, stage={stage_count}, special={special_count}, '
        f'situation={len(events)} events + {len(lines)} lines + {len(planned)} planned'
    )
    generated_id_events = sum(
        event in shared_events or any(event in line.get('events', []) and line.get('id', '').endswith(f'.s_{event}') for line in speech_lines)
        for event in known_events
    )
    print(
        f'PASS speech corpus: covered {covered}/{len(known_events)} events '
        f'({status_counts["ready"]} ready, {status_counts["rule"]} rule, {status_counts["todo"]} todo); '
        f'all events map to existing lines; {generated_id_events} also have generated .s_<event> ids'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

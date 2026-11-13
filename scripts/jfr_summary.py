"""Summarize a client profile from the playground launcher with the JDK's jfr tool."""
import argparse
from collections import Counter
from datetime import datetime
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

PACKAGE = 'io.github.longshou.mcyukkuri.'
CPU = ('jdk.ExecutionSample', 'jdk.NativeMethodSample')
MARKERS = ('mcyukkuri.ClientWound', 'mcyukkuri.ClientFrames')
PAUSES = ('jdk.GCPhasePause', 'jdk.ClassLoad')
SERVER_TICK = 'minecraft.ServerTickTime'
SECTIONS = ('Client and other threads', 'Server thread')
METRICS = ('CPU', 'allocation')
EVENTS = (*CPU, 'jdk.ObjectAllocationSample', *PAUSES, *MARKERS, SERVER_TICK)
ROOT = Path(__file__).resolve().parents[1]


def find_jfr():
    executable = 'jfr.exe' if os.name == 'nt' else 'jfr'
    candidates = []
    if 'JAVA_HOME' in os.environ:
        candidates.append(Path(os.environ['JAVA_HOME']) / 'bin' / executable)
    common = ROOT
    if shutil.which('git'):
        git = subprocess.run(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                             cwd=ROOT, capture_output=True, text=True, check=False)
        if git.returncode == 0:
            common = Path(git.stdout.strip()).parent
    candidates.extend(sorted((common / '.local/tools/java').glob(f'*/bin/{executable}')))
    return next((str(path) for path in candidates if path.is_file()), None) or shutil.which('jfr')


def events(stream):
    """Keep one XML event in memory at a time, including its printed stack."""
    parent = None
    for when, element in ET.iterparse(stream, events=('start', 'end')):
        if when == 'start' and element.tag == 'events':
            parent = element
        if when == 'end' and element.tag == 'event':
            yield element
            parent.remove(element)


def value(element, name, default=''):
    return element.findtext(f"value[@name='{name}']", default=default)


def milliseconds(duration):
    # java.time.Duration prints hours/minutes/seconds, with fractional seconds for sub-second events.
    match = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', duration)
    if not match:
        raise ValueError(f'invalid JFR duration: {duration}')
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return ((hours * 60 + minutes) * 60 + seconds) * 1000


def methods(event):
    stack = event.findall("struct[@name='stackTrace']/array[@name='frames']/struct/struct[@name='method']")
    return [value(method.find("struct[@name='type']"), 'name').replace('/', '.') + '.' + value(method, 'name')
            for method in stack]


def nearest_marker(markers, at, window):
    marker = min(markers, key=lambda m: abs(at - m[0]), default=None)
    return marker if marker and abs(marker[0] - at) <= window else None


def summarize(stream, top, window, slow_ms):
    counts = {section: {metric: Counter() for metric in METRICS} for section in SECTIONS}
    samples, pauses, wounds, frames, ticks = [], [], [], [], []
    for event in events(stream):
        kind = event.attrib['type']
        at = datetime.fromisoformat(value(event, 'startTime')).timestamp()
        if kind == 'mcyukkuri.ClientWound':
            detail = ' '.join(f'{name}={value(event, name)}'
                              for name in ('gameTick', 'entityId', 'dimension', 'model', 'kind', 'severity'))
            wounds.append((at, detail))
            continue
        if kind == 'mcyukkuri.ClientFrames':
            worst = milliseconds(value(event, 'max'))
            if worst >= slow_ms:
                end = at - milliseconds(value(event, 'worstEndedAgo')) / 1000
                frames.append((end, f"gameTick={value(event, 'gameTick')} max={worst:.1f} ms "
                                    f"mean={milliseconds(value(event, 'mean')):.1f} ms count={value(event, 'count')}"))
            continue
        if kind == 'jdk.GCPhasePause':
            duration = milliseconds(value(event, 'duration'))
            if duration >= slow_ms:
                pauses.append((at, duration, 'GC pause', ''))
            continue
        if kind == SERVER_TICK:
            ticks.append((at, milliseconds(value(event, 'averageTickDuration'))))
            continue
        thread = event.find("struct[@name='sampledThread']")
        if thread is None:
            thread = event.find("struct[@name='eventThread']")
        thread_name = value(thread, 'javaName') if thread is not None else ''
        section = 'Server thread' if thread_name == 'Server thread' else 'Client and other threads'
        stack = methods(event)
        mod = next((method for method in stack if method.startswith(PACKAGE)), None)
        if kind == 'jdk.ClassLoad':
            loaded = event.find("struct[@name='loadedClass']")
            name = value(loaded, 'name').replace('/', '.') if loaded is not None else ''
            if mod or name.startswith(PACKAGE) or thread_name == 'Render thread':
                duration = milliseconds(value(event, 'duration'))
                if duration >= slow_ms:
                    pauses.append((at, duration, 'class load', f'{name} on {thread_name}'))
            continue
        if not mod:
            continue
        # The leaf can be a JVM or graphics method invoked by the first visible mod frame.
        site = stack[0] if stack[0] == mod else f'{stack[0]} via {mod}'
        weight = int(value(event, 'weight')) if kind == 'jdk.ObjectAllocationSample' else 1
        metric = 'CPU' if kind in CPU else 'allocation'
        counts[section][metric][site] += weight
        samples.append((at, section, metric, site, weight))

    markers = sorted([*wounds, *frames])
    near = {section: {metric: Counter() for metric in METRICS} for section in SECTIONS}
    if markers:
        # Samples are ordered here because the JFR printer groups events by thread/chunk.
        i = 0
        for at, section, metric, site, weight in sorted(samples):
            while i < len(markers) and markers[i][0] < at - window:
                i += 1
            if i < len(markers) and markers[i][0] <= at + window:
                near[section][metric][site] += weight

    def totals(title, counts, unit):
        print(f'\n{title}')
        for site, count in counts.most_common(top):
            print(f'  {count:>14,} {unit}  {site}')
        if not counts:
            print('  No matching samples.')

    print(f'\nMarkers: {len(wounds)} first wounds, {len(frames)} frame windows at or above {slow_ms:g} ms')
    for label, group in (('First wound', sorted(wounds)[:top]), ('Slow frame', sorted(frames)[-top:])):
        for at, detail in group:
            print(f'  {timestamp(at)} {label}: {detail}')
    for section in SECTIONS:
        print(f'\n=== {section} ===')
        totals('Mod CPU hot methods', counts[section]['CPU'], 'samples')
        totals('Mod allocation sites', counts[section]['allocation'], 'sampled bytes')
        if markers:
            totals(f'Mod CPU within ±{window:g} s of a marker', near[section]['CPU'], 'samples')
            totals(f'Mod allocation within ±{window:g} s of a marker', near[section]['allocation'], 'sampled bytes')

    def context(at):
        marker = nearest_marker(markers, at, window)
        return f" [{at - marker[0]:+.3f} s from {marker[1]}]" if marker else ''

    print(f'\nLong server tick averages (at or above {slow_ms:g} ms, sampled once per second)')
    long_ticks = [(at, average) for at, average in ticks if average >= slow_ms]
    for at, average in sorted(long_ticks, key=lambda row: -row[1])[:top]:
        print(f'  {timestamp(at)} {average:8.2f} ms average{context(at)}')
    if not long_ticks:
        print(f'  {len(ticks)} recorded averages, none at or above threshold.'
              if ticks else '  No server tick time events recorded.')

    print(f'\nLongest GC pauses and relevant class loads (at or above {slow_ms:g} ms)')
    for at, duration, kind, name in sorted(pauses, key=lambda row: -row[1])[:top]:
        print(f'  {timestamp(at)} {duration:8.2f} ms  {kind} {name}{context(at)}')
    if not pauses:
        print('  No matching events.')


def timestamp(seconds):
    return datetime.fromtimestamp(seconds).astimezone().isoformat(timespec='milliseconds')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('--jfr', help='Path to the JDK jfr executable')
    parser.add_argument('--top', type=int, default=12)
    parser.add_argument('--window', type=float, default=0.5, help='Seconds on either side of each marker')
    parser.add_argument('--slow-ms', type=float, default=20, help='Minimum frame interval, tick average or pause to report')
    args = parser.parse_args()
    if args.top < 1 or not 0 <= args.window < float('inf') or not 0 <= args.slow_ms < float('inf'):
        parser.error('top must be positive and window/slow-ms must be finite and nonnegative')
    if not args.recording.is_file():
        parser.error(f'recording not found: {args.recording}')
    jfr = args.jfr or find_jfr()
    if not jfr:
        parser.error('JDK jfr not found. Set JAVA_HOME or pass --jfr.')
    try:
        # The JDK XML writer buffers formatted output. Limit depth so dense allocation stacks fit.
        with subprocess.Popen([jfr, 'print', '--xml', '--stack-depth', '8', '--events', ','.join(EVENTS),
                               str(args.recording.resolve())], stdout=subprocess.PIPE) as process, process.stdout:
            summarize(process.stdout, args.top, args.window, args.slow_ms)
            if process.wait():
                return 1
    except (OSError, ValueError, ET.ParseError) as error:
        print(f'Cannot summarize JFR: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

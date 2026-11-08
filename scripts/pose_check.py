"""Compile/cache the pure Java pose checker, then run its JSON protocol (no Gradle or game)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import gzip

ROOT = Path(__file__).resolve().parents[1]


def preview_sources():
    """Inputs that invalidate cached core-backed preview pixels."""
    base = ROOT / 'src/main/java/io/github/longshou/mcyukkuri'
    return [*sorted((base/'pose').rglob('*.java')), base/'motor/geom/BraidChain.java',
            base/'body/ModelFingerprint.java', *[ROOT/'scripts'/name for name in
            ('pose_check.py', 'pose_client.py', 'pose_client.cjs', 'pose_worker.cjs')]]


def java_command():
    common = Path(subprocess.check_output(
        ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT, text=True).strip()).parent
    suffix = '.exe' if os.name == 'nt' else ''
    homes = [Path(os.environ['JAVA_HOME'])] if 'JAVA_HOME' in os.environ else []
    homes += [p.parent.parent for p in (common / '.local/tools/java').rglob('javac' + suffix)]
    javac = next((p / ('bin/javac' + suffix) for p in homes if (p / ('bin/javac' + suffix)).is_file()), None)
    if javac is None:
        raise RuntimeError('JDK 21 missing: set JAVA_HOME or install under .local/tools/java')
    jars = []
    for name in ('gson-2.10.1.jar', 'joml-1.10.5.jar'):
        matches = sorted((common / '.local/gradle-home').rglob(name))
        if not matches:
            raise RuntimeError(f'missing dependency {name} under .local/gradle-home')
        jars.append(matches[0])
    base = ROOT / 'src/main/java/io/github/longshou/mcyukkuri'
    sources = sorted((base / 'pose').rglob('*.java'))
    sources += [base / 'motor/geom/BraidChain.java', base / 'body/ModelFingerprint.java']
    digest = hashlib.sha256(str(javac).encode())
    for path in sources + jars:
        digest.update(str(path).encode()); digest.update(path.read_bytes())
    cache = ROOT / '.local/pose-check' / digest.hexdigest()
    cp = os.pathsep.join(map(str, jars))
    if not (cache / '.ready').exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='compile-', dir=cache.parent) as temp:
            args = Path(temp) / 'sources.args'
            args.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
            classes = Path(temp) / 'classes'; classes.mkdir()
            subprocess.run([str(javac), '-encoding', 'UTF-8', '-proc:none', '-cp', cp, '-d', str(classes), '@' + str(args)], check=True)
            cache.mkdir(exist_ok=True)
            shutil.copytree(classes, cache, dirs_exist_ok=True)
            (cache / '.ready').touch()
    return [str(javac.with_name('java' + suffix)), '-Xmx1g', '-cp', str(cache) + os.pathsep + cp,
            'io.github.longshou.mcyukkuri.pose.cli.PoseCli', str(ROOT / 'src/main/resources/assets/mcyukkuri')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model'); parser.add_argument('--clip'); parser.add_argument('--t', default='0')
    parser.add_argument('--metric', default='tilt'); parser.add_argument('--water', type=float)
    parser.add_argument('--partner', action='append', default=[]); parser.add_argument('--care')
    parser.add_argument('--frames', type=Path); parser.add_argument('--serve', action='store_true')
    parser.add_argument('--vertices', type=Path); parser.add_argument('--pose-out', type=Path)
    parser.add_argument('--context', type=Path, help='explicit additional frame context JSON; see pose/check/README.md')
    args = parser.parse_args()
    if not args.serve and not args.frames and (not args.model or not args.clip):
        parser.error('--model and --clip are required without --frames or --serve')
    try:
        command = java_command()
        if args.serve:
            return subprocess.call(command + ['--serve'])
        request = {'metric': args.metric.split(',')}
        if args.frames:
            request['frames'] = [json.loads(line) for line in args.frames.read_text(encoding='utf-8').splitlines() if line.strip()]
            for index, frame in enumerate(request['frames']):
                if 'evidence' not in frame:
                    continue
                evidence = (args.frames.parent / frame['evidence']).resolve()
                if evidence.parent != args.frames.parent.resolve():
                    raise ValueError('pose evidence must be beside frames JSONL')
                with gzip.open(evidence, 'rt', encoding='utf-8') as source:
                    request['frames'][index] = json.load(source)
        else:
            request.update(model=args.model, clip=args.clip, t=args.t, partner=args.partner)
        for key in ('water', 'vertices', 'pose_out'):
            value = getattr(args, key)
            if value is not None:
                request[key] = str(value) if isinstance(value, Path) else value
        if args.care:
            request['care'] = [float(v) for v in args.care.split(',')]
        if args.context:
            request['context'] = json.loads(args.context.read_text(encoding='utf-8'))
        completed = subprocess.run(command, input=json.dumps(request, allow_nan=False), text=True, encoding='utf-8', stdout=subprocess.PIPE)
        print(completed.stdout, end='')
        if completed.returncode:
            return completed.returncode
        result = json.loads(completed.stdout)
        return 2 if any(r['verdict'] == 'error' for r in result['results']) else 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(json.dumps({'error': str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())

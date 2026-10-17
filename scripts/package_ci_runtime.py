"""Split a built CI jar into small compiled code and a verified source-resource manifest.

This never compiles. The full development jar remains the release artifact. Local launchers can
assemble the exact unpacked contents using this archive and the matching repository resources.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    if args.jar.is_dir():
        jars = [p for p in args.jar.glob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
        if len(jars) != 1:
            parser.error('Expected exactly one built development jar')
        args.jar = jars[0]
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'commit': args.commit, 'jar_sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(), 'resources': {}}
    with zipfile.ZipFile(args.jar) as source, zipfile.ZipFile(args.output / 'compiled-runtime.zip', 'w', zipfile.ZIP_DEFLATED) as code:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            data = source.read(entry)
            if entry.filename.startswith(('assets/', 'data/')):
                manifest['resources'][entry.filename] = hashlib.sha256(data).hexdigest()
            else:
                code.writestr(entry.filename, data)
    (args.output / 'runtime-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Packaged compiled runtime and {len(manifest["resources"])} verified resource entries.')


if __name__ == '__main__':
    main()

"""Export approved runtime faces without rebuilding bodies; --check is read-only."""
import argparse

# Existing body selectors import this stable five-face subset.
IDS = ['closed_smile','chewing','chewing_closed','satisfied','open_smile']


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    from runtime_face_library import outputs as library_outputs
    from face_usage import ASSETS, emitted_faces, check_coverage
    import json
    selected = library_outputs()
    usage = emitted_faces()
    metadata = ASSETS/'yukkuri_presentation/face_library.json'
    check_coverage(json.loads(selected[metadata]),usage,selected)
    if args.check:
        check_coverage(json.loads(metadata.read_text(encoding='utf-8')),usage)
    for path,data in selected.items():
        if args.check: assert path.is_file() and path.read_bytes()==data,'Outdated ecology expression: '+str(path)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print('Ecology expressions and full approved face library: '+('verified' if args.check else 'selected and exported'))
    print(f'Emitted face coverage: {len(usage)} ids on every runtime model (Java call sites/providers + all clip face-spans)')


if __name__=='__main__':main()

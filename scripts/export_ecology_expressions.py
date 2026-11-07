"""Export approved runtime faces without rebuilding bodies; --check is read-only."""
import argparse

# Existing body selectors import this stable five-face subset.
IDS = ['closed_smile','chewing','chewing_closed','satisfied','open_smile']


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    from runtime_face_library import outputs as library_outputs
    selected = library_outputs()
    for path,data in selected.items():
        if args.check: assert path.is_file() and path.read_bytes()==data,'Outdated ecology expression: '+str(path)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print('Ecology expressions and full approved face library: '+('verified' if args.check else 'selected and exported'))


if __name__=='__main__':main()

#!/usr/bin/env python
"""Replace one exact occurrence in a file, keeping its line endings, encoding and final newline.

    python scripts/safe_edit.py replace <file> <old> <new>
    python scripts/safe_edit.py replace <file> --old-from-file <path> --new-from-file <path>

The old text must match exactly once (else nothing is written), so a stale or ambiguous pattern fails loudly instead of
rewriting the wrong place. Windows ``sed`` and ad-hoc scripts drop carriage returns, rewrite UTF-8 as the ANSI code page
and strip the final newline; this does none of that. The Edit tool is the other safe way to change a file by hand."""
import argparse
import codecs
import sys
from pathlib import Path

sys.dont_write_bytecode = True


def read(path):
    """(text with LF newlines, newline style, BOM) of ``path``."""
    raw = Path(path).read_bytes()
    bom = raw.startswith(codecs.BOM_UTF8)
    text = raw[len(codecs.BOM_UTF8):].decode('utf-8') if bom else raw.decode('utf-8')
    crlf, lf, cr = text.count('\r\n'), text.count('\n'), text.count('\r')
    if crlf and lf - crlf:
        raise ValueError(f'{path} mixes CRLF and LF line endings; not touching it')
    if cr - crlf:
        raise ValueError(f'{path} has a carriage return without a newline; not touching it')
    return text.replace('\r\n', '\n'), ('crlf' if crlf else 'lf'), bom


def source(value, path, what):
    """The text of an edit argument: passed on the command line, or read from a file for multi-line text."""
    if path is not None:
        return read(path)[0]
    if value is None:
        raise ValueError(f'no {what} text: pass it as an argument or with --{what}-from-file')
    return value.replace('\r\n', '\n')


def replace(file, old, new):
    text, style, bom = read(file)
    if not old:
        raise ValueError('the old text is empty')
    count = text.count(old)
    if count != 1:
        raise ValueError(f'{file}: the old text matches {count} times; it must match exactly once')
    result = text.replace(old, new, 1)
    if text.endswith('\n') and not result.endswith('\n'):
        result += '\n'
    elif not text.endswith('\n') and result.endswith('\n'):
        result = result[:-1]
    endings = '\r\n' if style == 'crlf' else '\n'
    Path(file).write_bytes((codecs.BOM_UTF8 if bom else b'') + result.replace('\n', endings).encode('utf-8'))
    print(f'{file}: replaced 1 occurrence ({style.upper()} kept)', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    edit = commands.add_parser('replace', help='replace the one occurrence of the old text')
    edit.add_argument('file', type=Path)
    edit.add_argument('old', nargs='?', help='text to replace (multi-line text: use --old-from-file)')
    edit.add_argument('new', nargs='?', help='replacement text (multi-line text: use --new-from-file)')
    edit.add_argument('--old-from-file', type=Path, help='read the old text from this file')
    edit.add_argument('--new-from-file', type=Path, help='read the replacement from this file')
    args = parser.parse_args()
    if args.old is not None and args.old_from_file is not None:
        parser.error('pass the old text as an argument or with --old-from-file, not both')
    if args.new is not None and args.new_from_file is not None:
        parser.error('pass the replacement as an argument or with --new-from-file, not both')
    try:
        replace(args.file, source(args.old, args.old_from_file, 'old'), source(args.new, args.new_from_file, 'new'))
    except (OSError, ValueError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

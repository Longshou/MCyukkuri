"""Compatibility entry point for the current actual-mamu close-up preview.

The older full-scene montage did not reproduce the outlet clipping. Delegate to
this geometry preview so the old entry point cannot overwrite current.png with
an image that omits the mamu. No browser, game or Java compilation is used.
"""
from mamu_birth_preview import main


if __name__ == '__main__':
    main()

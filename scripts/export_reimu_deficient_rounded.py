"""Rebuild sparse and uneven-face Reimu using the shared rounded deficient pipeline."""
import export_marisa_deficient_rounded as shared

if __name__ == "__main__":
    shared.configure("reimu")
    shared.main()

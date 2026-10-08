"""Compare recorded model hashes across LF/CRLF checkouts without ignoring content drift."""
import hashlib


def matches_model_fingerprint(data, expected):
    lf = data.replace(b"\r\n", b"\n")
    return any(hashlib.sha256(candidate).hexdigest() == expected
               for candidate in (data, lf, lf.replace(b"\n", b"\r\n")))


#!/usr/bin/env python
"""Compatibility entry point for the unchanged fate gate, now owned by smoke_checks."""
from smoke_checks import (CHECKER, EVENT, FATES, KINDS, LOOP_BOUND, REFUSAL, SUPPRESSED,
                          check_allowances, fates, fate_main as main, signature)

if __name__ == '__main__':
    raise SystemExit(main())

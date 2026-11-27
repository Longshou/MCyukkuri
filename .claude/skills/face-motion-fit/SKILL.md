---
name: face-motion-fit
description: Use whenever you author or change a face (expression) timeline inside a motion clip, a presentation row (moments.json) or presentation/expressions.json, or pick faces for a looping action such as a shove, struggle or chew. Covers minimum face hold, one change per beat, no eye-anchor jumps between adjacent faces, one face held through a repetitive action, and the face-fit checker.
---

# Face and motion fit

Read `standards/yukkuri-library/FACE-MOTION.md` before choosing or changing any face span in a clip or presentation row, and add what you learn to its pitfalls list.

Rules in one line each (details and thresholds are on that page): a look stays at least 0.5 s; at most 1.5 face changes per second and one emotional change per beat; adjacent faces keep the eye anchor (move at most 0.45 model units) unless a closed-eye face bridges them, so never alternate with `puff`/`puff_open`/`puff_teary`; a repetitive action (shove, struggle, loop) holds one face; the face matches the body phase and the individual's real state.

Run before you finish, and fix every finding in the timelines you touched (the baseline only holds old offenders):

```
PYTHONUTF8=1 <py> scripts/check_face_fit.py --check --changed origin/main
<py> scripts/check_face_fit.py --transitions <clip-or-row-id>   # per-span holds and eye metrics
```

Clips and `moments.json` rows that carry the same faces are edited together; re-export with `<py> scripts/export.py --family motion-corrections --family moments --family ecology-expressions` and run `scripts/check_face_registry.py`.

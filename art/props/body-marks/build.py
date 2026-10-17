"""Builds the swatch strip used by the dirt and scar marks (src/main/resources/assets/mcyukkuri/textures/props/body_marks.png).

The strip is sampled like body_wounds.png: the renderer picks one 16x1 swatch per decal triangle. Materials 16-20 in
WoundRenderer map to swatches 0-4 here:
  0 scar core   1 scar rim   2 mud   3 dark clump   4 dust
Run with the bundled Pillow:  python art/props/body-marks/build.py [--check]
"""
import hashlib
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "src/main/resources/assets/mcyukkuri/textures/props/body_marks.png"
SWATCHES = [
    (236, 208, 202, 255),  # 0 scar core: healed, paler than the skin
    (196, 136, 132, 255),  # 1 scar rim: pink edge
    (116, 90, 66, 255),    # 2 mud
    (78, 60, 48, 255),     # 3 dark clump
    (156, 134, 104, 255),  # 4 dust
]
FILLER = (156, 134, 104, 255)


def build() -> Image.Image:
    image = Image.new("RGBA", (16, 1), FILLER)
    for index, colour in enumerate(SWATCHES):
        image.putpixel((index, 0), colour)
    return image


def main() -> int:
    image = build()
    if "--check" in sys.argv:
        current = Image.open(OUT).convert("RGBA") if OUT.exists() else None
        ok = current is not None and list(current.getdata()) == list(image.getdata())
        print("body_marks.png", "matches" if ok else "DIFFERS")
        return 0 if ok else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
    provenance = {
        "output": str(OUT.relative_to(ROOT)).replace("\\", "/"),
        "output_sha256": digest,
        "swatches": ["scar_core", "scar_rim", "mud", "dark_clump", "dust"],
        "builder": "art/props/body-marks/build.py",
        "origin": "project original colours; no external reference image",
    }
    (Path(__file__).parent / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print("wrote", OUT, digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Read-only held-mouth geometry check; optional offline contact diagram, no game/compiler."""
import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mcyukkuri"


def mouth_data(asset_id):
    model = json.loads((ASSETS / "yukkuri" / f"{asset_id}.json").read_text(encoding="utf-8"))
    part = next(p for p in model["parts"] if p["name"] == "face_mouth")
    vertices = model["vertices"]
    mouth = [vertices[i * 8:i * 8 + 3] for i in range(part["start"], part["start"] + part["count"])]
    bounds = [(min(p[a] for p in mouth), max(p[a] for p in mouth)) for a in range(3)]
    assert all(math.isfinite(v) for b in bounds for v in b), asset_id
    assert any(b["name"] == "face_mouth" for b in model["bones"]), asset_id
    anchor = [(bounds[0][0] + bounds[0][1]) / 2, (bounds[1][0] + bounds[1][1]) / 2, bounds[2][0]]
    return model, mouth, anchor


def preview(folder):
    from PIL import Image, ImageDraw
    folder.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (1050, 530), "#edf2f5")
    draw = ImageDraw.Draw(image)
    model, mouth, anchor = mouth_data("reimu")
    bundle = json.loads((ASSETS / "yukkuri_props/bedding_bundle.json").read_text(encoding="utf-8"))
    b = bundle["bounds"]
    center = [(v[0] + v[1]) / 2 for v in b]
    draw.text((24, 16), "Mouth attachment / offline source geometry diagram (not a game capture)", fill="#182839")
    for panel, angle in enumerate((0, 35, -25)):
        origin = (175 + panel * 350, 280)
        radians = math.radians(angle)
        def project(p):
            z = (p[2]-anchor[2]) * math.cos(radians) - (p[1]-anchor[1]) * math.sin(radians)
            y = (p[2]-anchor[2]) * math.sin(radians) + (p[1]-anchor[1]) * math.cos(radians)
            return origin[0] + (z+p[0]*.35) * 40, origin[1] - y * 40
        def triangles(points, color):
            for i in range(0, len(points), 3):
                draw.polygon([project(v) for v in points[i:i + 3]], fill=color, outline="#455c6e")
        points = [bundle["vertices"][i:i + 3] for i in range(0, len(bundle["vertices"]), 8)]
        triangles([[p[a] - center[a] + anchor[a] for a in range(3)] for p in points], "#87a657")
        closed=[[p[0], anchor[1] + (p[1] - anchor[1]) * .45, p[2]] for p in mouth]
        for i in range(0,len(closed),3):
            draw.line([project(p) for p in closed[i:i+3]]+[project(closed[i])],fill="#dc627c",width=2)
        x, y = project(anchor)
        draw.ellipse((x - 4, y - 4, x + 4, y + 4), fill="#f59b23")
        draw.text((panel * 350 + 30, 410), f"Side projection / mouth + bundle pitch {angle} deg", fill="#182839")
    draw.text((24, 466), "Pink: real lip triangles, closed to 45%. Green: centered source bedding bundle.", fill="#182839")
    draw.text((24, 489), "Orange: outer lip anchor. Runtime additionally samples skin, expression, body scale and current bone rotation.", fill="#182839")
    image.save(folder / "held-contact.png")
    (folder / "index.html").write_text('<!doctype html><meta charset="utf-8"><title>Held mouth contact</title><h1>Offline held-mouth contact diagram</h1><p>Source geometry projection; not in-game verification.</p><img src="held-contact.png" style="max-width:100%">', encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    living = json.loads((ASSETS / "yukkuri_presentation/living.json").read_text(encoding="utf-8"))["variants"]
    living.update(json.loads((ASSETS / "yukkuri_presentation/subspecies.json").read_text(encoding="utf-8")))
    for asset_id in living:
        mouth_data(asset_id)
    print(f"Validated mouth surface, finite anchor and mouth bone in {len(living)} selected models.")
    if args.preview:
        preview(ROOT / ".local/previews/held-mouth-contact")

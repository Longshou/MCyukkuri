"""Guard native block/item model textures against missing atlas registrations."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets"


class RuntimeAtlasTests(unittest.TestCase):
    def test_props_directory_is_stitched(self):
        atlas = json.loads((ROOT / "minecraft/atlases/blocks.json").read_text())
        self.assertIn({"type": "minecraft:directory", "source": "props", "prefix": "props/"},
                      atlas["sources"])

    def test_referenced_mod_textures_exist(self):
        checked = set()
        for path in (ROOT / "mcyukkuri/models").rglob("*.json"):
            model = json.loads(path.read_text())
            for texture in model.get("textures", {}).values():
                if texture.startswith("mcyukkuri:"):
                    relative = texture.split(":", 1)[1]
                    with self.subTest(model=path.name, texture=texture):
                        self.assertTrue((ROOT / "mcyukkuri/textures" / (relative + ".png")).is_file())
                        self.assertIn(relative.split("/", 1)[0], ("block", "item", "props"))
                    checked.add(texture)
        self.assertGreater(len(checked), 0)


if __name__ == "__main__":
    unittest.main()

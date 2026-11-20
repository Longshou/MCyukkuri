"""The playtest list leaves pure-appearance items to the preview inbox."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import playtest_list


class AppearanceOnlyTests(unittest.TestCase):
    def test_art_and_cinema_titles_stay_out(self):
        self.assertTrue(playtest_list.appearance_only(('美术遗漏', '美术遗漏：部分接入（待看页逐件通过／不通过）。')))
        self.assertTrue(playtest_list.appearance_only(('放映室首页', '放映室首页：已接入待试玩。打开「晒太阳」。')))

    def test_pointer_to_the_preview_pages_stays_out(self):
        self.assertTrue(playtest_list.appearance_only(('某反馈', '某反馈：部分接入。完成标准可在待你查看里逐件确认。')))

    def test_playtest_items_stay(self):
        self.assertFalse(playtest_list.appearance_only(
            ('窝入口与 2×2 大窝', '窝入口与 2×2 大窝：已接入待试玩。家庭场「灵胖进大草窝过夜」。')))
        self.assertFalse(playtest_list.appearance_only(
            ('纸箱与美术', '纸箱与美术：部分接入。看FG-09是否还有两个纸箱重叠。')))


if __name__ == '__main__':
    unittest.main()

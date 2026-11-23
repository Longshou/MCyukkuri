"""The playtest list: appearance items stay out, only the last two feedback rounds are listed, hints are hand-written."""
import sys
import unittest
from unittest import mock
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


TODO = (Path(__file__).resolve().parent.parent / 'docs/TODO.md').read_text(encoding='utf-8')
SCENES, NUMBERS, FIELD_ORDER = playtest_list.load_museum()
CURRENT = playtest_list.todo_items(TODO)
ROWS = playtest_list.menu(CURRENT, set(), SCENES, NUMBERS, FIELD_ORDER)
BY_SCENE = {r['scenes'][0]['id']: r for r in ROWS if r['scenes']}
WATCH = playtest_list.load_watch()
SYNTHETIC = """- **2026-11-22 反馈**（x）：
  - 甲演出：已接入待试玩。DN-11 看孩子顶嘴。
- **2026-11-20 反馈**（x）：
  - 乙演出：已接入待试玩。WT-06 看追猎。
- **2026-11-19 反馈**（x）：
  - 丙演出：已接入待试玩。FG-01 看挖洞。DN-12 看睡位。
  - 丁演出：已接入待试玩。看一个未指名场景。
"""
UNLINKED = """- **2026-11-22 反馈**（x）：
  - 短项：已接入待试玩。看两只在这里撞上。
"""


class RecentRoundsTests(unittest.TestCase):
    def recent(self, text):
        return playtest_list.recent_feedback(playtest_list.todo_items(text), SCENES, NUMBERS)

    def test_only_two_latest_rounds_name_scenes(self):
        ids, _ = self.recent(SYNTHETIC)
        self.assertEqual({'den_mold_child_exile', 'pred_range'}, ids)
        self.assertNotIn('dig', ids)

    def test_real_list_holds_only_recent_scenes(self):
        ids, _ = self.recent(TODO)
        self.assertTrue(BY_SCENE)
        self.assertLessEqual(set(BY_SCENE), ids)
        for sid in ('dig', 'forage', 'excited_leak', 'shop_build'):
            self.assertNotIn(sid, BY_SCENE)

    def test_unnamed_item_outside_recent_rounds_drops(self):
        rows = playtest_list.menu(playtest_list.todo_items(SYNTHETIC), set(), SCENES, NUMBERS, FIELD_ORDER)
        self.assertEqual({'den_mold_child_exile', 'pred_range'}, {r['scenes'][0]['id'] for r in rows})


class WatchTextTests(unittest.TestCase):
    def test_every_listed_scene_has_a_hint(self):
        for sid, row in BY_SCENE.items():
            self.assertTrue(row['watch'], sid)

    def test_hints_are_short_and_never_quote_the_user(self):
        for sid, text in WATCH.items():
            self.assertNotIn('「', text, sid)
            self.assertNotIn('」', text, sid)
            self.assertLessEqual(len(text), playtest_list.WATCH_MAX, sid)

    def test_hint_keys_are_real_scenes(self):
        self.assertFalse(set(WATCH) - set(SCENES))

    def test_row_hint_is_exactly_the_scene_hint(self):
        for sid, row in BY_SCENE.items():
            self.assertEqual(WATCH[sid], row['watch'])

    def test_no_misattributed_sentences(self):
        # these once got the MD-17 sentence, the SP-01 text or the process item (清单范围)
        for sid in ('den_mold_child_exile', 'den_mold_keep', 'pred_range', 'pred_perch_hunt'):
            if sid not in BY_SCENE:  # its item left the two recent rounds
                continue
            watch = BY_SCENE[sid]['watch']
            for bad in ('漏尿', '清单', 'M 菜单', 'SP-01', '满约'):
                self.assertNotIn(bad, watch, sid)

    def test_scene_without_a_hint_shows_title_only(self):
        with mock.patch.object(playtest_list, 'load_watch', return_value={}):
            rows = playtest_list.menu(playtest_list.todo_items(SYNTHETIC), set(), SCENES, NUMBERS, FIELD_ORDER)
        self.assertEqual(['', ''], [r['watch'] for r in rows])


class UnlinkedRowTests(unittest.TestCase):
    def test_unlinked_items_are_not_menu_rows(self):
        rows = playtest_list.menu(playtest_list.todo_items(UNLINKED), set(), SCENES, NUMBERS, FIELD_ORDER)
        self.assertEqual([], rows)

    def test_real_menu_has_only_scene_rows(self):
        self.assertTrue(ROWS)
        self.assertTrue(all(row['scenes'] for row in ROWS))


if __name__ == '__main__':
    unittest.main()

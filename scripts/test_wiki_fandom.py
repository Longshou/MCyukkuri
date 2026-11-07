"""Offline regressions for fandom cache queries."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import wiki_fandom


class FandomShowTests(unittest.TestCase):
    def test_show_uses_all_cached_category_memberships(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            (cache / 'pages').mkdir(parents=True)
            (cache / 'categories').mkdir()
            page = {
                'title': 'Factory', 'pageid': 1900, 'revid': 10557,
                'category': 'Pet Yukkuri', 'wikitext': 'Factory source',
                'images': [],
            }
            (cache / 'pages' / '1900-factory.json').write_text(
                json.dumps(page), encoding='utf-8')
            (cache / 'categories' / 'places.json').write_text(json.dumps({
                'pages': [{'title': 'Factory', 'pageid': 1900}], 'other': [],
            }), encoding='utf-8')
            output = io.StringIO()
            with patch.object(wiki_fandom, 'CACHE', cache), redirect_stdout(output):
                wiki_fandom.show(['Places'], ['Factory'])
            text = output.getvalue()
            self.assertIn('Category:Places (1 pages)', text)
            self.assertIn('Factory (1900, rev 10557)', text)
            self.assertIn('Factory source', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)

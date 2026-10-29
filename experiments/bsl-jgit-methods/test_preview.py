"""Small rendering checks; no EDT or comparison engine is launched."""
from html.parser import HTMLParser
import unittest

import run


class Marks(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.inside = False
        self.marked = []
        self.all_text = []
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        if tag == 'mark': self.inside = True

    def handle_endtag(self, tag):
        if tag == 'mark': self.inside = False

    def handle_data(self, data):
        self.all_text.append(data)
        if self.inside: self.marked.append(data)


def span(source, target):
    start = source.index(target)
    return dict(start=len(source[:start].encode('utf-16-le'))//2,
                length=len(target.encode('utf-16-le'))//2)


class PreviewTest(unittest.TestCase):
    def test_unicode_and_multiline_ranges_preserve_surrounding_lines(self):
        source = '// 😀\r\nСообщить("<новое>");\r\nДалее();\r\n'
        lines = run.code_lines(source, [span(source, '<новое>')], [])
        self.assertEqual(len(lines), 3)
        self.assertEqual([row[1] for row in lines], [False, True, False])
        self.assertEqual(Marks(lines[1][0]).marked, ['<новое>'])
        self.assertIn('&lt;новое&gt;', lines[1][0])
        self.assertNotIn('\r', ''.join(row[0] for row in lines))
        multiline = run.code_lines('a\nb\nc\n', [dict(start=1, length=3)], [])
        self.assertEqual([row[1] for row in multiline], [True, True, False])
        self.assertEqual(Marks(multiline[1][0]).marked, ['b'])

    def test_moves_and_padding_do_not_become_edits(self):
        case = dict(before='A\nB\n', after='B\nA\n')
        result = dict(left=[], right=[], fallback=None, byteChanges=True,
                      moves=[dict(key='A', leftStart=0, leftLength=1, rightStart=2, rightLength=1)],
                      lines=dict(aligned=[[0, None], [1, 0], [None, 1]]))
        view = run.jgit_preview(case, result)
        self.assertEqual(view.count('class="code moved"'), 2)
        self.assertEqual(view.count('class="code gap"'), 2)
        self.assertNotIn('<mark>', view)
        self.assertNotIn('class="code added"', view)
        result['left'] = [dict(start=0, length=1)]
        result['right'] = [dict(start=2, length=1)]
        edited = run.jgit_preview(case, result)
        self.assertIn('class="code removed"', edited)
        self.assertIn('class="code added"', edited)
        self.assertEqual(Marks(edited).marked, ['A', 'A'])

    def test_invalid_utf16_boundary_is_rejected(self):
        with self.assertRaises(KeyError):
            run.code_lines('😀\n', [dict(start=1, length=1)], [])

    def test_changed_spaces_and_tabs_keep_selectable_source_characters(self):
        source = '\tСообщить(" Код "); \t\n'
        rendered = run.code_lines(source, [span(source, '" Код "'), span(source, ' \t')], [])[0][0]
        parsed = Marks(rendered)
        self.assertEqual(''.join(parsed.all_text), source[:-1])
        self.assertEqual(''.join(parsed.marked), '" Код " \t')
        self.assertEqual(rendered.count('data-symbol="·"'), 3)
        self.assertEqual(rendered.count('data-symbol="→"'), 1)
        self.assertNotIn('·', ''.join(parsed.all_text))
        self.assertNotIn('→', ''.join(parsed.all_text))


if __name__ == '__main__':
    unittest.main()

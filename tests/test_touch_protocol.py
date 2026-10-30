"""Keep the Java/native action protocol in sync as controls evolve."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TouchProtocol(unittest.TestCase):
    def test_action_ids_agree_across_jni_boundary(self):
        java = (ROOT / 'android/app/src/main/java/io/github/kalandos240/endlesssky/TouchActions.java').read_text()
        native = (ROOT / 'android/native/AndroidTouch.cpp').read_text()
        ids = {int(number): name for name, number in re.findall(r'int (\w+) = (\d+);', java) if name != 'COUNT'}
        mapping = {int(number): name for number, name in re.findall(r'case (\d+): return Command::(\w+);', native)}
        self.assertEqual(ids, mapping)
        self.assertEqual(set(ids), set(range(18)))
        state = (ROOT / 'android/native/TouchState.h').read_text()
        self.assertIn('ACTION_COUNT = 18;', state)


if __name__ == '__main__':
    unittest.main()

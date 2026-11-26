#!/usr/bin/env python3
"""Stage-specific guidance is exact spec text; no inference or application inputs."""
import json
import pathlib
import re
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class GuidanceTest(unittest.TestCase):
    def test_generated_stage_contracts(self):
        spec = (ROOT / 'docs/spec-v0.8.md').read_text()
        with tempfile.TemporaryDirectory() as directory:
            stage = pathlib.Path(directory)
            subprocess.run(['javac', '-d', str(stage), str(ROOT / 'tools/GenerationGuidance.java')], check=True)
            output = stage / 'guidance.deal'
            subprocess.run(['java', '-cp', str(stage), 'GenerationGuidance', str(ROOT),
                            str(ROOT / 'dependencies/deal'), str(output)], check=True)
            values = {name: json.loads(text) for name, text in re.findall(
                r'export function (\w+)\(\): string \{ return ("(?:[^"\\]|\\.)*"); \}', output.read_text())}
        self.assertEqual(values['specification'], spec)
        planning = values['planningSpecification']
        for text in ['Plan {', 'Requirement {', 'Need {', 'Input {', 'EntryField {',
                     'Counts require integral values', 'independently optional', 'ActionBinding {',
                     'Freeze host-configured Screen.target', 'original facts']:
            self.assertIn(text, planning)
        self.assertNotIn('SelectionQuestion {', planning)
        self.assertNotIn('SelectionAnswer {', planning)
        selection = values['selectionSpecification']
        self.assertIn('SelectionQuestion {', selection)
        self.assertIn('SelectionAnswer {', selection)
        self.assertIn('0.000001', selection)
        self.assertNotIn('Plan {', selection)
        writing = values['writingSpecification']
        for text in ['<classes>', '<fields>', '<functions>', '<view>', 'one owner',
                     'read-only', 'nonnullable', 'Constructive calculations', 'correlation',
                     'never auto-replay', 'Payload events consume', 'Card components:']:
            self.assertIn(text, writing)
        for text in ['EncodedValue tags:', '### Observation', '| Component | Props-class',
                     '| Stage | Calls/recovery |']:
            self.assertNotIn(text, writing)
        # Each generated passage is copied verbatim from the authority, not a new
        # hand-maintained contract. Full pack/language retain all APIs and syntax.
        for name in ['planningSpecification', 'selectionSpecification', 'writingSpecification']:
            for paragraph in values[name].strip().split('\n\n'):
                self.assertIn(paragraph, spec)
        self.assertEqual(values['pack'], (ROOT / 'ui/platform.dealui-pack').read_text())
        language_spec = (ROOT / 'dependencies/deal/docs/spec-v1.6.md').read_text()
        passages = ''.join(language_spec[language_spec.index(start):language_spec.index(end, language_spec.index(start))] + '\n'
                           for start, end in [('### Narrowing and checked assertions\n', '### Type compatibility\n'),
                                              ('### Inference rules\n', '### Redeclaration\n'),
                                              ('### Array literals\n', '### Indexing\n')])
        self.assertEqual(values['language'], (ROOT / 'dependencies/deal/skills/write-deal/references/language.md').read_text()
                         + '\nAUTHORITATIVE TYPE REFINEMENT AND LITERAL RULES\n' + passages)
        self.assertIn('Retain remaining binding refinements across iterations', values['language'])
        self.assertIn('Under contextual `unknown`, object literals infer', values['language'])
        self.assertLess(len(planning), 11000)
        self.assertLess(len(selection), 1500)
        self.assertLess(len(writing), 20000)


if __name__ == '__main__':
    unittest.main()

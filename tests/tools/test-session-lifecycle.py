#!/usr/bin/env python3
"""Run pinned upstream checkpoint/lifecycle assertions against embedding's real host and pack."""
import os
import json
import re
from pathlib import Path
from host_check import prepare, run

ROOT = Path(__file__).resolve().parents[2]
lock = prepare(ROOT)
UI = Path(os.environ.get('DEAL_UI_ROOT', ROOT / 'dependencies/deal-ui')).resolve()
DEAL = Path(os.environ.get('DEAL_ROOT', ROOT / 'dependencies/deal')).resolve()
STAGE = ROOT / 'build/session-lifecycle'
CLASSES = STAGE / 'classes'
CLASSES.mkdir(parents=True, exist_ok=True)
LIBRARY = ROOT / 'build/embedding-library/classes'
run(['javac', '--release', '21', '-cp', LIBRARY, '-d', CLASSES,
     ROOT / 'tests/tools/fixtures/SessionLifecycleTest.java'], check=True, timeout=30)
run(['java', '-Ddeal.home=' + str(DEAL), '-cp', str(CLASSES) + ':' + str(LIBRARY),
     'SessionLifecycleTest', ROOT, UI, STAGE], check=True, timeout=60)
HOST = ROOT / 'android/assets/embedding/bindings'
run(['node', UI / 'src/test/js/checkpoint.test.js', STAGE / 'out', HOST], check=True, timeout=30)
# Preserve every upstream assertion. Its synthetic Button has no event payload, whereas embedding's
# actual Button requires a number. Adapt only dispatch inputs and the copied script's repository root.
source = (UI / 'src/test/js/session.test.js').read_text()
source = source.replace("path.resolve(__dirname,'../../..')", json.dumps(str(UI)))
source, count = re.subn(r'dispatch\(slot\(([^)]*)\)\)', r'dispatch(slot(\1),"0")', source)
assert count == 9, 'upstream dispatch fixture changed; review its host adaptation'
script = STAGE / 'session.test.js'
script.write_text(source)
run(['node', '--require', ROOT / 'tests/tools/fixtures/session-host-preload.js',
     script, STAGE / 'out', HOST], check=True, timeout=30)

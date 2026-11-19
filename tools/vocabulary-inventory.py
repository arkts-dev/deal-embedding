#!/usr/bin/env python3
"""Vocabulary inventory: reject removed component bindings and renamed legacy props.

The scan is lexical over the pack-view binding spelling only, so markdown prose, demo
types and DEAL references through other modules cannot match. It also runs its own
negative control against a temporary file and never modifies a tracked file.
"""
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACK = ROOT / "ui/platform.dealui-pack"
REMOVED = ["Option", "IntField", "SearchField", "Time", "IntText", "Choice", "Filters", "Money",
           "Metric", "Comparison", "Screen", "InputGroup", "ActionGroup", "ContentGroup",
           "SessionClock", "Notice", "Failure", "Item", "KeyedList", "Heading", "Caption",
           "PrimaryAction", "SecondaryAction"]
# Built from parts so this tool is not itself a binding site the inventory reports.
LEGACY = ["ui." + "Text(value:", "ui." + "Toggle(text:", "ui." + "Toggle(value:",
          "ui." + "Section(summary:", "ui." + "Row(minimumChildWidth:"]
IMPORT = re.compile(r'import\s+\*\s+as\s+([A-Za-z_][A-Za-z0-9_]*)\s+from\s+"([^"]*)"')


def scope():
    files = [PACK]
    for pattern, base in (("ui/**/*", ROOT / "ui"), ("core/**/*.deal", ROOT / "core"),
                          ("tools/**/*", ROOT / "tools"), ("tests/tools/**/*", ROOT / "tests/tools"),
                          ("android/src/rendering/**/*", ROOT / "android/src/rendering")):
        for path in ROOT.glob(pattern):
            if path.is_file() and path not in files:
                files.append(path)
    return sorted(files)


def scan_file(path):
    try:
        lines = path.read_text().splitlines()
    except (UnicodeDecodeError, OSError):
        return []
    hits = []
    relative = path.relative_to(ROOT).as_posix()
    if path == PACK:
        pattern = re.compile(r"export\s+component\s+(" + "|".join(REMOVED) + r")\b")
        for number, line in enumerate(lines, 1):
            match = pattern.search(line)
            if match:
                hits.append(f"{relative}:{number}: export component {match.group(1)}")
        return hits
    text = "\n".join(lines)
    aliases = [alias for alias, specifier in IMPORT.findall(text) if specifier.endswith("platform.dealui-pack")]
    for alias in aliases:
        pattern = re.compile(r"\b" + re.escape(alias) + r"\.(" + "|".join(REMOVED) + r")\s*\(")
        for number, line in enumerate(lines, 1):
            match = pattern.search(line)
            if match:
                hits.append(f"{relative}:{number}: {alias}.{match.group(1)}(")
    for spelling in LEGACY:
        for number, line in enumerate(lines, 1):
            if spelling in line:
                hits.append(f"{relative}:{number}: {spelling}")
    return hits


def scan(files):
    hits = []
    for path in files:
        hits.extend(scan_file(path))
    return hits


def main():
    hits = scan(scope())
    if hits:
        print("\n".join(hits), file=sys.stderr)
        print(f"vocabulary inventory: {len(hits)} removed name or legacy prop binding(s)", file=sys.stderr)
        return 1
    # Negative control: one migrated view with a removed component reintroduced into a
    # temporary file that is not tracked, not scanned by the run and never packaged.
    control_dir = ROOT / "build/vocabulary-inventory-control"
    control = control_dir / "removed-name.dealui"
    try:
        control_dir.mkdir(parents=True, exist_ok=True)
        control.write_text('import * as ui from "./platform.dealui-pack";\n'
                           'export view Control(state: app.State): View { ui.Column() { ui.' + "IntField" + '(value: 1) } }\n')
        failures = scan([control])
        expected = control.relative_to(ROOT).as_posix() + ":2:"
        if not failures or not failures[0].startswith(expected):
            print("vocabulary inventory: negative control did not fail", file=sys.stderr)
            return 1
    finally:
        shutil.rmtree(control_dir, ignore_errors=True)
    print("vocabulary inventory: zero hits; negative control observed failing naming path and line")
    return 0


if __name__ == "__main__":
    sys.exit(main())

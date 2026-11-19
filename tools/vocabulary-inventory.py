#!/usr/bin/env python3
"""Vocabulary inventory: reject removed component bindings and renamed legacy props.

The scan is lexical over the pack-view binding spelling only. A removed component name
is matched only in a file that binds the pack under an alias - an import statement, an
escaped import inside an emitter's view string, or a view import table entry - while the
renamed legacy prop spellings are matched in every scanned file. Text is matched as
source, so a document that quotes the pack import and then a removed-name binding is
reported like code; module references, demo-local types and prose that binds no pack
alias cannot match. The tool also runs its own negative controls against temporary files
and never modifies a tracked file.
"""
import pathlib
import re
import shutil
import sys
import tempfile

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
VIEW_IMPORT = re.compile(r'module\s*:\s*"([^"]*)"\s*,\s*alias\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"')
ESCAPED_QUOTE = '\\"'
PACK_MODULE = "platform.dealui-pack"
# Built from parts so the control spellings below do not report this tool itself.
CONTROL_NAME = "Int" + "Field"
CONTROL_OTHER = "No" + "tice"


def pack_aliases(text):
    """Identifiers the file binds to the pack, in each spelling a binding site uses."""
    spellings = {text}
    normalised = text
    while ESCAPED_QUOTE in normalised:
        normalised = normalised.replace(ESCAPED_QUOTE, '"')
        spellings.add(normalised)
    aliases = set()
    for spelling in spellings:
        aliases.update(alias for alias, specifier in IMPORT.findall(spelling)
                       if specifier.endswith(PACK_MODULE))
        aliases.update(alias for specifier, alias in VIEW_IMPORT.findall(spelling)
                       if specifier.endswith(PACK_MODULE))
    return sorted(aliases)


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
        text = path.read_bytes().decode("utf-8", errors="replace")
    except OSError as error:
        # An unreadable file is a failure, never a silent pass over its bindings.
        print(f"vocabulary inventory: cannot read {path}: {error}", file=sys.stderr)
        raise SystemExit(1)
    lines = text.splitlines()
    hits = []
    relative = path.relative_to(ROOT).as_posix()
    if path == PACK:
        pattern = re.compile(r"export\s+component\s+(" + "|".join(REMOVED) + r")\b")
        for number, line in enumerate(lines, 1):
            match = pattern.search(line)
            if match:
                hits.append(f"{relative}:{number}: export component {match.group(1)}")
        return hits
    for alias in pack_aliases(text):
        pattern = re.compile(r"\b" + re.escape(alias) + r"\.(" + "|".join(REMOVED) + r")\s*\(")
        for number, line in enumerate(lines, 1):
            for match in pattern.finditer(line):
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
    # Negative controls: migrated views with removed components reintroduced, once as
    # authored source, once inside a DEAL emitter's escaped view string - two bindings on
    # one line - and once under a view import table's alias. Each sits in a temporary file
    # that is not tracked, not scanned by the run and never packaged.
    controls = {
        "removed-name.dealui": (
            'import * as ui from "./platform.dealui-pack";\n'
            'export view Control(state: app.State): View { ui.Column() { ui.' + CONTROL_NAME + '(value: 1) } }\n',
            [CONTROL_NAME]),
        "emitter-view.deal": (
            'function panelView(): string {\n'
            '  return "import * as ui from \\"./platform.dealui-pack\\"; export view Panel(state: app.AppState): '
            'View { ui.Column() { ui.' + CONTROL_NAME + '(value: 1) ui.' + CONTROL_OTHER + '(text: \\"x\\") } }";\n'
            '}\n',
            [CONTROL_NAME, CONTROL_OTHER]),
        "view-import.deal": (
            'function panelView(): string {\n'
            '  return "export view Main(state: app.AppState): View { ui.Column() { ui.' + CONTROL_NAME + '(value: 1) } }";\n'
            '}\n'
            'let viewImports: Import[] = [{module:"./experience",alias:"app"},'
            '{module:"./platform.dealui-pack",alias:"ui"}];\n',
            [CONTROL_NAME]),
    }
    temp_root = ROOT / "build"
    temp_root.mkdir(parents=True, exist_ok=True)
    control_dir = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-inventory-control-", dir=temp_root))
    try:
        for name, (content, names) in controls.items():
            control = control_dir / name
            control.write_text(content, encoding="utf-8")
            relative = control.relative_to(ROOT).as_posix()
            expected = [f"{relative}:2: ui.{name_}(" for name_ in names]
            failures = scan([control])
            if failures != expected:
                print(f"vocabulary inventory: negative control {name} did not fail naming "
                      f"{expected}; observed {failures}", file=sys.stderr)
                return 1
    finally:
        shutil.rmtree(control_dir, ignore_errors=True)
    print("vocabulary inventory: zero hits; negative controls observed failing naming path, line and spelling")
    return 0


if __name__ == "__main__":
    sys.exit(main())

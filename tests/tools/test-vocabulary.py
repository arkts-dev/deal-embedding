#!/usr/bin/env python3
"""Deterministic vocabulary items: pack conformance, projections, renderer metadata,
compile-input target, inventory isolation and determinism."""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
PACK = ROOT / "ui/platform.dealui-pack"
THEME = ROOT / "ui/theme.json"
CONSTRAINTS = ROOT / "ui/prop-constraints.json"
INVENTORY = ROOT / "tools/ui-vocabulary.json"
VALIDATOR = ROOT / "tools/pack-conformance.py"
PACKAGED = ROOT / "build/embedding-consumer/assets/embedding"
CLASSES = ROOT / "build/embedding-library/classes"
KOTLIN_HOME = pathlib.Path("/snap/kotlin/current")
STDLIB = KOTLIN_HOME / "lib/kotlin-stdlib.jar"
PROJECTIONS = ["prop-constraints.json", "card-profile.json", "prop-declarations.json"]
REMOVED = ["Option", "IntField", "SearchField", "Time", "IntText", "Choice", "Filters", "Money",
           "Metric", "Comparison", "Screen", "InputGroup", "ActionGroup", "ContentGroup",
           "SessionClock", "Notice", "Failure", "Item", "KeyedList", "Heading", "Caption",
           "PrimaryAction", "SecondaryAction"]


def run(*args, **kwargs):
    return subprocess.run([str(item) for item in args], capture_output=True, text=True, **kwargs)


def validate(pack=PACK, theme=THEME, constraints=CONSTRAINTS, inventory=INVENTORY, assets=None, kotlin=None):
    command = [sys.executable, VALIDATOR, "--pack", pack, "--theme", theme, "--constraints", constraints,
               "--inventory", inventory]
    if assets:
        command += ["--assets", assets]
    if kotlin:
        command += ["--kotlin", kotlin]
    return run(*command)


def prepared(pack_text=None, theme=None, constraints=None, inventory=None):
    directory = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-"))
    (directory / "pack").write_text((pack_text if pack_text is not None else PACK.read_text()))
    (directory / "theme.json").write_text(THEME.read_text() if theme is None else json.dumps(theme))
    (directory / "constraints.json").write_text(CONSTRAINTS.read_text() if constraints is None else json.dumps(constraints))
    (directory / "inventory.json").write_text(INVENTORY.read_text() if inventory is None else json.dumps(inventory))
    return directory / "pack", directory / "theme.json", directory / "constraints.json", directory / "inventory.json"


def expect_rejected(code, subject, pack=PACK, theme=THEME, constraints=CONSTRAINTS, inventory=INVENTORY):
    assets = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-out-"))
    result = validate(pack, theme, constraints, inventory, assets, assets)
    assert result.returncode != 0, f"expected a rejection for {subject}: {result.stdout}{result.stderr}"
    message = result.stdout + result.stderr
    assert code in message, f"expected {code} naming {subject}, got {message}"
    assert subject in message, f"expected {subject} in the rejection, got {message}"
    assert list(assets.iterdir()) == [], f"a rejected pack published {list(assets.iterdir())}"
    return message


def line_of(text, prefix):
    for line in text.splitlines():
        if line.startswith(prefix):
            return line
    raise AssertionError(f"no line starts with {prefix!r}")


def replace_line(text, prefix, replacement):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith(prefix):
            lines[index] = replacement
            return "\n".join(lines) + "\n"
    raise AssertionError(f"no line starts with {prefix!r}")


def main():
    inventory = json.loads(INVENTORY.read_text())
    assert inventory["version"] == 2 and len(inventory["components"]) == 64

    # The packaged pack conforms, declares 64 spec components, no removed name or alias,
    # and exactly one derived capability per component.
    packaged_pack = PACKAGED / "platform.dealui-pack"
    assert packaged_pack.exists(), "the packaged pack is missing; build the library first"
    assert validate(packaged_pack).returncode == 0
    pack_text = packaged_pack.read_text()
    declared = re.findall(r"export component (\w+)", pack_text)
    assert len(declared) == 64 and set(declared) == set(inventory["components"])
    for name in REMOVED:
        assert f"export component {name}(" not in pack_text, f"removed name {name} survived"
    capabilities = re.findall(r'capability "([^"]+)"', pack_text)
    assert len(capabilities) == 64 and len(set(capabilities)) == 64
    assert set(capabilities) == {f"renderer.{name}" for name in declared}
    theme_tokens = json.loads((PACKAGED.parent / "ui/theme.json").read_text())
    assert theme_tokens["version"] == 2
    projected = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-theme-"))
    built = run(sys.executable, ROOT / "tools/theme-build.py", PACKAGED.parent / "ui/theme.json", projected / "PackTheme.kt")
    assert built.returncode == 0, built.stderr
    generated = (projected / "PackTheme.kt").read_text()
    for name, value in theme_tokens["geometry"].items():
        assert f"val {name} = {value}.dp" in generated, name
    for name, role in theme_tokens["roles"].items():
        assert f"val {name}" in generated and role in generated, name

    # Projections are byte-identical across runs and equal the packaged bytes.
    first = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-a-"))
    second = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-b-"))
    for directory in (first, second):
        result = validate(assets=directory, kotlin=directory)
        assert result.returncode == 0, result.stderr
    for name in PROJECTIONS + ["PackProjections.kt"]:
        assert (first / name).read_bytes() == (second / name).read_bytes(), f"{name} is not deterministic"
        if name in PROJECTIONS:
            assert (first / name).read_bytes() == (PACKAGED / name).read_bytes(), f"{name} differs from the shipped projection"

    # Constraint projection covers every numeric prop identity; the presentation fields are finite rows.
    constraints = json.loads((PACKAGED / "prop-constraints.json").read_text())
    rows = {row["prop"]: row["class"] for row in constraints["constraints"]}
    assert rows["NumberText.decimals"] == "finite" and rows["NumberText.minimumDigits"] == "finite"
    assert rows["Progress.value"] == "display-clamp" and rows["Transform.pose"] == "pose"
    assert rows["Sparkline.series"] == "finite"
    numeric = {f"{name}.{prop['name']}" for name, component in inventory["components"].items()
               for prop in component["props"] if prop["type"] in ("number", "number[]")}
    assert set(rows) == numeric, sorted(set(rows) ^ numeric)

    # Declaration projection is keyed by component and prop with kind, default and optionality.
    declarations = json.loads((PACKAGED / "prop-declarations.json").read_text())
    by_component = {entry["component"]: {prop["name"]: prop for prop in entry["props"]} for entry in declarations["components"]}
    assert len(by_component) == 64
    assert by_component["Button"]["onClick"]["kind"] == "action" and by_component["Button"]["onClick"]["optional"] is True
    assert by_component["Button"]["text"]["required"] is True
    assert by_component["Motion"]["style"]["default"] == "pop"
    assert "style" not in by_component["BackHandler"]
    assert by_component["Table"]["headers"]["default"] == [] and by_component["Table"]["headers"]["kind"] == "json"
    assert by_component["Surface"]["shapes"]["kind"] == "json"
    assert by_component["Sparkline"]["series"]["kind"] == "numbers"
    for entry in declarations["components"]:
        names = [prop["name"] for prop in entry["props"]]
        assert names == sorted(names), f"{entry['component']} props are not sorted"
    assert [entry["component"] for entry in declarations["components"]] == sorted(by_component)

    # Pack-shaped divergences reject naming the component or prop and publish nothing.
    added = PACK.read_text() + 'export class ExtraProps { style: string = ""; }\nexport component Extra(props: ExtraProps): View { capability "renderer.Extra"; }\n'
    expect_rejected("PACK_CONFORMANCE", "Extra", *prepared(pack_text=added))
    legacy = PACK.read_text() + 'export class OptionProps { style: string = ""; }\nexport component Option(props: OptionProps): View { capability "renderer.Option"; }\n'
    expect_rejected("PACK_CONFORMANCE", "Option", *prepared(pack_text=legacy))
    wrong_default = replace_line(PACK.read_text(), "export class ButtonProps",
        'export class ButtonProps { text: string; onClick?: Action; value: number = 1; enabled: boolean = true; style: string = ""; accessibilityLabel?: string; }')
    expect_rejected("PACK_CONFORMANCE", "Button.value", *prepared(pack_text=wrong_default))
    back_style = replace_line(PACK.read_text(), "export class BackHandlerProps",
        'export class BackHandlerProps { onBack: Action; style: string = ""; }')
    expect_rejected("PACK_CONFORMANCE", "BackHandler.style", *prepared(pack_text=back_style))
    omitted_event = PACK.read_text().replace('event onChange(payload: boolean); ', '', 1)
    expect_rejected("PACK_CONFORMANCE", "Toggle.onChange", *prepared(pack_text=omitted_event))
    unlisted_child = replace_line(PACK.read_text(), "export component MetricGroup",
        'export component MetricGroup(props: MetricGroupProps): View { children required Stat | Tile; capability "renderer.MetricGroup"; }')
    expect_rejected("PACK_CONFORMANCE", "MetricGroup", *prepared(pack_text=unlisted_child))
    other_cardinality = replace_line(PACK.read_text(), "export component MetricGroup",
        'export component MetricGroup(props: MetricGroupProps): View { children optional Stat | NumberStat; capability "renderer.MetricGroup"; }')
    expect_rejected("PACK_CONFORMANCE", "MetricGroup", *prepared(pack_text=other_cardinality))
    wrong_capability = replace_line(PACK.read_text(), "export component Toggle",
        'export component Toggle(props: ToggleProps): View { event onChange(payload: boolean); accessibility accessibilityLabel; capability "renderer.compose.toggle"; }')
    expect_rejected("PACK_CONFORMANCE", "Toggle", *prepared(pack_text=wrong_capability))
    duplicate_capability = replace_line(PACK.read_text(), "export component Checkbox",
        'export component Checkbox(props: CheckboxProps): View { event onChange(payload: boolean); accessibility accessibilityLabel; capability "renderer.Toggle"; }')
    expect_rejected("PACK_CONFORMANCE", "Checkbox", *prepared(pack_text=duplicate_capability))
    no_accessibility = replace_line(PACK.read_text(), "export component Button",
        'export component Button(props: ButtonProps): View { event onClick(payload: number); capability "renderer.Button"; }')
    expect_rejected("PACK_CONFORMANCE", "Button", *prepared(pack_text=no_accessibility))
    optional_default = replace_line(PACK.read_text(), "export class ButtonProps",
        'export class ButtonProps { text: string; onClick?: Action; value?: number = 0; enabled: boolean = true; style: string = ""; accessibilityLabel?: string; }')
    expect_rejected("PACK_CONFORMANCE", "Button.value", *prepared(pack_text=optional_default))
    number_boolean = replace_line(PACK.read_text(), "export class ButtonProps",
        'export class ButtonProps { text: string; onClick?: Action; value: number = 0; enabled: boolean = 1; style: string = ""; accessibilityLabel?: string; }')
    expect_rejected("PACK_CONFORMANCE", "Button.enabled", *prepared(pack_text=number_boolean))
    # `| null` is the optionality spelling, as deal-ui parses it: a required prop or a prop with
    # a declared default so spelled rejects before any projection is written.
    nullable_pose = replace_line(PACK.read_text(), "export class TransformProps",
        'export class TransformProps { pose: number[] | null; durationMs: number; size: number = 0; style: string = ""; }')
    message = expect_rejected("PACK_CONFORMANCE", "Transform.pose", *prepared(pack_text=nullable_pose))
    assert "optionality" in message, message
    nullable_text = replace_line(PACK.read_text(), "export class TextProps",
        'export class TextProps { text: string | null; style: string = ""; }')
    message = expect_rejected("PACK_CONFORMANCE", "Text.text", *prepared(pack_text=nullable_text))
    assert "optionality" in message, message
    nullable_defaulted = replace_line(PACK.read_text(), "export class TransformProps",
        'export class TransformProps { pose: number[]; durationMs: number; size: number | null = 0; style: string = ""; }')
    message = expect_rejected("PACK_CONFORMANCE", "Transform.size", *prepared(pack_text=nullable_defaulted))
    assert "optionality" in message, message
    nullable_payload = PACK.read_text().replace("event onChange(payload: boolean); ",
                                                "event onChange(payload: boolean | null); ", 1)
    message = expect_rejected("PACK_CONFORMANCE", "Toggle.onChange", *prepared(pack_text=nullable_payload))
    assert "event-payload" in message, message

    # The positive half of the same rule: `| null` on an optional prop is accepted and projects
    # as optional in the declaration data and in the generated Kotlin table.
    nullable_optional = replace_line(PACK.read_text(), "export class ButtonProps",
        'export class ButtonProps { text: string; onClick: Action | null; value: number = 0; enabled: boolean = true; style: string = ""; accessibilityLabel?: string; }')
    pack, theme_path, constraint_path, inventory_path = prepared(pack_text=nullable_optional)
    nullable_out = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-nullable-"))
    accepted = validate(pack, theme_path, constraint_path, inventory_path, nullable_out, nullable_out)
    assert accepted.returncode == 0, f"a nullable optional prop must be accepted: {accepted.stdout}{accepted.stderr}"
    projected = json.loads((nullable_out / "prop-declarations.json").read_text())
    button = next(entry for entry in projected["components"] if entry["component"] == "Button")
    onClick = next(prop for prop in button["props"] if prop["name"] == "onClick")
    assert onClick.get("optional") is True and "required" not in onClick, onClick
    generated = (nullable_out / "PackProjections.kt").read_text()
    assert 'Row("Button", "onClick", "action", false, true, null),' in generated, generated
    theme = json.loads(THEME.read_text()); theme["version"] = 1
    message = expect_rejected("PACK_CONFORMANCE", "version", *prepared(theme=theme))
    assert "2" in message and "1" in message

    # Constraint-table defects reject with PACK_PROP naming the component, prop and category.
    def table(mutator):
        data = json.loads(CONSTRAINTS.read_text())
        mutator(data["constraints"])
        data["constraints"].sort(key=lambda entry: entry["prop"])
        return data
    absent = table(lambda entries: entries.append({"prop": "Text.missing", "class": "finite"}))
    expect_rejected("PACK_PROP", "Text.missing", *prepared(constraints=absent))
    missing = table(lambda entries: entries.remove(next(e for e in entries if e["prop"] == "Ticker.intervalMs")))
    expect_rejected("PACK_PROP", "Ticker.intervalMs", *prepared(constraints=missing))
    duplicate = table(lambda entries: entries.append(dict(next(e for e in entries if e["prop"] == "Timer.millis"))))
    expect_rejected("PACK_PROP", "constraint", *prepared(constraints=duplicate))
    unknown = table(lambda entries: next(e for e in entries if e["prop"] == "Grid.columns").update({"class": "huge"}))
    expect_rejected("PACK_PROP", "Grid.columns", *prepared(constraints=unknown))
    contradiction = table(lambda entries: next(e for e in entries if e["prop"] == "Transform.pose").update({"class": "positive"}))
    expect_rejected("PACK_PROP", "Transform.pose", *prepared(constraints=contradiction))
    presentation_entry = table(lambda entries: entries.append({"prop": "NumberText.decimals", "class": "finite"}))
    expect_rejected("PACK_PROP", "NumberText.decimals", *prepared(constraints=presentation_entry))
    # One rejection for every class: a class that contradicts the declared type (or is not the
    # inventory's required entry) rejects at the pack build; the shipped table accepts each class.
    for constraint_class in ("nonnegative", "positive", "integral-index", "chart", "display-clamp", "pose"):
        contradicted = table(lambda entries, c=constraint_class: entries.append({"prop": "Text.text", "class": c}))
        expect_rejected("PACK_PROP", "Text.text", *prepared(constraints=contradicted))
    finite_extra = table(lambda entries: entries.append({"prop": "Button.value", "class": "finite"}))
    expect_rejected("PACK_PROP", "Button.value", *prepared(constraints=finite_extra))

    # Pack-build numeric presentation domain: accepted values pass; out-of-domain values and a
    # non-number declaration reject with PACK_PROP naming the component and the prop.
    def decimals(value, prop="decimals", kind="number"):
        line = line_of(PACK.read_text(), "export class NumberTextProps")
        updated = line.replace(f"{prop}: number = {'1' if prop == 'minimumDigits' else '-1'}", f"{prop}: {kind} = {value}")
        assert updated != line or value == -1, line
        return replace_line(PACK.read_text(), "export class NumberTextProps", updated)
    def inventory_with(prop, **changes):
        data = json.loads(INVENTORY.read_text())
        for entry in data["components"]["NumberText"]["props"]:
            if entry["name"] == prop:
                entry.update(changes)
                return data
        raise AssertionError(prop)
    def minimum_digits(value):
        line = line_of(PACK.read_text(), "export class NumberTextProps").replace("minimumDigits: number = 1", f"minimumDigits: number = {value}")
        return replace_line(PACK.read_text(), "export class NumberTextProps", line)
    for value in (-1, 0, 6):
        pack, theme_path, constraint_path, inventory_path = prepared(pack_text=decimals(value), inventory=inventory_with("decimals", default=value))
        assert validate(pack, theme_path, constraint_path, inventory_path).returncode == 0, f"decimals {value} must be accepted"
    for value in (-2, 7, 1.5):
        expect_rejected("PACK_PROP", "NumberText.decimals",
                        *prepared(pack_text=decimals(value), inventory=inventory_with("decimals", default=value)))
    for value in (1, 4):
        pack, theme_path, constraint_path, inventory_path = prepared(pack_text=minimum_digits(value), inventory=inventory_with("minimumDigits", default=value))
        assert validate(pack, theme_path, constraint_path, inventory_path).returncode == 0, f"minimumDigits {value} must be accepted"
    for value in (0, 1.5):
        expect_rejected("PACK_PROP", "NumberText.minimumDigits",
                        *prepared(pack_text=minimum_digits(value), inventory=inventory_with("minimumDigits", default=value)))
    expect_rejected("PACK_PROP", "NumberText.decimals",
                    *prepared(pack_text=decimals('"-1"', kind="string"), inventory=inventory_with("decimals", type="string", default="-1")))

    # Renderer conformance: the packaged pack passes; an added component fails naming it.
    def conform(path):
        return run("java", "-cp", f"{CLASSES}:{STDLIB}", "dev.deal.embedding.RendererConformance", path)
    assert conform(packaged_pack).returncode == 0
    added_pack, _, _, _ = prepared(pack_text=added)
    result = conform(added_pack)
    assert result.returncode != 0 and "Extra" in (result.stdout + result.stderr), result.stderr

    # Compile-input target: the deterministic composer writes the target and the two sections
    # unchanged; an out-of-set target is rejected. The production compiler validates the same input.
    stage = ROOT / "build/v08-cutover/composer-test"
    assert stage.exists(), "the composer stage is missing; run the composer test first"
    composer_classes = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-composer-"))
    compiled = run("javac", "--release", "21", "-cp", CLASSES, "-d", composer_classes,
                  ROOT / "tests/tools/fixtures/CompileComposer.java", ROOT / "tests/tools/fixtures/ProviderDeclaration.java")
    assert compiled.returncode == 0, compiled.stderr
    classpath = f"{composer_classes}:{CLASSES}"
    for target in ("screen", "card"):
        result = run("java", f"-Ddeal.home={ROOT / 'dependencies/deal'}", "-cp", classpath,
                     "CompileComposer", ROOT, stage, target, stage / "metadata.json", "true")
        assert result.returncode == 0, result.stderr
        manifest = json.loads((ROOT / "build/v08-cutover/composed-executable/js/admission.json").read_text())
        assert manifest["target"] == target
        for name in ("prop-constraints.json", "card-profile.json"):
            assert manifest["propConstraints" if name.startswith("prop") else "cardProfile"] == json.loads((PACKAGED / name).read_text())
    invalid = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-target-")) / "stage"
    invalid.mkdir()
    for name in ("experience.deal", "experience.dealui"):
        shutil.copy(stage / name, invalid / name)
    result = run("java", f"-Ddeal.home={ROOT / 'dependencies/deal'}", "-cp", classpath,
                 "CompileComposer", ROOT, invalid, "print", stage / "metadata.json", "true")
    assert result.returncode != 0, "an out-of-set target must be rejected"
    assert "ADMISSION_TARGET" in (result.stdout + result.stderr), result.stdout + result.stderr
    assert not (invalid.parent / "composed-executable/js/admission.json").exists(), "an out-of-set target writes no manifest"
    compiler = (ROOT / "android/src/compiler/ExperienceCompiler.kt").read_text()
    for key in ('"target"', '"propConstraints"', '"cardProfile"', "CompileInput.target(target)", "ADMISSION_TARGET"):
        assert key in compiler, f"the compiler must carry {key}"

    # The deterministic composer rejects a candidate binding a removed name.
    bad = stage.parent / "removed-name"
    shutil.copytree(stage, bad, dirs_exist_ok=True)
    view = (bad / "experience.dealui").read_text()
    alias = re.search(r'import \* as (\w+) from "\./platform\.dealui-pack"', view)
    assert alias, view
    removed = alias.group(1) + "." + REMOVED[1] + "("
    assert alias.group(1) + ".Column(" in view
    (bad / "experience.dealui").write_text(view.replace(alias.group(1) + ".Column(", removed, 1))
    # An edited pair is not the freshly assembled one: it carries the last issued metadata as a
    # repaired source does, and admission treats it as repaired rather than fresh.
    result = run("java", f"-Ddeal.home={ROOT / 'dependencies/deal'}", "-cp", classpath,
                 "CompileComposer", ROOT, bad, "screen", bad / "metadata.json", "false")
    assert result.returncode != 0, "a removed-name candidate must not compile"
    assert "IntField" in (result.stdout + result.stderr), result.stdout + result.stderr

    # Unconsumed payload: a payload event bound to state-only action fields is rejected by the
    # deterministic compile, naming the component and the event, and nothing dispatches.
    unconsumed = stage.parent / "unconsumed"
    shutil.copytree(stage, unconsumed, dirs_exist_ok=True)
    view = (unconsumed / "experience.dealui").read_text()
    binding = 'onChange: action app.f_calculation_Change {value: payload}'
    assert binding in view, view
    (unconsumed / "experience.dealui").write_text(view.replace(binding, 'onChange: action app.f_calculation_Change {value: state.f_calculation_value}'))
    result = run("java", f"-Ddeal.home={ROOT / 'dependencies/deal'}", "-cp", classpath,
                 "CompileComposer", ROOT, unconsumed, "screen", unconsumed / "metadata.json", "false")
    assert result.returncode != 0, "an unconsumed payload must not compile"
    message = result.stdout + result.stderr
    assert "PAYLOAD_UNCONSUMED" in message and "NumberField" in message and "onChange" in message, message

    # Prompt trimming may remove examples, not checked APIs or event contracts.
    # Generated stage guidance tests assert the authoritative payload rule remains;
    # emitted-runtime tests execute numeric/boolean payloads rather than requiring
    # one particular example's spelling here.
    prompt = (ROOT / "core/custom-writing.deal").read_text()
    assert "There is no ui.NumberText" not in prompt
    assert "Bind only exported nominal actions" in prompt
    parts = (ROOT / "core/parts.deal").read_text()
    assert 'ui.Toggle(label: ' in parts and 'onChange: action app.Change_"+input.id+" {value: payload}' in parts
    assert 'export class Change_"+input.id+" { value: "+actionType+"; }' in parts
    assert 'When(state."+input.id+"_reason !== \\"\\")' in parts
    # The scalar-read Load pair is emitted, compiled and dispatched by the cutover and emitted-runtime
    # items; its view keeps the located sequence comparison and consumes the declared number payload.
    scalar = (ROOT / "core/scalar-read.deal").read_text()
    assert ',value:state."+prefix+"sequence,onClick:action app."+start+" {sequence: payload})' in scalar, scalar
    assert 'ui.Text(text:state."+prefix+"text_display)' in scalar and 'ui.Text(text:state."+prefix+"status)' in scalar, scalar
    assert 'onClick:action app."+start+" {sequence:state.' not in scalar
    emitted = ROOT / "build/v08-cutover/emitted/scalar-read"
    assert (emitted / "generation.sha256").exists(), "the scalar-read pair is missing; run the cutover test first"
    production = run("java", "-cp", f"{composer_classes}:{CLASSES}:{STDLIB}", "ProviderDeclaration")
    assert production.returncode == 0, production.stderr
    compiled_declaration = (emitted / "provider-0.d.deal").read_text().strip()
    assert compiled_declaration == production.stdout.strip(), f"the pair compiled against {compiled_declaration!r}"

    # Inventory isolation: the packaged AAR carries the pack, theme and table and no inventory bytes.
    with zipfile.ZipFile(ROOT / "build/embedding-library/deal-embedding.aar") as aar:
        names = aar.namelist()
    assert not any("ui-vocabulary" in name for name in names), "the inventory was packaged"
    for name in ("assets/embedding/platform.dealui-pack", "assets/embedding/prop-constraints.json",
                 "assets/embedding/card-profile.json", "assets/embedding/prop-declarations.json",
                 "assets/embedding/bindings/renderer-admission.js",
                 "assets/ui/platform.dealui-pack", "assets/ui/theme.json", "assets/ui/prop-constraints.json"):
        assert name in names, f"{name} is missing from the AAR"
    reduced = pathlib.Path(tempfile.mkdtemp(prefix="vocabulary-reduced-"))
    shutil.copy(THEME, reduced / "theme.json")
    assert run(sys.executable, ROOT / "tools/theme-build.py", reduced / "theme.json", reduced / "PackTheme.kt").returncode == 0
    build = (ROOT / "build.sh").read_text()
    inventory_uses = [line.strip() for line in build.splitlines() if "ui-vocabulary.json" in line]
    assert len(inventory_uses) == 2, inventory_uses
    assert sum('--inventory "$ROOT/tools/ui-vocabulary.json"' in line for line in inventory_uses) == 1, "only conformance consumes the inventory"
    assert sum(line.startswith('INPUTS=') for line in inventory_uses) == 1, "the inventory must invalidate its conformance stage"
    readers = [ROOT / "tools/theme-build.py", ROOT / "tools/GenerationGuidance.java"]
    for base in ("ui", "core", "android/src", "tests/tools", "tools"):
        readers.extend((ROOT / base).rglob("*"))
    for file in readers:
        if not file.is_file() or file.name in ("pack-conformance.py", "test-vocabulary.py", "build-demos.sh",
                                               "test-matrix.py", "test-build-stages.py"):
            continue
        if file.suffix not in (".sh", ".kt", ".py", ".java", ".json", ".deal", ".js"):
            continue
        assert "ui-vocabulary" not in file.read_text(errors="ignore"), f"{file} reads the inventory"

    print("vocabulary: conformance, projection, metadata, target, isolation and determinism items passed")



if __name__ == "__main__":
    main()

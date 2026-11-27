#!/usr/bin/env python3
"""Pack conformance validator and projection generator.

Reads the pack bytes, the theme version, the prop-constraint table and the
expected-vocabulary inventory; compares every listed fact in one pass; on success
writes the constraint, card-profile and prop-declaration projections (JSON data and
the generated Kotlin tables).  A rejection publishes nothing.
"""
import argparse
import json
import os
import pathlib
import sys
import tempfile

CLASSES = ["finite", "nonnegative", "positive", "integral-index", "chart", "range", "pose", "display-clamp"]
SCALAR_CLASSES = {"finite", "nonnegative", "positive", "integral-index", "chart", "range", "display-clamp"}


class Rejection(Exception):
    def __init__(self, code, category, subject, detail):
        super().__init__(f"{code}: {category} {subject}: {detail}")
        self.code, self.category, self.subject, self.detail = code, category, subject, detail

    def line(self):
        return f"{self.code} {self.category} {self.subject}: {self.detail}"


# ---------------------------------------------------------------- pack parsing

def tokenize(text):
    tokens, i, line, column = [], 0, 1, 1
    while i < len(text):
        c = text[i]
        if c in " \t\r\n":
            line, column, i = (line + 1, 1, i + 1) if c == "\n" else (line, column + 1, i + 1)
            continue
        if c == "/" and text.startswith("//", i):
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        if c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise Rejection("PACK_CONFORMANCE", "component", "<source>", "unterminated comment")
            lines = text[i:end + 2].count("\n")
            line, column, i = line + lines, 1, end + 2
            continue
        start_line, start_column = line, column
        if c.isalpha() or c == "_":
            start = i
            while i < len(text) and (text[i].isalnum() or text[i] == "_"):
                i += 1
            column += i - start
            tokens.append(("id", text[start:i], start_line, start_column))
            continue
        if c.isdigit():
            start = i
            while i < len(text) and (text[i].isdigit() or text[i] in ".eE+-"):
                i += 1
            column += i - start
            tokens.append(("number", text[start:i], start_line, start_column))
            continue
        if c == '"':
            i += 1
            column += 1
            value = []
            while i < len(text) and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                    column += 1
                    value.append({"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\"}.get(text[i], text[i]))
                else:
                    value.append(text[i])
                i += 1
                column += 1
            i += 1
            column += 1
            tokens.append(("string", "".join(value), start_line, start_column))
            continue
        tokens.append(("symbol", c, line, column))
        i += 1
        column += 1
    tokens.append(("eof", "", line, column))
    return tokens


class Cursor:
    def __init__(self, tokens):
        self.tokens, self.position = tokens, 0

    def peek(self):
        return self.tokens[self.position]

    def at(self, text):
        token = self.peek()
        return token[0] in ("id", "symbol") and token[1] == text

    def at_kind(self, kind):
        return self.peek()[0] == kind

    def take(self):
        token = self.tokens[self.position]
        self.position += 1
        return token

    def match(self, text):
        if not self.at(text):
            return False
        self.position += 1
        return True

    def require(self, text):
        if not self.at(text):
            token = self.peek()
            raise Rejection("PACK_CONFORMANCE", "component", "<source>", f"expected '{text}' at line {token[2]}")
        return self.take()

    def ident(self):
        token = self.peek()
        if token[0] != "id":
            raise Rejection("PACK_CONFORMANCE", "component", "<source>", f"expected identifier at line {token[2]}")
        self.position += 1
        return token[1]

    def string(self):
        token = self.peek()
        if token[0] != "string":
            raise Rejection("PACK_CONFORMANCE", "component", "<source>", f"expected string at line {token[2]}")
        self.position += 1
        return token[1]

    def qualified(self):
        name = self.ident()
        while self.match("."):
            name += "." + self.ident()
        return name


def pack_default(cursor):
    if cursor.match("-"):
        number = cursor.take()
        return (-1) * float(number[1])
    if cursor.match("["):
        values = []
        while not cursor.at("]"):
            values.append(pack_default(cursor))
            if not cursor.match(","):
                break
        cursor.require("]")
        return values
    if cursor.match("{"):
        values = {}
        while not cursor.at("}"):
            key = cursor.ident()
            cursor.require(":")
            values[key] = pack_default(cursor)
            if not cursor.match(","):
                break
        cursor.require("}")
        return values
    token = cursor.take()
    if token[0] == "string":
        return token[1]
    if token[0] == "number":
        return float(token[1])
    if token[1] == "true":
        return True
    if token[1] == "false":
        return False
    if token[1] == "null":
        return None
    raise Rejection("PACK_CONFORMANCE", "default", "<source>", f"unsupported default at line {token[2]}")


def parse_type(cursor):
    """Preserve value nullability independently of the field's `?` presence marker."""
    name = cursor.qualified()
    while cursor.match("["):
        cursor.require("]")
        name += "[]"
    if cursor.match("|"):
        cursor.require("null")
        name += " | null"
    return name


def parse_pack(text):
    cursor = Cursor(tokenize(text))
    while cursor.at("import"):
        while not cursor.at(";") and not cursor.at_kind("eof"):
            cursor.take()
        cursor.match(";")
    version = None
    if cursor.match("pack"):
        cursor.require("version")
        version = cursor.string()
        cursor.require(";")
    classes, components = {}, {}
    while not cursor.at_kind("eof"):
        cursor.require("export")
        if cursor.match("class"):
            name = cursor.ident()
            cursor.require("{")
            fields = []
            while not cursor.match("}"):
                field = cursor.ident()
                optional = cursor.match("?")
                cursor.require(":")
                kind = parse_type(cursor)
                has_default, default = False, None
                if cursor.match("="):
                    has_default, default = True, pack_default(cursor)
                cursor.match(";")
                fields.append({"name": field, "type": kind, "optional": optional,
                               "hasDefault": has_default, "default": default})
            if name in classes:
                raise Rejection("PACK_CONFORMANCE", "component", name, "duplicate props class")
            classes[name] = fields
        elif cursor.match("component"):
            name = cursor.ident()
            cursor.require("(")
            cursor.require("props")
            cursor.require(":")
            props = cursor.qualified()
            cursor.require(")")
            cursor.require(":")
            cursor.require("View")
            contracts = {"capabilities": [], "events": {}, "children": None, "accessibility": []}
            if cursor.match("{"):
                while not cursor.match("}"):
                    if cursor.match("children"):
                        required = cursor.match("required")
                        if not required:
                            cursor.match("optional")
                        types = []
                        if cursor.at_kind("id"):
                            types.append(cursor.qualified())
                            while cursor.match("|"):
                                types.append(cursor.qualified())
                        contracts["children"] = {"required": required, "types": types}
                    elif cursor.match("event"):
                        event = cursor.ident()
                        payload = None
                        if cursor.match("("):
                            cursor.require("payload")
                            cursor.require(":")
                            payload = parse_type(cursor)
                            cursor.require(")")
                        contracts["events"][event] = payload
                    elif cursor.match("accessibility"):
                        contracts["accessibility"].append(cursor.ident())
                    elif cursor.match("capability"):
                        contracts["capabilities"].append(cursor.string())
                    else:
                        token = cursor.peek()
                        raise Rejection("PACK_CONFORMANCE", "component", name, f"unknown contract at line {token[2]}")
                    cursor.require(";")
            if name in components:
                raise Rejection("PACK_CONFORMANCE", "component", name, "duplicate component declaration")
            components[name] = {"propsClass": props, "contracts": contracts}
        else:
            token = cursor.peek()
            raise Rejection("PACK_CONFORMANCE", "component", "<source>", f"expected class or component at line {token[2]}")
    for name, component in components.items():
        if component["propsClass"] not in classes:
            raise Rejection("PACK_CONFORMANCE", "component", name, f"undeclared props class {component['propsClass']}")
    return {"version": version, "classes": classes, "components": components}


# ------------------------------------------------------------ comparison pass

def declared_props(pack, name):
    return pack["classes"][pack["components"][name]["propsClass"]]


def prop_map(pack, name):
    return {field["name"]: field for field in declared_props(pack, name)}


def component_set(pack):
    return set(pack["components"])


def required_inventory_keys(inventory):
    return set(inventory["components"])


def compare_props(pack, name, expected, actual):
    exp = {prop["name"]: prop for prop in expected}
    act = {field["name"]: field for field in actual}
    for prop in exp:
        if prop not in act:
            raise Rejection("PACK_CONFORMANCE", "prop", f"{name}.{prop}", "missing declared prop")
    for prop in act:
        if prop not in exp:
            raise Rejection("PACK_CONFORMANCE", "prop", f"{name}.{prop}", "prop not in the expected vocabulary")
    for prop in sorted(exp):
        want, have = exp[prop], act[prop]
        if want["type"] != have["type"]:
            raise Rejection("PACK_CONFORMANCE", "prop", f"{name}.{prop}", f"type {have['type']} != {want['type']}")
        if "default" in want:
            if not have["hasDefault"]:
                raise Rejection("PACK_CONFORMANCE", "default", f"{name}.{prop}", "declared default missing")
            if isinstance(want["default"], bool) != isinstance(have["default"], bool):
                raise Rejection("PACK_CONFORMANCE", "default", f"{name}.{prop}",
                                f"default {have['default']!r} != {want['default']!r}")
            if normalize(want["default"]) != normalize(have["default"]):
                raise Rejection("PACK_CONFORMANCE", "default", f"{name}.{prop}",
                                f"default {have['default']!r} != {want['default']!r}")
            if have["optional"]:
                raise Rejection("PACK_CONFORMANCE", "optionality", f"{name}.{prop}",
                                "a prop with a declared default must not be optional")
        if want.get("required"):
            if have["hasDefault"] or have["optional"]:
                raise Rejection("PACK_CONFORMANCE", "optionality", f"{name}.{prop}", "must be required")
        if want.get("optional") and not have["optional"]:
            raise Rejection("PACK_CONFORMANCE", "optionality", f"{name}.{prop}", "must be optional")


def normalize(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, float) and value == int(value):
        return int(value)
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, dict):
        return {key: normalize(item) for key, item in value.items()}
    return value


def domain_ok(prop, value):
    if prop == "decimals":
        return isinstance(value, (int, float)) and not isinstance(value, bool) and (
            value == -1 or (value == int(value) and 0 <= value <= 6))
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value == int(value) and value >= 1


def validate(pack, theme, table, inventory):
    version = pack["version"]
    if theme.get("version") != inventory.get("version") or version != str(theme.get("version")):
        raise Rejection("PACK_CONFORMANCE", "version", "pack/theme",
                        f"pack version {version} disagrees with theme version {theme.get('version')}")
    if int(inventory["version"]) != theme["version"]:
        raise Rejection("PACK_CONFORMANCE", "version", "inventory/theme",
                        f"inventory version {inventory['version']} disagrees with theme version {theme['version']}")

    expected_components = required_inventory_keys(inventory)
    actual_components = component_set(pack)
    for name in sorted(expected_components - actual_components):
        raise Rejection("PACK_CONFORMANCE", "component", name, "spec component missing from the pack")
    for name in sorted(actual_components - expected_components):
        raise Rejection("PACK_CONFORMANCE", "component", name, "not a spec component")

    for name in sorted(expected_components):
        component = pack["components"][name]
        expected = inventory["components"][name]
        compare_props(pack, name, expected["props"], declared_props(pack, name))
        if "style" in expected:
            style = prop_map(pack, name).get("style")
            if style is None or style["type"] != "string" or not style["hasDefault"] or style["default"] != expected["style"]:
                raise Rejection("PACK_CONFORMANCE", "style", f"{name}.style",
                                f"must be string default {expected['style']!r}")
        elif "style" in prop_map(pack, name):
            raise Rejection("PACK_CONFORMANCE", "style", f"{name}.style", "must not be declared")
        if expected.get("accessibility"):
            label = prop_map(pack, name).get("accessibilityLabel")
            if label is None or not label["optional"] or label["type"] != "string":
                raise Rejection("PACK_CONFORMANCE", "accessibility", f"{name}.accessibilityLabel",
                                "interactive component must declare accessible label")
        elif "accessibilityLabel" in prop_map(pack, name):
            raise Rejection("PACK_CONFORMANCE", "accessibility", f"{name}.accessibilityLabel", "must not be declared")
        expected_contracts = ["accessibilityLabel"] if expected.get("accessibility") else []
        if component["contracts"]["accessibility"] != expected_contracts:
            raise Rejection("PACK_CONFORMANCE", "accessibility", name,
                            f"accessibility contracts {component['contracts']['accessibility']} != {expected_contracts}")
        capabilities = component["contracts"]["capabilities"]
        if len(capabilities) != 1:
            raise Rejection("PACK_CONFORMANCE", "capability", name,
                            f"exactly one capability required, found {len(capabilities)}")
        if capabilities[0] != f"renderer.{name}":
            raise Rejection("PACK_CONFORMANCE", "capability", name,
                            f"capability {capabilities[0]!r} must be 'renderer.{name}'")
        events = component["contracts"]["events"]
        expected_events = expected["events"]
        for event in sorted(expected_events):
            if event not in events:
                raise Rejection("PACK_CONFORMANCE", "event", f"{name}.{event}", "declared event missing")
            if events[event] != expected_events[event]:
                raise Rejection("PACK_CONFORMANCE", "event-payload", f"{name}.{event}",
                                f"payload {events[event]!r} != {expected_events[event]!r}")
        for event in sorted(set(events) - set(expected_events)):
            raise Rejection("PACK_CONFORMANCE", "event", f"{name}.{event}", "event not in the expected vocabulary")
        want_children = expected.get("children")
        have_children = component["contracts"]["children"]
        if want_children is None:
            if have_children is not None:
                raise Rejection("PACK_CONFORMANCE", "children", name, "leaf component must not declare a child block")
        else:
            if have_children is None:
                raise Rejection("PACK_CONFORMANCE", "children", name, "child block missing")
            if have_children["required"] != want_children["required"]:
                raise Rejection("PACK_CONFORMANCE", "children", name,
                                "cardinality disagrees with the expected vocabulary")
            if sorted(have_children["types"]) != sorted(want_children["types"]):
                raise Rejection("PACK_CONFORMANCE", "children", name,
                                f"allowed children {have_children['types']} != {want_children['types']}")

    # Card profile: every profile component and event is declared by the pack.
    profile = inventory["cardProfile"]
    for name in profile["components"]:
        if name not in actual_components:
            raise Rejection("PACK_CONFORMANCE", "card", name, "card component is not declared by the pack")
    for event in profile["events"]:
        component, _, name = event.partition(".")
        if name not in pack["components"][component]["contracts"]["events"]:
            raise Rejection("PACK_CONFORMANCE", "card", event, "card event is not declared by the pack")
    if any(value <= 0 for value in profile["limits"].values()):
        raise Rejection("PACK_CONFORMANCE", "card", "limits", "card limits must be positive")

    # Constraint table: shape, closed classes, type compatibility and agreement.
    if not isinstance(table, dict) or table.get("version") != int(pack["version"]):
        raise Rejection("PACK_PROP", "constraint", "table", "table version disagrees with the pack version")
    entries = table.get("constraints")
    if not isinstance(entries, list):
        raise Rejection("PACK_PROP", "constraint", "table", "constraints must be a list")
    identities = [entry.get("prop") for entry in entries if isinstance(entry, dict)]
    if identities != sorted(identities):
        raise Rejection("PACK_PROP", "constraint", "table", "constraints must be sorted by prop")
    if len(set(identities)) != len(identities):
        raise Rejection("PACK_PROP", "constraint", "table", "duplicate constraint entry")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"prop", "class"}:
            raise Rejection("PACK_PROP", "constraint", "table", "entry must declare exactly prop and class")
        prop, klass = entry["prop"], entry["class"]
        if klass not in CLASSES:
            raise Rejection("PACK_PROP", "constraint", prop, f"unknown class {klass!r}")
        component, _, field = prop.partition(".")
        if component not in actual_components:
            raise Rejection("PACK_PROP", "constraint", prop, "component is not declared by the pack")
        declared = prop_map(pack, component)
        if field not in declared:
            raise Rejection("PACK_PROP", "constraint", prop, "prop is not declared by the pack")
        kind = declared[field]["type"]
        if klass == "pose" and kind != "number[]":
            raise Rejection("PACK_PROP", "constraint", prop, f"pose requires number[], declared {kind}")
        if klass in SCALAR_CLASSES and kind != "number":
            raise Rejection("PACK_PROP", "constraint", prop, f"{klass} requires number, declared {kind}")
        if field in ("decimals", "minimumDigits"):
            raise Rejection("PACK_PROP", "constraint", prop,
                            "numeric presentation fields are a shared field domain, not a class entry")
    expected_entries = [{"prop": entry["prop"], "class": entry["class"]} for entry in inventory["constraints"]]
    if [normalize(entry) for entry in entries] != expected_entries:
        defined = {entry["prop"] for entry in entries}
        expected = {entry["prop"] for entry in expected_entries}
        missing = sorted(expected - defined)
        extra = sorted(defined - expected)
        raise Rejection("PACK_PROP", "constraint", (missing or extra)[0],
                        "missing constrained entry" if missing else "entry not required by the inventory")
    for name in sorted(actual_components):
        for field in declared_props(pack, name):
            if field["name"] in ("decimals", "minimumDigits"):
                if field["type"] != "number":
                    raise Rejection("PACK_PROP", "presentation", f"{name}.{field['name']}",
                                    f"declared {field['type']}, must be number")
                if field["hasDefault"] and not domain_ok(field["name"], field["default"]):
                    raise Rejection("PACK_PROP", "presentation", f"{name}.{field['name']}",
                                    f"default {field['default']!r} is outside the shared numeric presentation domain")
    return version


# ---------------------------------------------------------------- projections

KIND = {"string": "string", "number": "number", "number[]": "numbers",
        "boolean": "boolean", "Action": "action"}


def kind_of(kind):
    return KIND.get(kind, "json")


def constraint_projection(pack, table):
    declared = {f"{name}.{field['name']}" for name in pack["components"] for field in declared_props(pack, name)
                if field["type"] in ("number", "number[]")}
    table_map = {entry["prop"]: entry["class"] for entry in table["constraints"]}
    rows = [{"prop": prop, "class": table_map.get(prop, "finite")} for prop in sorted(declared)]
    return {"version": int(pack["version"]), "constraints": rows}


def card_projection(inventory):
    profile = inventory["cardProfile"]
    return {"version": int(inventory["version"]), "components": sorted(profile["components"]),
            "events": sorted(profile["events"]), "limits": profile["limits"]}


def declaration_projection(pack):
    components = []
    for name in sorted(pack["components"]):
        props = []
        for field in sorted(declared_props(pack, name), key=lambda item: item["name"]):
            record = {"name": field["name"], "kind": kind_of(field["type"])}
            if field["hasDefault"]:
                record["default"] = normalize(field["default"])
            if field["optional"]:
                record["optional"] = True
            else:
                record["required"] = True
            props.append(record)
        components.append({"component": name, "props": props})
    return {"version": int(pack["version"]), "components": components}


def kotlin_literal(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(float(value))
    if isinstance(value, str):
        escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        return f'"{escaped}"'
    raise TypeError(value)


def kotlin_projection(constraints, card, declarations):
    lines = ["package dev.deal.embedding", "",
             "/** Generated from the conforming pack; never hand-edited. */",
             "internal sealed interface PropDefault {",
             "    data class Text(val value: String) : PropDefault",
             "    data class Num(val value: Double) : PropDefault",
             "    data class Flag(val value: Boolean) : PropDefault",
             "    data class Json(val value: String) : PropDefault",
             "}",
             "",
             "internal object PackConstraints {",
             f"    const val version = {constraints['version']}",
             "    val classes: Map<String, String> = linkedMapOf("]
    for row in constraints["constraints"]:
        lines.append(f'        "{row["prop"]}" to "{row["class"]}",')
    lines.append("    )")
    lines.append("}")
    lines += ["", "internal object PackCardProfile {",
              f"    const val version = {card['version']}",
              f"    const val pictures = {int(card['limits']['pictures'])}",
              f"    const val shapesPerPicture = {int(card['limits']['shapesPerPicture'])}",
              f"    const val motions = {int(card['limits']['motions'])}",
              f"    const val nativeTimers = {int(card['limits']['nativeTimers'])}",
              "    val components: Set<String> = linkedSetOf("]
    for name in card["components"]:
        lines.append(f'        "{name}",')
    lines.append("    )")
    lines.append("    val events: Set<String> = linkedSetOf(")
    for event in card["events"]:
        lines.append(f'        "{event}",')
    lines.append("    )")
    lines += ["}", "", "internal object PackPropDeclarations {",
              "    data class Row(val component: String, val prop: String, val kind: String,",
              "                   val required: Boolean, val optional: Boolean, val default: PropDefault?)",
              "    val rows: List<Row> = listOf("]
    for component in declarations["components"]:
        for prop in component["props"]:
            default = "null"
            if "default" in prop:
                if prop["kind"] == "string":
                    default = f'PropDefault.Text({kotlin_literal(str(prop["default"]))})'
                elif prop["kind"] == "number":
                    default = f'PropDefault.Num({kotlin_literal(float(prop["default"]))})'
                elif prop["kind"] == "boolean":
                    default = f'PropDefault.Flag({"true" if prop["default"] else "false"})'
                else:
                    default = f'PropDefault.Json({kotlin_literal(json.dumps(normalize(prop["default"]), separators=(",", ":")))})'
            lines.append(f'        Row("{component["component"]}", "{prop["name"]}", "{prop["kind"]}", '
                         f'{str(prop.get("required", False)).lower()}, {str(prop.get("optional", False)).lower()}, {default}),')
    lines += ["    )", "    fun forComponent(component: String): List<Row> = rows.filter { it.component == component }", "}"]
    return "\n".join(lines) + "\n"


def write_atomic(path, text):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(handle, "w") as stream:
            stream.write(text)
        os.replace(temporary, path)
    except BaseException:
        stale = pathlib.Path(temporary)
        if stale.exists():
            stale.unlink()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", default="ui/platform.dealui-pack")
    parser.add_argument("--theme", default="ui/theme.json")
    parser.add_argument("--constraints", default="ui/prop-constraints.json")
    parser.add_argument("--inventory", default="tools/ui-vocabulary.json")
    parser.add_argument("--assets", default=None, help="directory receiving the projection JSON files")
    parser.add_argument("--kotlin", default=None, help="directory receiving the generated Kotlin tables")
    arguments = parser.parse_args()
    try:
        pack = parse_pack(pathlib.Path(arguments.pack).read_text())
        theme = json.loads(pathlib.Path(arguments.theme).read_text())
        table = json.loads(pathlib.Path(arguments.constraints).read_text())
        inventory = json.loads(pathlib.Path(arguments.inventory).read_text())
        validate(pack, theme, table, inventory)
        constraints = constraint_projection(pack, table)
        card = card_projection(inventory)
        declarations = declaration_projection(pack)
        if arguments.assets:
            write_atomic(pathlib.Path(arguments.assets) / "prop-constraints.json", json.dumps(constraints, indent=2) + "\n")
            write_atomic(pathlib.Path(arguments.assets) / "card-profile.json", json.dumps(card, indent=2) + "\n")
            write_atomic(pathlib.Path(arguments.assets) / "prop-declarations.json", json.dumps(declarations, indent=2) + "\n")
        if arguments.kotlin:
            write_atomic(pathlib.Path(arguments.kotlin) / "PackProjections.kt", kotlin_projection(constraints, card, declarations))
        print(f"pack conformance: {len(pack['components'])} components conform at version {pack['version']}")
    except Rejection as rejection:
        print(rejection.line(), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

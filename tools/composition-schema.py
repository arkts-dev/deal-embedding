#!/usr/bin/env python3
"""Generate portable closed-shape adapters from the authoritative native tool schema."""
import json
import pathlib
import sys

schema = json.loads(pathlib.Path(sys.argv[1]).read_text())
output = pathlib.Path(sys.argv[2])
functions = []

def quote(value):
    return json.dumps(value, ensure_ascii=False)

def resolve(value):
    if '$ref' in value:
        found = schema
        for part in value['$ref'].split('/')[1:]:
            found = found[part]
        return found
    return value

def generate(raw):
    value = resolve(raw)
    name = 'shape' + str(len(functions))
    functions.append(None)
    index = len(functions) - 1
    lines = []
    if 'anyOf' in value:
        lines.append('let accepted: boolean = false;')
        for branch in value['anyOf']:
            target = generate(branch)
            lines.append('try { ' + target + '(parent,key,expressions); accepted = true; } catch (error) { if (error.code !== "INVALID_CONFIG" && error.code !== "E8001") { throw error; } }')
            lines.append('if (accepted) { return null; }')
        lines.append('reject();')
    else:
        kind = value['type']
        if kind == 'object':
            lines.append('let value: table = parent[key];')
            properties = value['properties']
            keys = list(properties)
            if value.get('additionalProperties') is not False:
                raise ValueError('Composition objects must be closed')
            allowed = ' || '.join('field === ' + quote(k) for k in keys) or 'false'
            lines.append('for (let field: string of tables.keys(value)) { if (!(' + allowed + ')) { reject(); } }')
            required = set(value.get('required', []))
            for field, child in properties.items():
                target = generate(child)
                call = target + '(value,' + quote(field) + ',expressions);'
                lines.append(call if field in required else 'if (contains(tables.keys(value),' + quote(field) + ')) { ' + call + ' }')
        elif kind == 'array':
            item = resolve(value['items'])
            item_kind = item.get('type', 'object')
            carrier = {'object': 'table', 'string': 'string', 'number': 'number', 'boolean': 'boolean'}[item_kind]
            lines.append('let value: ' + carrier + '[] = parent[key];')
            target = generate(item)
            lines.append('for (let item: ' + carrier + ' of value) { let wrapper: table = {value:item}; ' + target + '(wrapper,"value",expressions); }')
        else:
            if kind not in ('number', 'string', 'boolean'):
                raise ValueError('Unsupported schema kind: ' + kind)
            lines.append('let value: ' + kind + ' = parent[key];')
            if kind == 'number':
                lines.append('if (value - value !== 0) { reject(); }')
            if 'enum' in value:
                allowed = ' || '.join('value === ' + quote(v) for v in value['enum'])
                lines.append('if (!(' + allowed + ')) { reject(); }')
    if value == schema['$defs']['formula']:
        lines.append('let expression: string = value.expr; expressions[expressions.length] = expression;')
    functions[index] = 'function ' + name + '(parent: table, key: string, expressions: string[]): null {\n    ' + '\n    '.join(lines) + '\n    return null;\n}\n'
    return name

root = generate(schema)
fragment = generate(schema['properties']['fragments']['items'])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text('''import * as json from "std/json";
import * as tables from "std/table";
// Generated from ui/fragment-contract.json; do not maintain another shape inventory.
function reject(): null { throw {code:"INVALID_CONFIG",message:"Invalid maintained fragment configuration"}; }
function contains(values: string[], value: string): boolean {
    for (let item: string of values) { if (item === value) { return true; } }
    return false;
}
''' + '\n'.join(functions) + '''
export function validate(input: table): null {
    let wrapper: table = {value:input}; let expressions: string[] = [];
    ''' + root + '''(wrapper,"value",expressions); return null;
}
export function fragmentFormulas(input: table): string[] {
    let wrapper: table = {value:input}; let expressions: string[] = [];
    ''' + fragment + '''(wrapper,"value",expressions); return expressions;
}
export function validateJson(input: string): null { return validate(json.parse(input)); }
''')

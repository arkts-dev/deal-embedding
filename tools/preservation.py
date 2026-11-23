#!/usr/bin/env python3
"""Preservation gate: saved sources, permissions, original request bytes and failed
evidence survive every gate. Never deletes, rewrites or resets anything.

A store has this layout:

    <root>/requests/<file>      original request bytes (tracked input copy)
    <root>/saved/<id>.deal      accepted source pair of a saved Experience
    <root>/saved/<id>.dealui
    <root>/saved.json           saved Experience identities [{"id": string, ...}]
    <root>/grants.json          {"module": {"granted": true, "epoch": integer}}
    <root>/evidence/<file>      retained failed evidence

`snapshot` records content digests; `verify` reports a located violation for every
removed or rewritten request, saved source, saved identity, grant or evidence file.
Additions are reported, never treated as violations. A failure exits nonzero and
leaves the store untouched.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

STORE = Path('build/verification/preservation/store')
BEFORE = Path('build/verification/preservation/before.json')
AFTER = Path('build/verification/preservation/after.json')
REQUEST_INPUTS = Path('tests/scenarios')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _files(root, pattern):
    directory = root / pattern
    if not directory.is_dir():
        return {}
    return {f'{pattern}/{path.relative_to(directory).as_posix()}': digest(path)
            for path in sorted(directory.rglob('*')) if path.is_file()}


def snapshot(root):
    root = Path(root)
    identities = []
    saved = root / 'saved.json'
    if saved.is_file():
        for entry in json.loads(saved.read_text()):
            identities.append(str(entry['id']))
    grants = {}
    grant_file = root / 'grants.json'
    if grant_file.is_file():
        for module, value in json.loads(grant_file.read_text()).items():
            grants[module] = {'granted': bool(value.get('granted')), 'epoch': int(value.get('epoch', 0))}
    return {
        'root': str(root),
        'requests': _files(root, 'requests'),
        'saved': _files(root, 'saved'),
        'identities': sorted(identities),
        'grants': grants,
        'evidence': _files(root, 'evidence'),
    }


def compare(before, after):
    """Return (violations, additions). Every violation names the retained item."""
    violations = []
    additions = []
    for section, label in (('requests', 'original request bytes'),
                           ('saved', 'saved source'),
                           ('evidence', 'failed evidence')):
        for name, value in before[section].items():
            if name not in after[section]:
                violations.append(f'{label} missing: {name}')
            elif after[section][name] != value:
                violations.append(f'{label} rewritten: {name}')
        for name in after[section]:
            if name not in before[section]:
                additions.append(f'{label} added: {name}')
    for identity in before['identities']:
        if identity not in after['identities']:
            violations.append(f'saved Experience identity missing: {identity}')
    for identity in after['identities']:
        if identity not in before['identities']:
            additions.append(f'saved Experience identity added: {identity}')
    for module, grant in before['grants'].items():
        current = after['grants'].get(module)
        if current is None:
            violations.append(f'permission grant revoked: {module}')
        elif current.get('granted') != grant.get('granted'):
            violations.append(f'permission grant changed: {module}')
        elif current.get('epoch') != grant.get('epoch'):
            violations.append(f'permission epoch changed: {module} ({grant.get("epoch")} -> {current.get("epoch")})')
    return violations, additions


def _seed(root, repo):
    """Ensure the deterministic device stand-in exists; existing data is never reset or rewritten."""
    root = Path(root)
    created = not root.exists()
    for directory in ('requests', 'saved', 'evidence'):
        (root / directory).mkdir(parents=True, exist_ok=True)
    target = root / 'requests' / 'requests.json'
    if not target.is_file():
        target.write_bytes((repo / REQUEST_INPUTS / 'requests.json').read_bytes())
        created = True
    sources = {
        'budget': ('export view Budget(state: app.BudgetState): View { ui.Column() { } }\n',
                   'import * as ui from "./platform.dealui-pack";\n'),
        'food': ('export view Food(state: app.FoodState): View { ui.Column() { } }\n',
                 'import * as ui from "./platform.dealui-pack";\n'),
        'equipment': ('export view Equipment(state: app.EquipmentState): View { ui.Column() { } }\n',
                      'import * as ui from "./platform.dealui-pack";\n'),
    }
    identities = []
    for name, (deal, dealui) in sources.items():
        deal_path = root / 'saved' / f'{name}.deal'
        dealui_path = root / 'saved' / f'{name}.dealui'
        if not deal_path.is_file():
            deal_path.write_text(deal)
            created = True
        if not dealui_path.is_file():
            dealui_path.write_text(dealui)
            created = True
        identities.append({'id': f'saved-{name}', 'title': name.capitalize(),
                           'source': {'deal': f'saved/{name}.deal', 'dealui': f'saved/{name}.dealui'}})
    if not (root / 'saved.json').is_file():
        (root / 'saved.json').write_text(json.dumps(identities, indent=2) + '\n')
        created = True
    if not (root / 'grants.json').is_file():
        (root / 'grants.json').write_text(json.dumps({
            'host/trip': {'granted': True, 'epoch': 1},
            'host/pizza': {'granted': True, 'epoch': 1},
            'host/rental': {'granted': True, 'epoch': 1},
        }, indent=2) + '\n')
        created = True
    evidence = {
        'food-provider-failure.json': {'scenario': 'food', 'classification': 'provider',
                                       'reason': 'installed provider unavailable'},
        'equipment-invalid-draft.json': {'scenario': 'equipment', 'classification': 'source',
                                         'reason': 'invalid draft'},
    }
    for name, value in evidence.items():
        path = root / 'evidence' / name
        if not path.is_file():
            path.write_text(json.dumps(value) + '\n')
            created = True
    return created


def _write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def request_files(repo):
    """Tracked original request bytes, keyed by their repository path."""
    repo = Path(repo)
    directory = repo / REQUEST_INPUTS
    return {f'{REQUEST_INPUTS.as_posix()}/{path.relative_to(directory).as_posix()}': digest(path)
            for path in sorted(directory.rglob('*')) if path.is_file()}


def _baseline_complete(state):
    return (bool(state['requests']) and bool(state['store']['saved'])
            and bool(state['store']['identities']) and bool(state['store']['grants'])
            and bool(state['store']['evidence']))


def begin(repo):
    repo = Path(repo)
    seeded = _seed(repo / STORE, repo)
    state = {
        'requests': request_files(repo),
        'store': snapshot(repo / STORE),
    }
    if not _baseline_complete(state):
        print('preservation: refusing to begin with an empty baseline; retention would prove nothing',
              file=sys.stderr)
        return 1
    _write(repo / BEFORE, state)
    print(f'preservation: before snapshot ({len(state["requests"])} request file(s), '
          f'{len(state["store"]["saved"])} saved source(s), {len(state["store"]["grants"])} grant(s), '
          f'{len(state["store"]["evidence"])} evidence file(s); store {"seeded" if seeded else "retained"})')
    return 0


def end(repo):
    repo = Path(repo)
    state = {
        'requests': request_files(repo),
        'store': snapshot(repo / STORE),
    }
    _write(repo / AFTER, state)
    try:
        before = json.loads((repo / BEFORE).read_text())
    except FileNotFoundError:
        print('preservation: no before snapshot exists; run begin first', file=sys.stderr)
        return 1
    baseline = {'requests': before['requests'],
                'store': {'saved': before['store']['saved'], 'identities': before['store']['identities'],
                          'grants': before['store']['grants'], 'evidence': before['store']['evidence']}}
    if not _baseline_complete(baseline):
        print('preservation: the before snapshot is empty or incomplete; retention cannot be proven',
              file=sys.stderr)
        return 1
    violations, additions = compare(
        {'requests': before['requests'], 'saved': before['store']['saved'],
         'identities': before['store']['identities'], 'grants': before['store']['grants'],
         'evidence': before['store']['evidence']},
        {'requests': state['requests'], 'saved': state['store']['saved'],
         'identities': state['store']['identities'], 'grants': state['store']['grants'],
         'evidence': state['store']['evidence']})
    # The device stand-in carries a retained copy of the original request bytes; it must survive too.
    store_requests, _ = compare(
        {'requests': before['store']['requests'], 'saved': {}, 'identities': [], 'grants': {}, 'evidence': {}},
        {'requests': state['store']['requests'], 'saved': {}, 'identities': [], 'grants': {}, 'evidence': {}})
    violations.extend(store_requests)
    if violations:
        for violation in violations:
            print(f'preservation: VIOLATION {violation}', file=sys.stderr)
        print(f'preservation: {len(violations)} retained item(s) missing or rewritten', file=sys.stderr)
        return 1
    print(f'preservation: {len(state["requests"])} request file(s), '
          f'{len(state["store"]["saved"])} saved source(s), {len(state["store"]["identities"])} saved identity/ies, '
          f'{len(state["store"]["grants"])} grant(s) and {len(state["store"]["evidence"])} failed-evidence file(s) retained; '
          f'{len(additions)} addition(s); nothing reset, rewritten or deleted')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['begin', 'end', 'snapshot', 'verify'])
    parser.add_argument('--repo', default='.')
    parser.add_argument('--root')
    parser.add_argument('--out')
    parser.add_argument('--before')
    parser.add_argument('--after')
    args = parser.parse_args()
    if args.command == 'begin':
        return begin(args.repo)
    if args.command == 'end':
        return end(args.repo)
    if args.command == 'snapshot':
        if not args.root or not args.out:
            parser.error('snapshot requires --root and --out')
        _write(args.out, snapshot(args.root))
        return 0
    if not args.before or not args.after:
        parser.error('verify requires --before and --after')
    violations, additions = compare(json.loads(Path(args.before).read_text()),
                                    json.loads(Path(args.after).read_text()))
    for violation in violations:
        print(f'preservation: VIOLATION {violation}', file=sys.stderr)
    for addition in additions:
        print(f'preservation: addition {addition}')
    return 1 if violations else 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Resolve the standalone library's declared inputs from tools/library-inputs.txt.

One declaration feeds the build cache key, the standalone boundary audit and the `$ROOT/...`
input scan of `build.sh`, so a declared root cannot change without the audit seeing it.
Dependency entries must resolve to a pinned independent checkout (`dependencies/` or a matching
absolute override), never to application source or tests; nothing under demos/ or tests/ is accepted.
"""
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'tools/library-inputs.txt'
UI_SOURCES = ['UiModel', 'UiParser', 'UiDiagnostic', 'UiDiagnosticJson', 'UiRepairDiagnostics',
              'UiCompilerWorkspace', 'CanonicalCompiler', 'UiChecker', 'UiFlowScope', 'UiBorrowedValueChecker',
              'UiDealGenerator', 'UiViewBindings', 'UiSourceGenerator', 'DealUiDealSource', 'UiCheckpointCompiler']


class DeclarationError(Exception):
    pass


def dependency_roots():
    lock_repo = ROOT / 'dependencies'
    deal = Path(os.environ.get('DEAL_ROOT') or lock_repo / 'deal').resolve()
    deal_ui = Path(os.environ.get('DEAL_UI_ROOT') or lock_repo / 'deal-ui').resolve()
    return {'deal': deal, 'deal-ui': deal_ui}


def declared():
    """Return (repo-relative entries, dependency entries, flags) exactly as declared."""
    repo, deps, flags = [], [], []
    for number, line in enumerate(MANIFEST.read_text().splitlines(), 1):
        entry = line.strip()
        if not entry or entry.startswith('#'):
            continue
        if entry == 'dep-ui-sources':
            flags.append(entry)
        elif entry.startswith('dep:'):
            _, name, path = entry.split(':', 2)
            deps.append((name, path, number))
        else:
            repo.append(entry)
    return repo, deps, flags


def resolve(require=True):
    """Resolve the declaration to concrete paths; a missing input is a hard error."""
    repo, deps, flags = declared()
    roots = dependency_roots()
    paths = []
    for entry in repo:
        if any(character in entry for character in '*?['):
            matches = sorted(ROOT.glob(entry))
            if not matches:
                raise DeclarationError(f'library input pattern matched nothing: {entry}')
            paths.extend(matches)
        else:
            path = ROOT / entry
            if require and not path.exists():
                raise DeclarationError(f'library input is absent: {entry}')
            paths.append(path)
    if 'dep-ui-sources' in flags:
        for name in UI_SOURCES:
            path = roots['deal-ui'] / 'src/main/java/deal/ui' / f'{name}.java'
            if require and not path.is_file():
                raise DeclarationError(f'library input is absent: {path}')
            paths.append(path)
    for name, relative, number in deps:
        path = roots[name] / relative
        if path.is_relative_to(ROOT / 'demos') or path.is_relative_to(ROOT / 'tests'):
            raise DeclarationError(f'library-inputs.txt:{number}: dependency path inside application source: {path}')
        if require and not path.exists():
            raise DeclarationError(f'library input is absent: {path}')
        paths.append(path)
    return paths


def boundary_violations(paths):
    """Every declared library input must stay inside the library, its pinned dependencies or a declared
    root of the build script; application source and test trees are never allowed."""
    violations = []
    root = ROOT.resolve()
    allowed_dependency = [path.resolve() for path in dependency_roots().values()]
    roots = ('build.sh', 'dependencies.lock.json', 'tools', 'core', 'api', 'ui', 'android', 'docs')
    for path in paths:
        resolved = Path(path).resolve()
        if any(resolved.is_relative_to(base) for base in allowed_dependency):
            continue
        if resolved == root or resolved.is_relative_to(root):
            relative = resolved.relative_to(root).as_posix()
            if relative == 'demos' or relative.startswith('demos/'):
                violations.append(f'library build reads application source or tests: {relative}')
            elif relative == 'tests' or relative.startswith('tests/'):
                violations.append(f'library build reads application source or tests: {relative}')
            elif not any(relative == name or relative.startswith(name + '/') for name in roots):
                violations.append(f'library build input is outside the library roots: {relative}')
        elif not any(resolved.is_relative_to(base) for base in allowed_dependency):
            violations.append(f'library build input is outside the library and its pinned dependencies: {path}')
    return violations


def main():
    try:
        paths = resolve()
    except DeclarationError as error:
        print(f'library inputs: {error}', file=sys.stderr)
        return 1
    if '--audit' in sys.argv[1:]:
        violations = boundary_violations(paths)
        for violation in violations:
            print(f'library inputs: {violation}', file=sys.stderr)
        return 1 if violations else 0
    if '--lines' in sys.argv[1:]:
        print('\n'.join(str(path) for path in paths))
        return 0
    print(' '.join(str(path) for path in paths))
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Generate the native theme projection from the authoritative portable token file."""
import json
import pathlib
import sys

theme = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert theme['version'] == 1
geometry = theme['geometry']
assert all(k.isidentifier() and type(v) is int and 0 < v <= 1024 for k, v in geometry.items())
types = {'headlineMedium', 'titleMedium', 'headlineSmall', 'labelLarge', 'bodyLarge', 'bodySmall'}
colors = {'surfaceContainer', 'primary', 'onSurfaceVariant'}
lines = ['package dev.deal.embedding', 'import androidx.compose.material3.MaterialTheme',
         'import androidx.compose.runtime.Composable', 'import androidx.compose.ui.unit.dp',
         'internal object PackTheme {']
for name, value in geometry.items():
    lines.append(f'    val {name} = {value}.dp')
for name, role in theme['roles'].items():
    assert name.isidentifier() and role in types | colors
    source = 'typography' if role in types else 'colorScheme'
    lines.append(f'    val {name} @Composable get() = MaterialTheme.{source}.{role}')
lines.append('}')
pathlib.Path(sys.argv[2]).write_text('\n'.join(lines) + '\n')

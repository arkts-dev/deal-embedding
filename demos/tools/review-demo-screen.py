#!/usr/bin/env python3
"""Opt-in remote visual review of one local screenshot against a local design reference.
Requires DEXTER_DEEPSEEK_API_KEY. Sends image/reference only; no repository or logs.
"""
import argparse, base64, json, os, pathlib, urllib.request
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('screenshot', type=pathlib.Path)
p.add_argument('--reference', type=pathlib.Path, required=True)
p.add_argument('--goal', required=True)
a = p.parse_args()
key = os.environ.get('DEXTER_DEEPSEEK_API_KEY')
if not key:
    p.error('DEXTER_DEEPSEEK_API_KEY is missing')
image = a.screenshot.read_bytes()
if not image.startswith(b'\x89PNG\r\n\x1a\n') or len(image) > 8 * 1024 * 1024:
    p.error('Provide a PNG smaller than 8 MiB')
prompt = ('Review only the visible screen, not runtime correctness. Be critical and terse. '
          'Report concrete hierarchy, spacing, clipping, density and discoverability problems, '
          'then a visual-only verdict. Do not infer hidden states or claim behavior passed.\nGoal: '
          + a.goal + '\nDesign reference:\n' + a.reference.read_text()[:16000])
body = {'model': 'deepseek-flash', 'messages': [{'role': 'user', 'content': [
    {'type': 'text', 'text': prompt},
    {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,' + base64.b64encode(image).decode()}}
]}], 'max_tokens': 1000, 'stream': False, 'thinking': {'type': 'disabled'}}
request = urllib.request.Request('https://api.deepseek.com/v1/chat/completions',
    data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=90) as response:
    result = json.load(response)
content = result['choices'][0]['message'].get('content')
if not content:
    raise RuntimeError('Visual reviewer returned no visible findings; review is unverified')
print(content)

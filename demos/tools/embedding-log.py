#!/usr/bin/env python3
"""Live execution activity, not model reasoning. JSON output and bounded artifact drilldown."""
import argparse, datetime, json, os, pathlib, subprocess, time
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--package',default='dev.deal.apps.organizer')
p.add_argument('--follow',action='store_true')
p.add_argument('--json',action='store_true')
p.add_argument('--trace')
p.add_argument('--artifact')
a=p.parse_args()
adb=[str(pathlib.Path.home()/'Android/Sdk/platform-tools/adb')]
if os.environ.get('ANDROID_SERIAL'): adb+=['-s',os.environ['ANDROID_SERIAL']]
def read(name):
 r=subprocess.run(adb+['shell','run-as',a.package,'cat','files/embedding-log/'+name],capture_output=True,text=True,timeout=15)
 return r.stdout if r.returncode==0 else ''
if a.artifact:
 import re
 if not re.fullmatch('[a-f0-9-]{36}',a.artifact):p.error('Invalid artifact identity')
 data=read(a.artifact+'.json')
 print(data if data else 'Artifact unavailable (omitted, evicted or logger failure).')
 raise SystemExit
seen=set()
last_health=None
try:
 while True:
  for line in (read('previous.jsonl')+'\n'+read('events.jsonl')).splitlines():
   try:e=json.loads(line)
   except ValueError:continue
   key=(e['trace'],e['sequence'],e['time'],e['span'])
   if key in seen:continue
   seen.add(key)
   if a.trace and e['trace']!=a.trace:continue
   if a.json:print(json.dumps(e),flush=True)
   else:
    icon={'started':'→','completed':'✓','failed':'!','cancelled':'×'}.get(e['outcome'],'·')
    when=datetime.datetime.fromtimestamp(int(e['time'])/1000).strftime('%d %b %H:%M')
    print(f"{when} {icon} {e['trace'][:8]} {e['summary']} {e['code']} {e['durationMs']}ms [{e['span']}]"+(f" artifact={e['artifact']} ({e['content']})" if e['artifact'] else ''),flush=True)
  health=read('health.json')
  if health and health != last_health:
   try: state='ACTIVE' if json.loads(health).get('active',True) else 'RECOVERED (earlier log incomplete)'
   except ValueError: state='UNREADABLE'
   print('LOGGER HEALTH '+state+' '+health,flush=True)
  last_health=health
  if not a.follow:break
  time.sleep(.3)
except KeyboardInterrupt:pass

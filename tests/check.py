#!/usr/bin/env python3
"""One bounded deterministic check per invocation. No hidden build/device/inference work."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tests/tools'))
from host_check import prepare, run

STAGES = ['java_base','mechanisms','kotlin','generation','discovery','assistance_policy','logging','protocol','assistance','package_aar']
PAIRS = ['absent','chat','card','boolean','written','assembly','operation','context-operation','entries','entries-empty','entries-aggregate','row-tap','presentation','numeric-text','foreign','descriptor','trigger','clock','clock-input','numeric']
GROUPS = ['contracts','timers','reading','quick','points','ideas','drafts','aggregate','lists','issued-bodies']
LANES = {
    'infra': ['python3','tests/tools/test-build-stages.py'],
    'harness': ['python3','tests/tools/test-host-check.py'],
    'cache': ['python3','tests/tools/test-build-cache.py'],
    'construction': ['python3','tests/tools/test-construction-contracts.py'],
    'catalogue': ['python3','tests/tools/test-catalogue.py'],
    'composer-prepare': ['python3','tests/tools/test-composer.py','--prepare-only'],
    'composer': ['python3','tests/tools/test-composer.py','--contracts-only'],
    'renderer': ['bash','tests/tools/test-renderer-rules.sh'],
    'renderer-admission': ['node','tests/tools/test-renderer-admission.js'],
    'renderer-patterns': ['node','tests/tools/test-renderer-patterns.js'],
    'matrix': ['python3','tests/tools/test-matrix.py'],
    'boundary-plan': ['python3','tools/boundary-proof.py','--plan'],
}
LANES.update({'composer:'+g:['python3','tests/tools/test-composer.py','--group',g] for g in ['validation','basics','clocks','operations','entries','presentation']})
LANES.update({'pair:'+p:['python3','tests/tools/test-composer.py','--pair',p] for p in PAIRS})
LANES.update({'catalogue:'+g:['python3','tests/tools/test-catalogue-runtime.py','--group',g] for g in GROUPS})
LANES.update({'build:'+s:['bash','build.sh'] for s in STAGES})
LANES.update({'isolation:'+s:['python3','tools/boundary-proof.py','--prepare-stage',s] for s in STAGES})
LANES.update({'boundary:'+p.parent.name:['python3','tools/boundary-proof.py','--target',p.parent.name]
              for p in sorted((ROOT/'demos/android/apps').glob('*/AndroidManifest.xml'))})


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('lane', nargs='?', choices=sorted(LANES))
    parser.add_argument('--list', action='store_true')
    args=parser.parse_args()
    if args.list or not args.lane:
        for name, command in LANES.items():
            print(name+': '+' '.join(command))
        print('One lane only, 40s wall bound. Selection is not full-gate acceptance. Seams: test-verification-seams.py --list / --seam ID.')
        return 0
    os.chdir(ROOT)
    lock=prepare(ROOT)
    env={**os.environ,'DEAL_BUILD_LOCK_HELD':str(ROOT)}
    if args.lane.startswith('build:'):
        # Read toolchain declarations only, without invoking the packaging builder.
        spec=importlib.util.spec_from_file_location('boundary',ROOT/'tools/boundary-proof.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        android,aidl,_=module.sdk_paths()
        env.update(ANDROID_JAR=str(android),AIDL=str(aidl),RUNTIME_CP=module.runtime_classpath(),
                   OUTPUT_DIR=str(ROOT/'build/embedding-library'),DEAL_BUILD_STAGE=args.lane.split(':')[1])
    directory=ROOT/'build/verification/short-checks';directory.mkdir(parents=True,exist_ok=True)
    name=args.lane.replace(':','-')+'-'+str(time.time_ns())
    log=directory/(name+'.log');receipt=directory/(name+'.json')
    started=time.monotonic()
    print(f'[short-check] {args.lane}: bound=40s; log=build/verification/short-checks/{name}.log',flush=True)
    with log.open('w') as output:
        result=run(LANES[args.lane], timeout=40, env=env, stdout=output, stderr=subprocess.STDOUT)
    elapsed=time.monotonic()-started
    # Always retain the complete log, display only a bounded tail.
    with log.open('rb') as source:
        source.seek(0,2);size=source.tell();source.seek(max(0,size-3000))
        print(source.read().decode(errors='replace'))
    print(f'[short-check] {args.lane}: exit={result.returncode} elapsed={elapsed:.2f}s',flush=True)
    receipt.write_text(json.dumps({'lane':args.lane,'exit':result.returncode,'elapsed':elapsed,
                                   'command':LANES[args.lane]},indent=2))
    if result.returncode in (124,137):
        print('TIMEOUT, not a pass. Stage caches retained; inspect named stage, do not restart the full gate.',file=sys.stderr)
    return result.returncode


if __name__=='__main__':
    sys.exit(main())

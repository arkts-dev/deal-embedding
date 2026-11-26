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
import lane_bounds

STAGES = ['java_base','mechanisms','kotlin','generation','discovery','assistance_policy','logging','protocol','assistance','package_aar']
PAIRS = ['absent','chat','card','boolean','written','assembly','operation','context-operation','entries','entries-empty','entries-aggregate','row-tap','presentation','numeric-text','foreign','descriptor','trigger','clock','clock-input','numeric']
GROUPS = ['contracts','timers','reading','quick','points','ideas','drafts','day','calendar','descriptor','live','probe','aggregate','lists','issued-bodies']
# For the construction, catalogue and composer harnesses a lane bound is the sum of the stages the
# harness declares for itself, derived from that declaration (`tests/tools/lane_bounds.py`, which those
# harnesses also pass to their own calls) rather than from a literal beside it: the dependency check, the
# Java fixture compile, a cold fixture DEAL compile, every assertion stage the lane drives, and the
# harness's own receipt. A lane bound tighter than a stage inside it kills the lane before the harness
# names the stage that overran, which is the unnamed-timeout failure mode this derivation removes. The
# composer runs three Java assertion classes plus one node group per composer group it drives, and a pair
# lane compiles its own pair and then drives only the assertion harnesses its maps declare for it.
# A `build:` or `isolation:` lane prepares one declared library stage, so its bound is that stage's
# declared bound from `tools/stage-bounds.txt` plus the isolated tree's copy and input audit: the stage's
# own bound stops a runaway stage first, with its name, instead of an outer lane kill. A `boundary:` lane
# runs one selected standalone proof under the proof's own declared bound. No assertion result is cached
# by any bound, so a wider preparation bound weakens no check.
def boundary_module():
    spec=importlib.util.spec_from_file_location('boundary',ROOT/'tools/boundary-proof.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def lane_bound(lane):
    if lane.startswith('build:'):
        # One declared stage of the library build, bounded by its own declared preparation bound plus the
        # conformance assertion it may run beside it.
        return boundary_module().stage_bounds()[lane.split(':', 1)[1]] + 30
    if lane.startswith('isolation:'):
        # The same stage prepared in the isolated tree, inside the proof's own outer bound.
        return boundary_module().stage_bounds()[lane.split(':', 1)[1]] + 60
    if lane.startswith('boundary:') or lane == 'boundary-plan':
        # One selected proof, and the preview that plans and prepares the isolated tree it binds, run the
        # proof's own stages, so each is bounded by the proof itself rather than by an outer number the
        # proof's inner guard would never reach.
        return boundary_module().PROOF_SECONDS
    if lane == 'catalogue':
        return lane_bounds.catalogue_contract_lane()
    if lane.startswith('catalogue:'):
        # One catalogue runtime section group, or one of the `all`/`local` bundles that run every section
        # in one process, inside the harness's own per-group and per-bundle bound.
        return lane_bounds.catalogue_lane(lane.split(':', 1)[1])
    if lane == 'construction':
        return lane_bounds.construction_lane()
    if lane in ('structure', 'storage-host'):
        # The two edit/store lanes compile a fixture and then drive one node group, exactly as the
        # construction lane does, so their bound is that lane's declaration, never a literal here.
        return lane_bounds.fixture_lane(lane_bounds.CONTRACT_NODE)
    if lane == 'interaction':
        return lane_bounds.interaction_lane()
    if lane == 'surface':
        return lane_bounds.surface_lane()
    if lane == 'renderer':
        # The renderer rules compile the JVM harness before they assert: a cold run measured 34.8 s here,
        # 33 s of it compilation, so this lane keeps the headroom the other compiling lanes keep instead of
        # the shared default.
        return 120
    if lane == 'composer':
        return lane_bounds.composer_lane(len(lane_bounds.COMPOSER_GROUPS))
    if lane == 'composer-prepare':
        return lane_bounds.composer_prepare_lane()
    if lane.startswith('composer:'):
        return lane_bounds.composer_lane(1)
    if lane.startswith('pair:'):
        return lane_bounds.pair_lane(lane.split(':', 1)[1])
    if lane == 'infra':
        # The stage-orchestration item drives many fake builds under one cap per stage kind: eight 15 s
        # runs, eight 40 s runs, eleven 40 s runs, one 60 s proof preview and one 120 s lane refusal. Its own
        # guard counts those runs against the declaration, and the lane bound is that declaration's sum, so a
        # hung item is named by its own inner cap instead of being killed by an outer number nobody derived.
        return lane_bounds.infra_lane()
    if lane == 'cache':
        # The cache-control item drives its own fake harnesses and extracted gate blocks under separate
        # caps, declared and counted the same way: six 40 s, eight 40 s, one 20 s, five 450 s and thirteen
        # 150 s runs. The lane bound contains that sum rather than the widest cap.
        return lane_bounds.cache_lane()
    if lane == 'harness':
        return 120
    return 40
LANES = {
    'infra': ['python3','tests/tools/test-build-stages.py'],
    'harness': ['python3','tests/tools/test-host-check.py'],
    'cache': ['python3','tests/tools/test-build-cache.py'],
    'construction': ['python3','tests/tools/test-construction-contracts.py'],
    'structure': ['python3','tests/tools/test-structure-contracts.py'],
    'storage-host': ['python3','tests/tools/test-storage-host.py'],
    'interaction': ['python3','tests/tools/test-interaction-contracts.py'],
    'surface': ['python3','tests/tools/test-surface-script.py'],
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
# The short lane bounds every whole invocation at 40 s (below), which is deliberately shorter than a cold
# compile of this toolchain: a build:, isolation: or boundary: lane that misses its artifact stops at
# that bound with its name and the retained predecessors instead of returning a pass, and the complete
# gate is what validates those stages. Nothing here is a substitute for running the gate.
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
        print('One lane only, a per-lane wall bound (40s, wider for harnesses with cold compiler preparation). Selection is not full-gate acceptance. Seams: test-verification-seams.py --list / --seam ID.')
        return 0
    if os.environ.get('DEAL_BUILD_STAGE_SECONDS','').strip() and args.lane.startswith(('build:','isolation:')):
        # This lane's own bound is derived from the declaration, so an override it cannot contain would be
        # killed by the outer bound first. The standalone proof refuses the same variable, with this message;
        # the refusal comes before the lock and the dependency check, so it is the rule that answers.
        print(boundary_module().STAGE_OVERRIDE_REFUSAL, file=sys.stderr)
        return 2
    os.chdir(ROOT)
    lock=prepare(ROOT)
    env={**os.environ,'DEAL_BUILD_LOCK_HELD':str(ROOT)}
    if args.lane.startswith('build:'):
        # Read toolchain declarations only, without invoking the packaging builder.
        module=boundary_module()
        android,aidl,_=module.sdk_paths()
        env.update(ANDROID_JAR=str(android),AIDL=str(aidl),RUNTIME_CP=module.runtime_classpath(),
                   OUTPUT_DIR=str(ROOT/'build/embedding-library'),DEAL_BUILD_STAGE=args.lane.split(':')[1])
    directory=ROOT/'build/verification/short-checks';directory.mkdir(parents=True,exist_ok=True)
    name=args.lane.replace(':','-')+'-'+str(time.time_ns())
    log=directory/(name+'.log');receipt=directory/(name+'.json')
    started=time.monotonic()
    bound=lane_bound(args.lane)
    print(f'[short-check] {args.lane}: bound={bound}s; log=build/verification/short-checks/{name}.log',flush=True)
    with log.open('w') as output:
        result=run(LANES[args.lane], timeout=bound, env=env, stdout=output, stderr=subprocess.STDOUT)
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

#!/usr/bin/env python3
"""Real compiled DEAL composer with JVM syntax mechanisms; no model or alternate emitter."""
import argparse, json, os, pathlib, shutil, time
from host_check import prepare, run as bounded_run, compile_artifact
parser=argparse.ArgumentParser(description=__doc__)
GROUPS=['validation','basics','clocks','operations','entries','presentation']
parser.add_argument('--group',choices=GROUPS,help='one assertion group; all group receipts required for pair snapshot')
parser.add_argument('--prepare-only',action='store_true',help='prepare compiler artifacts only, no assertion or snapshot claim')
parser.add_argument('--contracts-only',action='store_true',help='compile and run construction/assembly assertions; do not compile emitted pairs')
parser.add_argument('--pair',choices=['numeric','chat','card','absent','boolean','written','assembly','operation','context-operation','entries','entries-empty','entries-aggregate','row-tap','presentation','numeric-text','foreign','descriptor','trigger','clock','clock-input'],help='run one emitted-pair lane from a validated contracts-only snapshot')
args=parser.parse_args()
if args.pair and (args.contracts_only or args.prepare_only or args.group): parser.error('--pair and --contracts-only are exclusive')
started=time.monotonic()
def run(command, **kwargs):
    before=time.monotonic()
    try: return bounded_run(command, timeout=45, **kwargs)
    finally: print(f'composer stage {command[0]} {command[-1]}: {time.monotonic()-before:.2f}s',flush=True)
root=pathlib.Path(__file__).resolve().parents[2]
deal=pathlib.Path(os.environ.get('DEAL_ROOT',root/'dependencies/deal')).resolve()
deal_ui=pathlib.Path(os.environ.get('DEAL_UI_ROOT',root/'dependencies/deal-ui')).resolve()
lock=prepare(root)  # verifies pins and holds the same nonblocking build lock
stage=root/'build/v08-cutover/composer-test'
cp=str(root/'build/embedding-library/classes')
classes=stage/'classes'
import hashlib
def input_digest():
    paths=[root/'tests/tools/test-composer.py',root/'tests/tools/host_check.py',root/'tests/tools/test-composer.js',root/'dependencies.lock.json']
    for directory in [root/'core',root/'tools',root/'tests/tools/fixtures',deal_ui/'ui',root/'build/embedding-library/classes']:
        paths.extend(p for p in directory.rglob('*') if p.is_file() and (p.suffix in ('.deal','.java','.js','.class') or p.name=='dependencies.sha256'))
    digest=hashlib.sha256()
    for path in sorted(set(paths)):
        digest.update(str(path.relative_to(root)).encode());digest.update(path.read_bytes())
    return digest.hexdigest()
def outputs():
    return {str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file() and (p.name in ('experience.deal','experience.dealui','metadata.json') or p.suffix in ('.class','.js'))}
receipt=stage/'contracts-snapshot.json'
if args.pair:
    snapshot=json.loads(receipt.read_text())
    assert snapshot['input']==input_digest(),'contracts snapshot inputs changed: rerun --contracts-only'
    assert snapshot['outputs']==outputs(),'contracts snapshot outputs changed: rerun --contracts-only'
else:
    stage=root/'build/v08-cutover/composer-test';stage.mkdir(parents=True,exist_ok=True)
    source=stage/'src';shutil.rmtree(source,ignore_errors=True);source.mkdir(exist_ok=True)
    receipt.unlink(missing_ok=True)
    embedding=root
    for p in (embedding/'core').glob('*.deal'):
        if p.name!='generation.deal':shutil.copy(p,source/p.name)
    shutil.copy(root/'tests/tools/fixtures/composer-contracts.deal',source/'checks.deal')
    shutil.rmtree(stage/'ui',ignore_errors=True)
    shutil.copytree(deal_ui/'ui',stage/'ui')
    for obsolete in stage.glob('*.d.deal'): obsolete.unlink()
    externals={}
    for p in (embedding/'core/host').glob('*.d.deal'):
        shutil.copy(p,stage/p.name);externals['embedding' if p.name=='embedding.d.deal' else 'embedding/'+p.name.removesuffix('.d.deal')]={'declaration':p.name}
    (stage/'deal.json').write_text(json.dumps({'languageVersion':'1.6','backend':'js','moduleRoots':['src','ui'],'externals':externals}))
    cp=str(root/'build/embedding-library/classes')
    classes=stage/'classes';classes.mkdir(exist_ok=True)
    java_sources=[*[embedding/'tools'/f'{name}.java' for name in ['FragmentSyntax','FragmentRegions','FragmentQualifier','FragmentMechanisms','FragmentAnalysis']],*[root/'tests/tools/fixtures'/f'{name}.java' for name in ['CompileComposer','FragmentRegionsTest','FragmentQualifierTest','FragmentMechanismsTest']]]
    compile_artifact(root,'composer-java',[*java_sources,cp,root/'tests/tools/host_check.py',pathlib.Path(shutil.which('javac')).resolve().parents[1]/'release'],[classes],
        ['bash','-euc','rm -rf "$1"; mkdir -p "$1"; shift; exec "$@"','compile',str(classes),'javac','--release','21','-cp',cp,'-d',str(classes),*map(str,java_sources)])
    compile_artifact(root,'composer-deal',[source,stage/'ui',*stage.glob('*.d.deal'),stage/'deal.json',deal/'std',cp,pathlib.Path(shutil.which('java')).resolve().parents[1]/'release'],[stage/'js'],
        ['bash','-euc','rm -rf "$1"; shift; exec "$@"','compile',str(stage/'js'),'java','-Ddeal.home='+str(deal),'-cp',cp,'deal.Main','compile',str(source/'checks.deal'),'--backend','js','--output',str(stage/'js')])
    if args.prepare_only:
        print('composer preparation only; assertions and contracts snapshot NOT produced',flush=True)
        raise SystemExit(0)
    for name in ['FragmentRegionsTest','FragmentQualifierTest','FragmentMechanismsTest']:
        run(['java','-cp',str(classes)+':'+cp,name],check=True)
    run(['node',str(root/'tests/tools/test-composer.js'),str(stage),str(classes)+':'+cp,args.group or 'all'],check=True)
    if args.group:
        # Receipts authorize only a frozen fixture snapshot, never skip assertions.
        group_record=stage/('group-'+args.group+'.json')
        owned={'validation':[], 'basics':['boolean','written'], 'clocks':['clock','clock-input'],
               'operations':['operation','context-operation','trigger','assembly','descriptor'],
               'entries':['entries','entries-empty','row-tap','entries-aggregate'],
               'presentation':['presentation','foreign','numeric-text']}[args.group]
        selected={k:v for k,v in outputs().items() if k.split('/')[0] in ['js','classes',*owned] or (args.group=='basics' and '/' not in k)}
        group_record.write_text(json.dumps({'input':input_digest(),'outputs':selected},sort_keys=True))
        records=[stage/('group-'+g+'.json') for g in GROUPS]
        if all(p.exists() for p in records):
            snapshots=[json.loads(p.read_text()) for p in records]
            current=outputs()
            if all(s['input']==input_digest() and all(current.get(k)==v for k,v in s['outputs'].items()) for s in snapshots):
                receipt.write_text(json.dumps({'input':input_digest(),'outputs':current},sort_keys=True))
        print('selected composer group passed; pair snapshot available: '+str(receipt.exists()),flush=True)
        raise SystemExit(0)
    receipt.write_text(json.dumps({'input':input_digest(),'outputs':outputs()},sort_keys=True))
    if args.contracts_only:
        print(f'composer contracts only: {time.monotonic()-started:.2f}s',flush=True)
        raise SystemExit(0)

# Each lane gets private input/output directories: compiling a pair never overwrites the
# numeric fixture consumed by persistence, nor the frozen snapshot used by later lanes.
pairs=stage.parent/'composer-pairs'
executable=stage.parent/'composed-executable/js'
def node(name, *arguments):
    run(['node',str(root/name if name.startswith('tests/') else root/'tests/tools'/name),*map(str,arguments)],check=True)
def compile_pair(name,target='screen'):
    source=stage if name in ('numeric','chat','card','absent') else stage/name
    directory=pairs/name/'stage'
    directory.mkdir(parents=True,exist_ok=True)
    for filename in ('experience.deal','experience.dealui','metadata.json'): shutil.copyfile(source/filename,directory/filename)
    output=directory.parent/'composed-executable/js'
    if name=='absent':
        # This early rejection cannot leave a manifest from a prior run.
        shutil.rmtree(output,ignore_errors=True)
        rejected=run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(directory),'',str(directory/'metadata.json'),'true'],capture_output=True,text=True)
        assert rejected.returncode!=0 and 'ADMISSION_TARGET' in rejected.stdout+rejected.stderr,rejected.stdout+rejected.stderr
        assert not (output/'admission.json').exists(),'a rejected compile input writes no manifest'
        return
    run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(directory),target,str(directory/'metadata.json'),'true'],check=True)
    manifest=json.loads((output/'admission.json').read_text())
    assert manifest['target']==target
    admission={'numeric','assembly','context-operation','entries-aggregate','row-tap','presentation','foreign','descriptor','trigger','clock'}
    if name in admission:
        node('test-admission.js',stage/'js',output/'admission.json',*(['trigger'] if name=='trigger' else []))
    runtime={'numeric':'tests/tools/test-composed-runtime.js','assembly':'tests/tools/test-assembly.js','context-operation':'test-issued-data.js','entries':'test-entries-part.js','entries-aggregate':'test-entry-aggregate.js','row-tap':'test-row-tap.js','numeric-text':'test-numeric-text.js','foreign':'test-foreign-binding.js','clock':'test-clock-part.js','clock-input':'test-clock-input.js'}
    if name in runtime: node(runtime[name],output)
    if name=='trigger': node('tests/tools/test-draft-revision.js',output)
    if name=='numeric':
        # Existing downstream persistence items consume this exact last numeric compile.
        shutil.rmtree(executable,ignore_errors=True)
        shutil.copytree(output,executable)
lanes=['absent','chat','card','boolean','written','assembly','operation','context-operation','entries','entries-empty','entries-aggregate','row-tap','presentation','numeric-text','foreign','descriptor','trigger','clock','clock-input','numeric']
for name in ([args.pair] if args.pair else lanes):
    compile_pair(name,'chat' if name in ('chat','assembly') else 'card' if name=='card' else 'screen')
print(f'composer lane complete: {time.monotonic()-started:.2f}s',flush=True)

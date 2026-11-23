#!/usr/bin/env python3
"""Real compiled DEAL composer with JVM syntax mechanisms; no model or alternate emitter."""
import json, os, pathlib, shutil, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[2]
deal=pathlib.Path(os.environ.get('DEAL_ROOT',root/'dependencies/deal')).resolve()
deal_ui=pathlib.Path(os.environ.get('DEAL_UI_ROOT',root/'dependencies/deal-ui')).resolve()
stage=root/'build/v08-cutover/composer-test';stage.mkdir(parents=True,exist_ok=True)
source=stage/'src';source.mkdir(exist_ok=True)
embedding=root
for p in (embedding/'core').glob('*.deal'):
    if p.name!='generation.deal':shutil.copy(p,source/p.name)
shutil.copy(root/'tests/tools/fixtures/composer-contracts.deal',source/'checks.deal')
shutil.copytree(deal_ui/'ui',stage/'ui',dirs_exist_ok=True)
externals={}
for p in (embedding/'core/host').glob('*.d.deal'):
    shutil.copy(p,stage/p.name);externals['embedding' if p.name=='embedding.d.deal' else 'embedding/'+p.name.removesuffix('.d.deal')]={'declaration':p.name}
(stage/'deal.json').write_text(json.dumps({'languageVersion':'1.6','backend':'js','moduleRoots':['src','ui'],'externals':externals}))
cp=str(root/'build/embedding-library/classes')
classes=stage/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','--release','21','-cp',cp,'-d',str(classes),*[str(embedding/'tools'/f'{name}.java') for name in ['FragmentSyntax','FragmentRegions','FragmentQualifier','FragmentMechanisms','FragmentAnalysis']],*[str(root/'tests/tools/fixtures'/f'{name}.java') for name in ['CompileComposer','FragmentRegionsTest','FragmentQualifierTest','FragmentMechanismsTest']]],check=True)
for name in ['FragmentRegionsTest','FragmentQualifierTest','FragmentMechanismsTest']:
    subprocess.run(['java','-cp',str(classes)+':'+cp,name],check=True)
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',cp,'deal.Main','compile',str(source/'checks.deal'),'--backend','js','--output',str(stage/'js')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-composer.js'),str(stage),str(classes)+':'+cp],check=True)
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(stage),'screen'],check=True)
# The frozen target is a compile input for every issued value: the same production composer path
# accepts chat and card as well as screen and writes the admission manifest beside the emitted pair.
for target in ('chat','card'):
    target_stage=pathlib.Path(tempfile.mkdtemp(prefix=f'composer-{target}-'))/'stage';target_stage.mkdir()
    for name in ('experience.deal','experience.dealui'):shutil.copy(stage/name,target_stage/name)
    subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(target_stage),target],check=True)
    manifest=json.loads((target_stage.parent/'composed-executable/js/admission.json').read_text())
    assert manifest['target']==target,target
# The frozen target is a compile input: without one the composer rejects with ADMISSION_TARGET
# before any manifest exists, and never supplies a default of its own.
absent=pathlib.Path(tempfile.mkdtemp(prefix='composer-absent-'))/'stage';absent.mkdir()
for name in ('experience.deal','experience.dealui'):shutil.copy(stage/name,absent/name)
rejected=subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(absent)],capture_output=True,text=True)
assert rejected.returncode!=0,'a compile input without a target must be rejected'
assert 'ADMISSION_TARGET' in (rejected.stdout+rejected.stderr),rejected.stdout+rejected.stderr
assert not (absent.parent/'composed-executable/js/admission.json').exists(),'a rejected compile input writes no manifest'
subprocess.run(['node',str(root/'tests/tools/test-composed-runtime.js'),str(stage.parent/'composed-executable/js')],check=True)

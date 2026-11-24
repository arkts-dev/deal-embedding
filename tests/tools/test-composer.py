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
# Each emitted pair compiles through the production compiler with the issued composition metadata the
# checking admission consumes; nothing is derived by scanning the source.
def compile_pair(directory,target='screen'):
    if directory.resolve()!=stage.resolve():
        for name in ('experience.deal','experience.dealui','metadata.json'):shutil.copyfile(directory/name,stage/name)
    subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(stage),target,str(stage/'metadata.json'),'true'],check=True)
(stage.parent/'composed-executable/js/admission.json').unlink(missing_ok=True)
compile_pair(stage,'screen')
assert json.loads((stage.parent/'composed-executable/js/admission.json').read_text())['target']=='screen','the screen compile-input target was not recorded'
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
# The frozen target is a compile input: without one the composer rejects with ADMISSION_TARGET
# before any manifest exists, and never supplies a default of its own.
absent=pathlib.Path(tempfile.mkdtemp(prefix='composer-absent-'))/'stage';absent.mkdir()
for name in ('experience.deal','experience.dealui'):shutil.copy(stage/name,absent/name)
rejected=subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(absent),'',str(stage/'metadata.json'),'true'],capture_output=True,text=True)
assert rejected.returncode!=0,'a compile input without a target must be rejected'
assert 'ADMISSION_TARGET' in (rejected.stdout+rejected.stderr),rejected.stdout+rejected.stderr
assert not (absent.parent/'composed-executable/js/admission.json').exists(),'a rejected compile input writes no manifest'
subprocess.run(['node',str(root/'tests/tools/test-composed-runtime.js'),str(stage.parent/'composed-executable/js')],check=True)
# The frozen target is a compile input for every issued value: the same production composer path
# accepts chat and card as well as screen and records the target it was given in the manifest it
# writes beside the compiled pair. The inputs are the ones the issued pair carries; only the frozen
# target value differs.
for target in ('chat','card'):
    target_stage=pathlib.Path(tempfile.mkdtemp(prefix=f'composer-{target}-'))/'stage';target_stage.mkdir()
    for name in ('experience.deal','experience.dealui','metadata.json'):shutil.copy(stage/name,target_stage/name)
    subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(classes)+':'+cp,'CompileComposer',str(root),str(target_stage),target,str(target_stage/'metadata.json'),'true'],check=True)
    manifest=json.loads((target_stage.parent/'composed-executable/js/admission.json').read_text())
    assert manifest['target']==target,target
# The yes_no figure assembles to emitted source that the production compiler and pack admit.
# The harness keeps only the last compile, so this reuses the one staged project, then restores the
# numeric fixture pair the vocabulary probes read from this same stage.
numeric={name:(stage/name).read_text() for name in ('experience.deal','experience.dealui','metadata.json')}
compile_pair(stage/'boolean','screen')
# A written formula lowered to the checked pow helper compiles through the production compiler.
compile_pair(stage/'written','screen')
# The assembly contract pair: ownership, slots, publications, one emitted operation with its
# revision field and currentness hook, composite emission, the chat root and an issued hole body.
compile_pair(stage/'assembly','chat')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-assembly.js'),str(stage.parent/'composed-executable/js')],check=True)
# The plan-action binding pair: one emitted operation backs the issued binding with its revision.
compile_pair(stage/'operation','screen')
# The issued-data binding pair: a plan constant, a disclosed fact, the frozen request and a part
# publication back their own emitted operations as sources emitted as data, and the compiled
# experience seeds those exact values.
compile_pair(stage/'context-operation','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-issued-data.js'),str(stage.parent/'composed-executable/js')],check=True)
compile_pair(stage/'entries','screen')
subprocess.run(['node',str(root/'tests/tools/test-entries-part.js'),str(stage.parent/'composed-executable/js')],check=True)
compile_pair(stage/'entries-empty','screen')
# The issued entry aggregates back a figure over an entries count and total: the aggregate operand reads
# the entries part's own emitted field, and the figure follows the records the user adds.
compile_pair(stage/'entries-aggregate','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-entry-aggregate.js'),str(stage.parent/'composed-executable/js')],check=True)
# The row-tap pair: the operation observes the captured row, so an unrelated row edit leaves its
# revision unchanged while an edit or removal of the tapped row invalidates it.
compile_pair(stage/'row-tap','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-row-tap.js'),str(stage.parent/'composed-executable/js')],check=True)
# The draft revision pair mounts the trigger composition: an equivalent draft spelling leaves the
# operation revision, while a changed validated value or an invalid draft advances it once.
compile_pair(stage/'trigger','screen')
subprocess.run(['node',str(root/'tests/tools/test-draft-revision.js'),str(stage.parent/'composed-executable/js')],check=True)
# The presentation pair: fixed decimals at the figure and entry-field sites, ClockText for
# time_of_day, and a data-only label escaped into the emitted view all compile through the shipped
# compiler and pack.
compile_pair(stage/'presentation','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
# The entry-field presentation pair: a seeded row text and the NumberText total over the same stored
# value apply the one issued numeric presentation rule, so one amount is never displayed two ways.
compile_pair(stage/'numeric-text','screen')
subprocess.run(['node',str(root/'tests/tools/test-numeric-text.js'),str(stage.parent/'composed-executable/js')],check=True)
# The foreign-binding pair: a view of one placed owner binds another owner's exported nominal action
# and its dispatch reaches that owner's reducer.
compile_pair(stage/'foreign','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-foreign-binding.js'),str(stage.parent/'composed-executable/js')],check=True)
# The descriptor pair: a seeded descriptor-local draft and its readiness are emitted authoritative state
# from the same registry the emitted operation consumes, and the pair compiles and admits through the
# shipped compiler like every other emitted artifact.
compile_pair(stage/'descriptor','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
# The trigger pair: a checked countdown_end/entry_added trigger names the emitted operation it must
# reach and carries its trigger resource in that operation's dependency set; the admission item states
# those facts from the pair itself and requires this pair to carry them.
compile_pair(stage/'trigger','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json'),'trigger'],check=True)
for name,text in numeric.items():
    (stage/name).write_text(text)
# The clock part assembles to emitted source that the production compiler and pack admit; its
# runtime proves monotone advance, once-only completion, restart and clamped remaining.
compile_pair(stage/'clock','screen')
subprocess.run(['node',str(root/'tests/tools/test-admission.js'),str(stage/'js'),str(stage.parent/'composed-executable/js/admission.json')],check=True)
subprocess.run(['node',str(root/'tests/tools/test-clock-part.js'),str(stage.parent/'composed-executable/js')],check=True)
compile_pair(stage/'clock-input','screen')
subprocess.run(['node',str(root/'tests/tools/test-clock-input.js'),str(stage.parent/'composed-executable/js')],check=True)
for name,text in numeric.items():
    (stage/name).write_text(text)
# The numeric fixture pair is the last compile input: the persistence items that follow this harness
# mount exactly this emitted pair, and its alias/cycle fields are the saved graph they restore.
compile_pair(stage,'screen')

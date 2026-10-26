#!/usr/bin/env python3
"""Generate typed host ABI inventories from production compiler emission, not copied schemas."""
import json,pathlib,re,subprocess,sys
root,out,compiler,deal,ui=map(pathlib.Path,sys.argv[1:])
stage=out/'protocol';(stage/'src').mkdir(parents=True,exist_ok=True)
import shutil
shutil.copytree(ui/'ui',stage/'src/ui',dirs_exist_ok=True)
for p in (root/'api').glob('*.d.deal'):shutil.copy(p,stage/'src'/p.name)
for p in (root/'core/host').glob('*.d.deal'):shutil.copy(p,stage/'src'/p.name)
modules={'embedding':'embedding.d.deal','catalogue-policy':'catalogue-policy.d.deal','embedding/protocol':'protocol.d.deal','embedding/policy-data':'policy-data.d.deal'}
(stage/'deal.json').write_text(json.dumps({'languageVersion':'1.4','backend':'js','moduleRoots':['src'],'externals':{k:{'declaration':'src/'+v} for k,v in modules.items()}}))
(stage/'src/main.deal').write_text('\n'.join('import * as m'+str(i)+' from "'+m+'";' for i,m in enumerate(modules))+'\nexport function main(): null { return null; }')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/main.deal'),'--backend','js','--output',str(stage/'js')],check=True)
text=(stage/'js/main.js').read_text()
entries=[]
for m in modules:
    pattern=r'\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), '+re.escape(json.dumps(m))+r', '
    match=re.search(pattern,text)
    if not match:raise RuntimeError('Missing compiler ABI inventory '+m)
    entries.append(json.dumps(m)+':'+match.group(1))
target=out/'aar/assets/embedding/bindings/protocol-contracts.js'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text('globalThis.protocolDeclarations={'+','.join(entries)+'};\n')
# Production application linkage is separate from the assertion-bearing test consumer.
shutil.copy(root/'tools/application-consumer.deal',stage/'src/application.deal')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/application.deal'),'--backend','js','--output',str(stage/'application')],check=True)
shutil.copytree(stage/'application',out/'aar/assets/protocol-application',dirs_exist_ok=True)
# A production-compiled consumer retained for host ABI tests, not a generated UI.
shutil.copy(root/'tools/protocol-consumer.deal',stage/'src/consumer.deal')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/consumer.deal'),'--backend','js','--output',str(stage/'consumer')],check=True)
shutil.copytree(stage/'consumer',out/'aar/assets/protocol-consumer',dirs_exist_ok=True)
shutil.copytree(stage/'js/ui',out/'aar/assets/generation/ui',dirs_exist_ok=True)
# Deliberately invalid catalogue source: exercises the real checker and template-stop rule.
(stage/'src/invalid-policy.deal').write_text('''import * as embedding from "embedding";
import * as policy from "catalogue-policy";
export async function offer(intent:string,context:embedding.GenerationContext,capabilities:embedding.CapabilityContract[]):policy.CatalogueOffer {
 let option:policy.ChoiceOption={name:"yes",description:"Select the invalid test source"};let options:policy.ChoiceOption[]=[option];
 let question:policy.ChoiceQuestion={name:"choose",instructions:"Select the test option",options:options};let questions:policy.ChoiceQuestion[]=[question];return {available:true,questions:questions,stateJson:""};
}
export async function lower(stateJson:string,answers:policy.ChoiceAnswer[]):policy.CatalogueLowering {let source:embedding.Source={deal:"invalid source",dealui:"invalid UI"};return {source:source,reason:""};}
export function main():null {return null;}
''')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/invalid-policy.deal'),'--backend','js','--output',str(stage/'invalid-policy')],check=True)
invalid=out/'aar/assets/protocol-invalid-policy';shutil.copytree(stage/'invalid-policy',invalid,dirs_exist_ok=True);(invalid/'invalid-policy.js').rename(invalid/'app-policy.js')

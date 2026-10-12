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
(stage/'deal.json').write_text(json.dumps({'languageVersion':'1.2','backend':'js','moduleRoots':['src'],'externals':{k:{'declaration':'src/'+v} for k,v in modules.items()}}))
(stage/'src/main.deal').write_text('\n'.join('import * as m'+str(i)+' from "'+m+'";' for i,m in enumerate(modules))+'\nexport function main(): null { return null; }')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/main.deal'),'--backend','js','--output',str(stage/'js')],check=True)
text=(stage/'js/main.js').read_text()
entries=[]
for m in modules:
    pattern=r'\$rt\.loadHost\(\$require\('+re.escape(json.dumps('./'+m))+r'\), (.*), '+re.escape(json.dumps(m))+r', '
    match=re.search(pattern,text)
    if not match:raise RuntimeError('Missing compiler ABI inventory '+m)
    entries.append(json.dumps(m)+':'+match.group(1))
target=out/'aar/assets/embedding/bindings/protocol-contracts.js'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text('globalThis.protocolDeclarations={'+','.join(entries)+'};\n')
# A production-compiled consumer retained for host ABI tests, not a generated UI.
(stage/'src/consumer.deal').write_text('''import * as embedding from "embedding";
export async function load(title:string, deal:string, dealui:string): embedding.Experience { return await embedding.load(title,{deal:deal,dealui:dealui}); }
export async function run(id:string): embedding.Session { return await embedding.run({id:id}); }
export async function poll(id:string): embedding.UiSnapshot { return await embedding.poll({id:id}); }
export async function close(id:string): null { return await embedding.close({id:id}); }
export async function forget(id:string): null { return await embedding.forget({id:id}); }
export async function events(): embedding.EventPage { return await embedding.events("","",20); }
export async function artifact(id:string): embedding.Artifact { return await embedding.artifact(id); }
export function saved(): embedding.Experience[] { return embedding.saved(); }
export function main():null {return null;}
''')
subprocess.run(['java','-Ddeal.home='+str(deal),'-cp',str(compiler),'deal.Main','compile',str(stage/'src/consumer.deal'),'--backend','js','--output',str(stage/'consumer')],check=True)
shutil.copytree(stage/'consumer',out/'aar/assets/protocol-consumer',dirs_exist_ok=True)
shutil.copytree(stage/'js/ui',out/'aar/assets/generation/ui',dirs_exist_ok=True)

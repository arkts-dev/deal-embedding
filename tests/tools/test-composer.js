#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert'),cp=require('child_process');
const stage=path.resolve(process.argv[2]),base=path.join(stage,'js'),classpath=process.argv[3],sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const declarations={};for(const source of Object.values(sources))for(const line of source.split('\n')){const match=line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^\"]*)", /);if(match)declarations[match[2]]=vm.runInNewContext('('+match[1]+')');}
const context=vm.createContext({sources,protocolDeclarations:declarations});
for(const name of ['sandbox.js','protocol-host.js'])vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings',name),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));const {entry}=dealLoad(factories,'checks.js');let finished=false,failure=null,result=null;entry.rejects.$f().then(()=>entry.construct.$f()).then(value=>{result=value;finished=true;}).catch(error=>{failure={code:error.code,message:error.message};finished=true;});`,context);
(async()=>{for(let turn=0;turn<1000;turn++){
 await new Promise(resolve=>setImmediate(resolve));
 for(const request of JSON.parse(vm.runInContext('dealCapabilities.take()',context))){
  assert.equal(request.module,'embedding/fragment-syntax');
  const output=cp.spawnSync('java',['-cp',classpath,'FragmentMechanisms',request.function],{input:request.args[0],encoding:'utf8'});
  if(output.status!==0)throw Error(output.stderr);
  context.reply={id:request.id,ok:true,value:output.stdout};vm.runInContext('dealCapabilities.deliver([reply])',context);
 }
 if(vm.runInContext('finished',context)){
  assert.equal(vm.runInContext('failure',context),null);const result=JSON.parse(vm.runInContext('result',context));
  fs.writeFileSync(path.join(stage,'experience.deal'),result.source.deal);fs.writeFileSync(path.join(stage,'experience.dealui'),result.source.dealui);
  assert(result.source.deal.includes('action.value'));assert(!result.source.deal.includes('action.f_calculation_value'));assert(result.source.deal.includes('f_calculation_double_ready: state.f_calculation_double_ready'));
  console.log('compiled composer: AST qualification, owner reducer wrappers and derived recomputation emitted');return;
 }
}throw Error('Composer did not settle');})().catch(error=>{console.error(error);process.exitCode=1;});

#!/usr/bin/env node
'use strict';
// Mechanism-only memoization: exact keys, bounded retention, classified failures,
// fresh module lifetime, and no caching of mutable/authority-bearing host calls.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('android/assets/embedding/bindings/protocol-host.js','utf8');
const context=vm.createContext({});vm.runInContext(source,context);
let calls=0,fail=false;
context.dealCapabilities={request:async(id,name,args)=>{calls++;if(fail)throw {code:'PERMISSION_DENIED',message:'denied',suppressed:['cleanup']};return name==='batch'?JSON.stringify([{value:args[0],code:args[0]==='bad'?'FRAGMENT_SYNTAX':'',message:''}]):args[0];}};
const rt={errorValue:(code,message)=>Object.assign(new Error(message),{code})};
const declarations={describe:{$k:'function',$d:'async(string)->string'},batch:{$k:'function',$d:'async(string)->string'}};
const open=()=>context.protocolHost('embedding/fragment-syntax',declarations,rt);
(async()=>{
 const first=open();assert.equal(await first.describe('😀'), '😀');assert.equal(await first.describe('😀'),'😀');assert.equal(calls,1);
 await first.describe('x');assert.equal(calls,2);await first.batch('x');assert.equal(calls,3);await first.batch('x');assert.equal(calls,3);
 await first.batch('bad');await first.batch('bad');assert.equal(calls,5,'per-item failures are not cached');
 fail=true;for(let i=0;i<2;i++)await assert.rejects(first.describe('denied'),e=>e.code==='PERMISSION_DENIED'&&e.message==='denied'&&e.suppressed[0]==='cleanup');assert.equal(calls,7);fail=false;
 await open().describe('😀');assert.equal(calls,8,'a new module has no retained values');
 const huge='a'.repeat(524289);await first.describe(huge);await first.describe(huge);assert.equal(calls,10,'oversized inputs are executed without retention');
 const other=context.protocolHost('embedding/inference',declarations,rt);await other.describe('x');await other.describe('x');assert.equal(calls,12,'other hosts are never memoized');
 console.log('syntax memo: exact keys, classified uncached failures, bounds and isolate-local lifetime passed');
})().catch(error=>{console.error(error);process.exitCode=1;});

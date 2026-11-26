'use strict';
// Supply the dependencies the upstream synthetic-host runner does not load. Its original lifecycle
// assertions run unmodified, using embedding's real admission verdict and issued pack projections.
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../..');
Object.defineProperty(globalThis,'rendererContract',{
  value:require('./renderer-contract.js')('screen'),writable:false,configurable:false
});
vm.runInThisContext(fs.readFileSync(path.join(root,'android/assets/embedding/bindings/renderer-admission.js'),'utf8'));

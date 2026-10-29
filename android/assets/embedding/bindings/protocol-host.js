"use strict";
// Native transport for the checked public declarations. No domain policy.
globalThis.protocolHost = function(id, declarations, rt) {
  function record(descriptor, value) {
    if (value === null || descriptor[0] === '?' && value === null) return null;
    if (descriptor[0] === '?') return record(descriptor.slice(1),value);
    if (descriptor[0] === '[') return value.map(v=>record(descriptor.slice(1,-1),v));
    if(descriptor==='table') {
      function table(v){if(Array.isArray(v))return v.map(table);if(v&&typeof v==='object')return rt.makeTable(Object.fromEntries(Object.entries(v).map(([k,x])=>[k,table(x)])));return v;}
      return table(value);
    }
    if (descriptor[0] !== '@') return value;
    if(descriptor.includes('/ui/')) {
      const name=descriptor.split('/').pop();
      const fields=Object.fromEntries(Object.entries(value).filter(([key])=>!key.startsWith('$')).map(([key,v])=>[key,key==='children'?v.map(n=>record(descriptor,n)):key==='props'?v.map(p=>record(descriptor.replace('/ViewNode','/Prop'),p)):key==='actionBindings'?record('table',v):v]));
      try { return protocolUi[name+'$new'](fields); }
      catch(error) { throw rt.errorValue(error.code||'BAD_RESPONSE','Invalid '+name+' transport: '+(error.message||'construction failed')); }
    }
    const spec = Object.values(protocolDeclarations).flatMap(m=>Object.values(m)).find(v=>v.$k==='class'&&v.$d===descriptor);
    if (!spec) throw Error('Unknown protocol record '+descriptor);
    const fields={};for(const field of spec.$fields)fields[field.name]=record(field.$d,value[field.name]);
    try { return rt.classPlan(descriptor,spec.$fields.map(f=>({name:f.name,descriptor:f.$d,optional:f.optional})),fields); }
    catch(error) { throw rt.errorValue(error.code||'BAD_RESPONSE','Invalid '+descriptor.split('/').pop()+' transport: '+(error.message||'construction failed')); }
  }
  globalThis.protocolRecord=(descriptor,value)=>record(descriptor,value);
  function plain(value) {
    if(value instanceof Map)return Object.fromEntries([...value].map(([k,v])=>[k,plain(v)]));
    if(Array.isArray(value))return value.map(plain);
    if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([k])=>!k.startsWith('$')).map(([k,v])=>[k,plain(v)]));
    return value;
  }
  globalThis.protocolPlain=plain;
  const raw={};
  for(const [name,spec]of Object.entries(declarations)) {
    if(spec.$k==='class') {
      raw[name]={$kind:'class',$classname:spec.$d};
    } else if(id==='embedding' && typeof publicProtocol !== 'undefined')raw[name]=(...args)=>publicProtocol.invoke(name,args.map(plain),value=>record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),value));
    else if(id==='catalogue-policy') raw[name]=async(...args)=>globalThis.loadedAppPolicy[name].$f(...args);
    else if(id==='embedding' && name==='contextJson')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return protocolContexts[context.id].value;};
    else if(id==='embedding' && name==='contextSchema')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return record('@$external/embedding/WireType',protocolContexts[context.id].schema);};
    else raw[name]=async(...args)=>{try{return record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),await dealCapabilities.request(id,name,args.map(plain)));}catch(error){const translated=rt.errorValue(error.code||'HOST_FAILED',error.message||'Host operation failed');translated.suppressed=error.suppressed||[];throw translated;}};
  }
  return raw;
};
globalThis.protocolContexts=Object.create(null);

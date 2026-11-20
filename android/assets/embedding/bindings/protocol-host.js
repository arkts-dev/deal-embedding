"use strict";
// Native transport for the checked public declarations. No domain policy.
globalThis.protocolHost = function(id, declarations, rt) {
  function record(descriptor, value) {
    if (value === null || descriptor[0] === '?' && value === null) return null;
    if (descriptor[0] === '?') return record(descriptor.slice(1),value);
    if (descriptor[0] === '[') return value.map(v=>record(descriptor.slice(1,-1),v));
    if(descriptor==='record') {
      function table(v){if(Array.isArray(v))return v.map(table);if(v&&typeof v==='object')return rt.makeTable(Object.fromEntries(Object.entries(v).map(([k,x])=>[k,table(x)])));return v;}
      return table(value);
    }
    if (descriptor[0] !== '@') return value;
    if(descriptor.includes('/ui/')) {
      const name=descriptor.split('/').pop();
      const fields=Object.fromEntries(Object.entries(value).filter(([key])=>!key.startsWith('$')).map(([key,v])=>[key,key==='children'?v.map(n=>record(descriptor,n)):key==='props'?v.map(p=>record(descriptor.replace('/ViewNode','/Prop'),p)):key==='actionBindings'?record('record',v):v]));
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
  function checkedContext(schema, text, maximumBytes, maximumDepth, maximumFields, maximumLength) {
    const fail=(code,message)=>{throw rt.errorValue(code,message);};
    const need=(condition,message)=>{if(!condition)fail('INVALID_ARGUMENT',message);};
    const validText=value=>{let bytes=0;for(const ch of value){const cp=ch.codePointAt(0);need(cp<0xd800||cp>0xdfff,'Invalid Unicode encoding');bytes+=cp<0x80?1:cp<0x800?2:cp<0x10000?3:4;}return bytes;};
    need(validText(text)<=maximumBytes,'Context UTF-8 byte bound');
    const ancestors=new Set();
    function checkSchema(t,depth){
      need(depth<=maximumDepth&&!ancestors.has(t),'Schema depth/cycle');ancestors.add(t);
      need(['number','string','boolean','null','record','array'].includes(t.kind),'Wire kind');
      need(Number.isInteger(t.maximum)&&t.maximum>=0&&t.maximum<=maximumLength&&Number.isFinite(t.minimumNumber)&&Number.isFinite(t.maximumNumber)&&t.minimumNumber<=t.maximumNumber,'Wire bounds');
      need(Array.isArray(t.fields)&&t.fields.length<=maximumFields&&(t.kind==='record'||t.fields.length===0)&&(t.kind==='array')===(t.element!==null),'Wire metadata');
      const names=new Set();for(const f of t.fields){need(/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(f.name)&&!names.has(f.name)&&typeof f.optional==='boolean','Wire field');names.add(f.name);checkSchema(f.type,depth+1);}
      if(t.element!==null)checkSchema(t.element,depth+1);ancestors.delete(t);
    }
    function checkValue(t,v){
      if(t.kind==='number')need(typeof v==='number'&&Number.isFinite(v)&&v>=t.minimumNumber&&v<=t.maximumNumber,'Numeric value');
      else if(t.kind==='string'){need(typeof v==='string'&&v.length<=t.maximum,'String value');validText(v);}
      else if(t.kind==='boolean')need(typeof v==='boolean','Boolean value');
      else if(t.kind==='null')need(v===null,'Null value');
      else if(t.kind==='array'){need(Array.isArray(v)&&v.length<=t.maximum,'Array value');for(const item of v)checkValue(t.element,item);}
      else {need(v!==null&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).every(k=>t.fields.some(f=>f.name===k)),'Closed record');for(const f of t.fields){if(Object.hasOwn(v,f.name))checkValue(f.type,v[f.name]);else need(f.optional,'Required field');}}
    }
    const plainSchema=plain(schema);checkSchema(plainSchema,0);
    let value;try{value=rt.jsonParseText(text);}catch(error){fail('INVALID_JSON','Context JSON: '+error.message);}
    checkValue(plainSchema,value);
    return record('@$external/embedding/controller-mechanisms/ContextValue',{schema:plainSchema,json:JSON.stringify(value)});
  }
  const raw={};
  // Syntax is a pure input-string -> output-string mechanism. This module-load
  // cache belongs to one isolate; it never retains Session state or authority.
  const syntaxValues=new Map();let syntaxUnits=0;
  for(const [name,spec]of Object.entries(declarations)) {
    if(spec.$k==='class') {
      raw[name]={$kind:'class',$classname:spec.$d};
    } else if(id==='embedding/fragment-syntax') {
      raw[name]=async input=>{
        const key=name+'\n'+input;
        if(syntaxValues.has(key))return syntaxValues.get(key);
        try {
          const value=await dealCapabilities.request(id,name,[input]);
          // Failed operations are never cached, including per-item batch errors.
          const success=name!=='batch'||JSON.parse(value).every(item=>item.code==='');
          if(success&&syntaxUnits+key.length+value.length<=524288){syntaxValues.set(key,value);syntaxUnits+=key.length+value.length;}
          return value;
        }catch(error){const translated=rt.errorValue(error.code||'HOST_FAILED',error.message||'Host operation failed');translated.suppressed=error.suppressed||[];throw translated;}
      };
    } else if(id==='embedding/controller-mechanisms') {
      if(name==='context')raw[name]=checkedContext;
      else raw[name]=async(...args)=>{try{return record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),await dealCapabilities.request('embedding',name,args.map(plain)));}catch(error){const translated=rt.errorValue(error.code||'HOST_FAILED',error.message||'Host operation failed');translated.suppressed=error.suppressed||[];throw translated;}};
    } else if(id==='embedding' && typeof publicProtocol !== 'undefined')raw[name]=(...args)=>publicProtocol.invoke(name,args.map(plain),value=>record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),value));
    else if(id==='embedding' && name==='contextJson')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return protocolContexts[context.id].value;};
    else if(id==='embedding' && name==='contextSchema')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return record('@$external/embedding/WireType',protocolContexts[context.id].schema);};
    else raw[name]=async(...args)=>{try{return record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),await dealCapabilities.request(id,name,args.map(plain)));}catch(error){const translated=rt.errorValue(error.code||'HOST_FAILED',error.message||'Host operation failed');translated.suppressed=error.suppressed||[];throw translated;}};
  }
  return raw;
};
globalThis.protocolContexts=Object.create(null);

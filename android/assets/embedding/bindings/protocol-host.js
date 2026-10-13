"use strict";
// Native transport for the checked public declarations. No domain policy.
globalThis.protocolHost = function(id, declarations, rt) {
  const defaults = {
    WireField:{name:"",type:null,optional:false}, WireType:{kind:"",fields:[],element:null,maximum:128,minimumInt:-2147483648,maximumInt:2147483647},
    Parameter:{name:"",type:null},CapabilityFunction:{name:"",description:"",parameters:[],result:null},CapabilityContract:{module:"",description:"",version:1,functions:[]},
    Source:{deal:"",dealui:""},GenerationContext:{id:""},GenerationRequest:{id:""},Experience:{id:"",title:"",origin:"",attempts:0},Session:{id:""},
    SessionUpdate:{snapshot:null,cursor:""},UiSnapshot:{tree:null,version:0,fault:""},ChoiceOption:{name:"",description:""},ChoiceQuestion:{name:"",instructions:"",options:[]},
    OptionProbability:{option:"",probability:0},ChoiceAnswer:{question:"",choice:"",probabilities:[],confidence:null},
    CatalogueOffer:{available:false,questions:[],stateJson:""},CatalogueLowering:{source:null,reason:""},Decision:{accepted:false,answers:[],reason:""},
    EventPage:{events:[],cursor:"",incomplete:false},Artifact:{status:"",text:""},ObservationHealth:{incomplete:false,active:false,code:"",lost:0}
  };
  function record(descriptor, value) {
    if (value === null || descriptor[0] === '?' && value === null) return null;
    if (descriptor[0] === '?') return record(descriptor.slice(1),value);
    if (descriptor[0] === '[') return value.map(v=>record(descriptor.slice(1,-1),v));
    if(descriptor==='table') {
      function table(v){if(Array.isArray(v))return v.map(table);if(v&&typeof v==='object')return rt.makeTable(Object.fromEntries(Object.entries(v).map(([k,x])=>[k,table(x)])));return v;}
      return table(value);
    }
    if (descriptor[0] !== '@') return value;
    if(descriptor.includes('/ui/'))return protocolUi[descriptor.split('/').pop()+'$new'](Object.fromEntries(Object.entries(value).filter(([key])=>!key.startsWith('$')).map(([key,v])=>[key,key==='children'?v.map(n=>record(descriptor,n)):key==='props'?v.map(p=>record(descriptor.replace('/ViewNode','/Prop'),p)):v])));
    const spec = Object.values(protocolDeclarations).flatMap(m=>Object.values(m)).find(v=>v.$k==='class'&&v.$d===descriptor);
    if (!spec) throw Error('Unknown protocol record '+descriptor);
    const fields={};for(const field of spec.$fields)fields[field.name]=record(field.$d,value[field.name]);
    return rt.makeClass(descriptor,descriptor,()=>fields,{});
  }
  globalThis.protocolRecord=(descriptor,value)=>record(descriptor,value);
  function plain(value) {
    if(value instanceof Map)return Object.fromEntries([...value].map(([k,v])=>[k,plain(v)]));
    if(Array.isArray(value))return value.map(plain);
    if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([k])=>!k.startsWith('$')).map(([k,v])=>[k,plain(v)]));
    return value;
  }
  const raw={};
  for(const [name,spec]of Object.entries(declarations)) {
    if(spec.$k==='class') {
      raw[name]={$kind:'class',$classname:spec.$d};
      raw[name+'$defaults']=()=>{
        const values=defaults[name] || (name==='Event'?Object.fromEntries(spec.$fields.map(f=>[f.name,f.name==='version'?-1:f.$d==='int'?0:''])):null);if(!values)throw Error('Missing protocol defaults '+name);
        return Object.fromEntries(Object.entries(values).map(([key,value])=>[key,Array.isArray(value)?[]:value===null&&['type','result'].includes(key)?record('@$external/embedding/WireType',defaults.WireType):key==='tree'?protocolUi['ViewNode$new']({}):key==='snapshot'?record('@$external/embedding/UiSnapshot',{tree:{},version:0,fault:''}):value]));
      };
    } else if(id==='embedding' && typeof publicProtocol !== 'undefined')raw[name]=(...args)=>publicProtocol.invoke(name,args.map(plain),value=>record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),value));
    else if(id==='catalogue-policy') raw[name]=async(...args)=>globalThis.loadedAppPolicy[name].$f(...args);
    else if(id==='embedding' && name==='contextJson')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return protocolContexts[context.id].value;};
    else if(id==='embedding' && name==='contextSchema')raw[name]=context=>{if(!Object.hasOwn(protocolContexts,context.id))throw rt.errorValue('INVALID_HANDLE','Invalid context');return record('@$external/embedding/WireType',protocolContexts[context.id].schema);};
    else raw[name]=async(...args)=>{try{return record(spec.$d.slice(spec.$d.lastIndexOf('->')+2),await dealCapabilities.request(id,name,args.map(plain)));}catch(error){const translated=rt.errorValue(error.code||'HOST_FAILED',error.message||'Host operation failed');translated.suppressed=error.suppressed||[];throw translated;}};
  }
  return raw;
};
globalThis.protocolContexts=Object.create(null);

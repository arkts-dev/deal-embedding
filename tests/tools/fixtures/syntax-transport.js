'use strict';
// One long-lived syntax-mechanism process for a harness that issues thousands of calls. It is the same
// one-invocation entry as a fresh JVM per call - one request per line, no result retained between calls -
// so opening the transport costs one JVM start and each call costs one round trip. Results, ordering and
// failures are the mechanism's own: the transport adds framing and nothing else.
const cp=require('child_process');
module.exports=function openTransport(classpath,className){
 const child=cp.spawn('java',['-cp',classpath,className||'FragmentMechanisms','serve'],{stdio:['pipe','pipe','inherit']});
 let text='',waiter=null,closed=false,exitCode=null;
 const cleanup=()=>{if(child.exitCode===null&&child.signalCode===null)child.kill('SIGKILL');};
 process.once('exit',cleanup);
 child.once('close',()=>process.removeListener('exit',cleanup));
 // A terminal stream or process event fails the call in flight and refuses every later one, so a broken
 // transport reports a failure instead of waiting for a reply that cannot arrive.
 const stop=error=>{
  closed=true;
  if(!waiter)return;
  const settle=waiter;waiter=null;settle.reject(error);
 };
 child.stdout.setEncoding('utf8');
 child.stdout.on('error',stop);
 child.stdin.on('error',stop);
 child.on('error',stop);
 child.on('exit',code=>{exitCode=code;});
 // `close` follows the last reply byte, so a reply written just before the process ended still counts.
 child.on('close',code=>stop(Error('Syntax transport closed before replying (exit '+code+')')));
 child.stdout.on('data',chunk=>{
  text+=chunk;
  for(;;){
   const end=text.indexOf('\n');
   if(end<0)return;
   const line=text.slice(0,end);text=text.slice(end+1);
   if(!waiter)return stop(Error('Unsolicited syntax transport reply: '+line));
   const settle=waiter;waiter=null;
   let reply;
   try{reply=JSON.parse(line);}catch(error){settle.reject(Error('Malformed syntax transport reply: '+line));continue;}
   if(reply&&reply.ok===true&&typeof reply.value==='string')settle.resolve(reply.value);
   else {
    const error=Error(reply&&typeof reply.error==='string'?reply.error:'Syntax transport reply without a value');
    error.syntaxDiagnostic=!!(reply&&/^(IllegalArgumentException|UiDiagnostic):/.test(reply.error));
    settle.reject(error);
   }
  }
 });
 return {
  invoke(functionName,input){
   return new Promise((resolve,reject)=>{
    if(closed)return reject(Error('Syntax transport closed (exit '+exitCode+')'));
    if(waiter)return reject(Error('Syntax transport admits one call at a time'));
    const timer=setTimeout(()=>{const error=Error('Syntax transport call timed out: '+functionName);stop(error);child.kill('SIGKILL');},15000);
    waiter={resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}};
    try{child.stdin.write(JSON.stringify({function:functionName,input})+'\n');}
    catch(error){const settle=waiter;waiter=null;settle.reject(error);}
   });
  },
  // Ends the request stream and answers with the exit status the old one-process-per-call entry asserted.
  close(){
   // `exit` may already have fired (even by a signal, where its code is null), so the child's own
   // status fields decide whether waiting for another exit event could still settle this promise.
   if(closed||child.exitCode!==null||child.signalCode!==null)return Promise.resolve(child.exitCode);
   return new Promise(resolve=>{const timer=setTimeout(()=>child.kill('SIGKILL'),3000);child.once('exit',code=>{clearTimeout(timer);exitCode=code;resolve(code);});child.stdin.end();});
  },
 };
};

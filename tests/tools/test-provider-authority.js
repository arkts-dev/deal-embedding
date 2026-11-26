#!/usr/bin/env node
'use strict';
// Provider authority and operation currentness in the running Session. The generated Experience the
// fixture assembled performs the only provider call; the host binding supplies the Session facts and
// every assertion below reads the state the compiled admission committed.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert'), cp = require('child_process'), crypto = require('crypto');
const stage = path.resolve(process.argv[2]), base = path.join(stage, 'js'), classpath = process.argv[3], root = process.cwd();
const transport = require('./fixtures/syntax-transport.js')(classpath);
const sources = {};
(function walk(dir) { for (const name of fs.readdirSync(dir)) { const file = path.join(dir, name); if (fs.statSync(file).isDirectory()) walk(file); else if (name.endsWith('.js')) sources[path.relative(base, file)] = fs.readFileSync(file, 'utf8'); } })(base);
const declarations = {};
for (const source of Object.values(sources)) for (const line of source.split('\n')) { const match = line.match(/\$rt\.loadHost\(\$[A-Za-z0-9]+, (.*), "(embedding[^"]*)", /); if (match) declarations[match[2]] = vm.runInNewContext('(' + match[1] + ')'); }
const context = vm.createContext({ sources, protocolDeclarations: declarations });
for (const name of ['sandbox.js', 'protocol-host.js']) vm.runInContext(fs.readFileSync(path.resolve('android/assets/embedding/bindings', name), 'utf8'), context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
  globalThis.steps={finished:true,failure:null,value:null};
  globalThis.startStep=(name)=>{globalThis.steps={finished:false,failure:null,value:null};
   dealLoad(factories,'checks.js').entry[name].$f()
    .then(value=>{steps.value=value;steps.finished=true;})
    .catch(error=>{steps.failure={name:error.name,message:error.message,code:error.code};steps.finished=true;});};`, context);
const tick = () => new Promise(resolve => setImmediate(resolve));
async function step(name) {
  vm.runInContext('startStep(' + JSON.stringify(name) + ')', context);
  for (let turn = 0; turn < 20000; turn++) {
    await tick();
    for (const request of JSON.parse(vm.runInContext('dealCapabilities.take()', context))) {
      let reply = { id: request.id, ok: true, value: null };
      if (request.module === 'embedding/fragment-syntax') {
        let output;
        try { output = { status: 0, stdout: await transport.invoke(request.function, request.args[0]) }; }
        catch (error) { if (!error.syntaxDiagnostic) throw error; output = { status: 1, stderr: String(error) }; }
        if (output.status !== 0) throw Error('fragment mechanism failed: ' + output.stderr);
        reply.value = output.stdout;
      } else if (request.module === 'embedding/construction-identity') reply.value = 'issued-' + request.id;
      else if (request.module === 'embedding/policy-data' && request.function === 'sha256') reply.value = crypto.createHash('sha256').update(request.args[0]).digest('hex');
      else throw Error('unissued assembly mechanism reached: ' + request.module + '/' + request.function);
      context.reply = reply;
      vm.runInContext('dealCapabilities.deliver([reply])', context);
    }
    if (vm.runInContext('steps.finished', context)) {
      const finished = vm.runInContext('steps', context);
      assert.equal(finished.failure, null, 'step ' + name + ': ' + (finished.failure && (finished.failure.name + ': ' + finished.failure.message)));
      return finished.value;
    }
  }
  throw Error('provider authority item did not settle: ' + name);
}
const numberWire = { kind: 'number', fields: [], element: null, maximum: 0, minimumNumber: -1000000, maximumNumber: 1000000 };
const probeContract = { module: 'host/probe', description: 'Authorized rate probe', version: 1,
  functions: [{ name: 'read', description: 'Read the current authorized rate', parameters: [{ name: 'value', type: numberWire }], result: numberWire, classification: 'read' }] };
const target = path.join(root, 'build/v08-cutover/provider-authority');
function compile(pair) {
  fs.rmSync(target, { recursive: true, force: true });
  fs.mkdirSync(path.join(target, 'pair'), { recursive: true });
  fs.writeFileSync(path.join(target, 'pair', 'experience.deal'), pair.source.deal);
  fs.writeFileSync(path.join(target, 'pair', 'experience.dealui'), pair.source.dealui);
  fs.writeFileSync(path.join(target, 'pair', 'metadata.json'), JSON.stringify(pair.metadata));
  // The production compiler registers each frozen contract's declaration from the compile input; the
  // emitted provider import and the Session facts module compile exactly as they do in production.
  fs.writeFileSync(path.join(target, 'pair', 'providers.txt'), 'host/probe=host-probe.d.deal\n');
  fs.copyFileSync(path.join(stage, 'host-probe.d.deal'), path.join(target, 'pair', 'host-probe.d.deal'));
  const compiled = cp.spawnSync('java', ['-Ddeal.home=dependencies/deal', '-cp', classpath, 'CompileComposer', root, path.join(target, 'pair'), 'screen', path.join(target, 'pair', 'metadata.json'), 'false'], { encoding: 'utf8', timeout: 240000 });
  if (compiled.error) throw Error('provider pair compile failed: ' + compiled.error.message);
  assert.equal(compiled.status, 0, compiled.stdout + compiled.stderr);
  return path.join(target, 'composed-executable', 'js');
}
function load(emitted) {
  const files = {};
  (function walk(dir) { for (const name of fs.readdirSync(dir)) { const file = path.join(dir, name); if (fs.statSync(file).isDirectory()) walk(file); else if (name.endsWith('.js')) files[path.relative(emitted, file)] = fs.readFileSync(file, 'utf8'); } })(emitted);
  const runtime = vm.createContext({ sources: files });
  for (const file of ['android/assets/embedding/bindings/sandbox.js', path.join(process.env.DEAL_UI_ROOT || 'dependencies/deal-ui', 'runtime-js/checkpoint.js'), path.join(process.env.DEAL_UI_ROOT || 'dependencies/deal-ui', 'runtime-js/session.js')])
    vm.runInContext(fs.readFileSync(file, 'utf8'), runtime);
  vm.runInContext('configureDealCapabilities(' + JSON.stringify([probeContract]) + ')', runtime);
  return runtime;
}
// One live Session: the packaged Deal UI store behind the host Session-facts binding. Every scenario
// opens a fresh Session so an earlier grant, epoch or clock never leaks into the next admission.
function open(emitted, facts) {
  const runtime = load(emitted);
  runtime.facts = facts;
  vm.runInContext(`configureDealSession(${JSON.stringify(facts)});
   const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));
   const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');
   const scheduleEffect=(invoke,complete,fail,exited)=>{Promise.resolve().then(invoke).then(complete).catch(fail).finally(exited);};
   globalThis.session=createDealUiSession(entry,rt,null,scheduleEffect,checkpointSchema);`, runtime);
  const state = () => JSON.parse(vm.runInContext('session.state()', runtime));
  const tree = () => JSON.parse(vm.runInContext('session.snapshot()', runtime)).tree;
  function props(node) { return Object.fromEntries(node.props.map(prop => [prop.name, prop])); }
  function find(node, match) { const found = match(node); if (found !== undefined) return found; for (const child of node.children || []) { const nested = find(child, match); if (nested !== undefined) return nested; } return undefined; }
  function loadButton() { return find(tree(), node => { if (node.component.split('.').pop() !== 'Button') return undefined; const all = props(node); return all.text !== undefined && all.text.stringValue === 'Load Rate' ? { slot: all.onClick.actionSlot, value: all.value.numberValue } : undefined; }); }
  const requests = () => JSON.parse(vm.runInContext('dealCapabilities.take()', runtime));
  const deliver = reply => { runtime.reply = reply; vm.runInContext('dealCapabilities.deliver([reply])', runtime); };
  const configure = config => { runtime.facts = config; runtime.config = config; vm.runInContext('configureDealSession(config)', runtime); };
  return { state, tree, loadButton, requests, deliver, configure, runtime };
}
async function dispatch(app, payload = null) {
  const button = app.loadButton();
  assert.ok(button && Number.isInteger(button.slot), 'the emitted pair binds its Load button');
  app.runtime.payload = payload;
  vm.runInContext('session.dispatch(' + button.slot + ',' + JSON.stringify(payload === null ? String(button.value) : payload) + ')', app.runtime);
}
/** Settle the provider request this dispatch issued, after an optional intervention on live facts. */
async function settle(app, value) {
  const started = Date.now();
  for (;;) {
    await tick();
    const pending = app.requests();
    if (pending.length > 0) { for (const request of pending) app.deliver({ id: request.id, ok: true, value: value }); break; }
    if (Date.now() - started > 5000) throw Error('the admitted read issued no provider request');
  }
  for (let turn = 0; turn < 200; turn++) await tick();
  return app.state();
}
const grantedFacts = extra => Object.assign({ lifetime: 'session-1', epochs: { 'host/probe': 4 }, granted: ['host/probe'], classification: { 'host/probe/read': 'read' }, confirmation: {}, clockTrusted: true, now: 1000, expiry: {} }, extra || {});
const scalar = name => 'f_parts_scalar_0_' + name;

(async () => {
  const pair = JSON.parse(await step('emit'));
  const emitted = compile(pair);

  // A published window is host configuration: one that names both bounds, neither bound or a
  // non-numeric bound is refused here instead of being read as the absence of a window.
  const malformedRuntime = load(emitted);
  const malformedWindow = spec => "configureDealSession({lifetime:'session-1',epochs:{'host/probe':4},granted:['host/probe'],classification:{'host/probe/read':'read'},confirmation:{},clockTrusted:true,now:1000,expiry:{'host/probe/read':" + spec + "}})";
  for (const spec of ['{relativeMs:500,absolute:1500}', '{}', '{absoltue:5}'])
    assert.throws(() => vm.runInContext(malformedWindow(spec), malformedRuntime), /exactly one of relativeMs or absolute/, 'a malformed window ' + spec + ' was accepted');
  for (const spec of ['{relativeMs:"soon"}', '{absolute:-2}'])
    assert.throws(() => vm.runInContext(malformedWindow(spec), malformedRuntime), /must be/, 'a malformed bound ' + spec + ' was accepted');
  // A container of another shape would publish no window for any function, so it is refused too.
  for (const container of ['[{relativeMs:500}]', '5', "new Map([['host/probe/read',{relativeMs:500}]])"])
    assert.throws(() => vm.runInContext("configureDealSession({lifetime:'session-1',epochs:{},granted:[],classification:{},confirmation:{},clockTrusted:true,now:1000,expiry:" + container + "})", malformedRuntime), /must be a map of windows/, 'a malformed window container ' + container + ' was accepted');
  assert.throws(() => vm.runInContext(malformedWindow('{relativeMs:500,absolute:-0.5}'), malformedRuntime), /instant or -1/, 'a fractional negative absolute bound was accepted');
  // The production form names the relative bound and publishes -1 for the absolute one.
  vm.runInContext(malformedWindow('{relativeMs:500,absolute:-1}'), malformedRuntime);

  // A granted, read-classified function is authorized with no frozen request and no confirmation, and
  // the operation publishes the validated result.
  const live = open(emitted, grantedFacts());
  await dispatch(live);
  // Exactly one call leaves for one admitted read: a duplicated invocation would show here.
  await tick();
  const admittedRequests = live.requests();
  assert.equal(admittedRequests.length, 1, 'an authorized read issued ' + admittedRequests.length + ' provider calls');
  for (const request of admittedRequests) live.deliver({ id: request.id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  assert.equal(live.requests().length, 0, 'an authorized read issued a second provider call');
  let current = live.state();
  assert.equal(current[scalar('valid')], true, 'the admitted read did not publish its result');
  assert.equal(current[scalar('value')], 42, 'the admitted read published the wrong value');
  assert.equal(current[scalar('pending')], false, 'the admitted read stayed pending');
  assert.equal(current.f_parts_rate, 42, 'the derived value did not follow the admitted result');
  assert.equal(current.f_parts_rate_ready, true, 'the derived value stayed unready');

  // A duplicate tap while the read is in flight preserves the invocation: its effect is not admitted,
  // so its refusal correlates with nothing and the first reply still publishes.
  const duplicate = open(emitted, grantedFacts());
  await dispatch(duplicate);
  await tick();
  const duplicateFlight = duplicate.requests();
  assert.equal(duplicateFlight.length, 1, 'the admitted read did not reach the provider');
  await dispatch(duplicate);
  await tick(); await tick();
  assert.equal(duplicate.requests().length, 0, 'a duplicate pending tap issued a provider call');
  duplicate.deliver({ id: duplicateFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = duplicate.state();
  assert.equal(current[scalar('valid')], true, 'a duplicate pending tap dropped the in-flight reply');
  assert.equal(current[scalar('value')], 42, 'a duplicate pending tap changed the published value');

  // An ungranted module makes no call and records a typed denial: the value it never read stays out.
  // The host publishes no epoch for a module it does not grant, so an admission that inspected the
  // epoch first would report an unissued instant instead of the denial the contract names.
  const denied = open(emitted, grantedFacts({ granted: [], epochs: { 'host/probe': -1 } }));
  await dispatch(denied);
  current = await settle(denied, 42).catch(() => denied.state());
  await tick();
  assert.equal(denied.requests().length, 0, 'an ungranted read dispatched a provider call');
  current = denied.state();
  assert.equal(current[scalar('valid')], false, 'an ungranted read published a value');
  assert.equal(current[scalar('value')], 0, 'an ungranted read changed the result');
  assert.equal(current[scalar('code')], 'DENIED', 'an ungranted read recorded another code');
  assert.equal(current[scalar('pending')], false, 'an ungranted read stayed pending');

  // A host that answered no admission instant at all admits nothing: the call never leaves.
  const unissued = open(emitted, grantedFacts({ now: 0 }));
  await dispatch(unissued);
  await tick(); await tick();
  assert.equal(unissued.requests().length, 0, 'unissued admission facts dispatched a provider call');
  assert.equal(unissued.state()[scalar('code')], 'INVALID_ARGUMENT', 'unissued admission facts recorded another code');
  assert.equal(unissued.state()[scalar('valid')], false, 'unissued admission facts published a value');

  // A refusal changes no business result: a value this Session already published stays displayed while
  // only its validity is withdrawn, so a later edit can reload it without an invented intermediate value.
  const preserved = open(emitted, grantedFacts());
  await dispatch(preserved);
  await tick();
  const published = preserved.requests();
  assert.equal(published.length, 1, 'the admitted read did not reach the provider');
  preserved.deliver({ id: published[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  assert.equal(preserved.state()[scalar('valid')], true, 'the admitted read did not publish its result');
  assert.equal(preserved.state()[scalar('value')], 42, 'the admitted read published the wrong value');
  preserved.configure(grantedFacts({ granted: [] }));
  await dispatch(preserved);
  await tick(); await tick();
  assert.equal(preserved.requests().length, 0, 'the refused invocation dispatched a provider call');
  current = preserved.state();
  assert.equal(current[scalar('code')], 'DENIED', 'the refused invocation recorded another code');
  assert.equal(current[scalar('valid')], false, 'the refused invocation kept its result current');
  assert.equal(current[scalar('value')], 42, 'the refused invocation changed the published result');

  // A function the host classifies mutating is not a read: this part is a read, so it admits nothing.
  for (const classification of ['mutating', '']) {
    const refused = open(emitted, grantedFacts({ classification: classification === '' ? {} : { 'host/probe/read': classification } }));
    await dispatch(refused);
    await tick(); await tick();
    assert.equal(refused.requests().length, 0, 'a non-read classification dispatched a provider call');
    current = refused.state();
    assert.equal(current[scalar('code')], 'DENIED', 'a non-read classification recorded another code');
    assert.equal(current[scalar('valid')], false, 'a non-read classification published a value');
  }

  // A revocation between send and reply discards the reply: no result, no continuation.
  const revoked = open(emitted, grantedFacts());
  await dispatch(revoked);
  await tick();
  const inFlight = revoked.requests();
  assert.equal(inFlight.length, 1, 'the admitted read did not reach the provider');
  revoked.configure(grantedFacts({ epochs: { 'host/probe': 5 } }));
  revoked.deliver({ id: inFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = revoked.state();
  assert.equal(current[scalar('code')], 'REVOKED', 'a reply under a moved epoch was not refused');
  assert.equal(current[scalar('valid')], false, 'a reply under a moved epoch published its value');
  assert.equal(current[scalar('value')], 0, 'a reply under a moved epoch changed the result');

  // An expiry that passed between send and reply is stale: the value is never published.
  const expired = open(emitted, grantedFacts({ expiry: { 'host/probe/read': { relativeMs: 500 } } }));
  await dispatch(expired);
  await tick();
  const expiring = expired.requests();
  assert.equal(expiring.length, 1, 'a windowed read did not reach the provider');
  expired.configure(grantedFacts({ now: 2000, expiry: { 'host/probe/read': { relativeMs: 500 } } }));
  expired.deliver({ id: expiring[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = expired.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'a reply past its window was not refused');
  assert.equal(current[scalar('valid')], false, 'a reply past its window published its value');

  // A window that is still open when the reply arrives publishes it: the expiry decision admits a
  // current invocation instead of refusing every windowed call.
  const windowed = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { relativeMs: 500 } } }));
  await dispatch(windowed);
  await tick();
  const within = windowed.requests();
  assert.equal(within.length, 1, 'a windowed read did not reach the provider');
  windowed.configure(grantedFacts({ now: 1200, expiry: { 'host/probe/read': { relativeMs: 500 } } }));
  windowed.deliver({ id: within[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = windowed.state();
  assert.equal(current[scalar('valid')], true, 'a reply inside its window was refused');
  assert.equal(current[scalar('value')], 42, 'a reply inside its window published no value');

  // An absolute window as given expires the reply that arrives after it.
  const absolute = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { absolute: 1500 } } }));
  await dispatch(absolute);
  await tick();
  const dated = absolute.requests();
  assert.equal(dated.length, 1, 'an absolutely windowed read did not reach the provider');
  absolute.configure(grantedFacts({ now: 1600, expiry: { 'host/probe/read': { absolute: 1500 } } }));
  absolute.deliver({ id: dated[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = absolute.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'a reply past its absolute instant was not refused');
  assert.equal(current[scalar('valid')], false, 'a reply past its absolute instant published its value');

  // An absolute instant of zero has already passed: the absence of a window and a window at the
  // instant of admission are different host answers, and a window that already passed dispatches
  // nothing at all.
  const atAdmission = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { absolute: 0 } } }));
  await dispatch(atAdmission);
  await tick(); await tick();
  assert.equal(atAdmission.requests().length, 0, 'an absolute instant of zero dispatched a provider call');
  current = atAdmission.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'an absolute instant of zero was treated as no window');
  assert.equal(current[scalar('valid')], false, 'an absolute instant of zero published its value');

  // A clock the host stops trusting while the call is in flight makes the window undecidable, even
  // for an absolute instant: the result is never published.
  const untrustedClock = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { absolute: 1500 } } }));
  await dispatch(untrustedClock);
  await tick();
  const untrustedFlight = untrustedClock.requests();
  assert.equal(untrustedFlight.length, 1, 'an absolutely windowed read did not reach the provider');
  untrustedClock.configure(grantedFacts({ now: 1200, clockTrusted: false, expiry: { 'host/probe/read': { absolute: 1500 } } }));
  untrustedClock.deliver({ id: untrustedFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = untrustedClock.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'an undecidable clock published a windowed reply');
  assert.equal(current[scalar('valid')], false, 'an undecidable clock published a value');

  // A host reading that retreated past the admission instant leaves the window undecided too.
  const retreated = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  await dispatch(retreated);
  await tick();
  const retreatedFlight = retreated.requests();
  assert.equal(retreatedFlight.length, 1, 'a windowed read did not reach the provider');
  retreated.configure(grantedFacts({ now: 900, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  retreated.deliver({ id: retreatedFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = retreated.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'a retreated clock published a windowed reply');
  assert.equal(current[scalar('valid')], false, 'a retreated clock published a value');

  // A Session suspend and resume start a new clock period, so a relative window captured under the
  // earlier period is undecidable: the reply is refused although its instant has not passed and the
  // clock is still trusted. An absolute instant is unaffected by the period.
  const suspended = open(emitted, grantedFacts({ now: 1000, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  await dispatch(suspended);
  await tick();
  const suspendedFlight = suspended.requests();
  assert.equal(suspendedFlight.length, 1, 'a relative windowed read did not reach the provider');
  suspended.configure(grantedFacts({ now: 1200, period: 1, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  suspended.deliver({ id: suspendedFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = suspended.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'a reply from another Session clock period was admitted');
  assert.equal(current[scalar('valid')], false, 'a reply from another Session clock period published a value');
  // A call admitted after the resume captures the new period and publishes its reply normally.
  const resumed = open(emitted, grantedFacts({ now: 1000, period: 2, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  await dispatch(resumed);
  await tick();
  const resumedFlight = resumed.requests();
  assert.equal(resumedFlight.length, 1, 'a read admitted after a resume did not reach the provider');
  resumed.configure(grantedFacts({ now: 1200, period: 2, expiry: { 'host/probe/read': { relativeMs: 5000 } } }));
  resumed.deliver({ id: resumedFlight[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = resumed.state();
  assert.equal(current[scalar('valid')], true, 'a reply admitted under its own Session period was refused');
  assert.equal(current[scalar('value')], 42, 'a reply admitted under its own Session period published no value');

  // The host withdrawing the module's grant while the call is in flight discards the reply.
  const withdrawn = open(emitted, grantedFacts());
  await dispatch(withdrawn);
  await tick();
  const unGranted = withdrawn.requests();
  assert.equal(unGranted.length, 1, 'the admitted read did not reach the provider');
  withdrawn.configure(grantedFacts({ granted: [] }));
  withdrawn.deliver({ id: unGranted[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = withdrawn.state();
  assert.equal(current[scalar('code')], 'REVOKED', 'a reply after the grant was withdrawn was not refused');
  assert.equal(current[scalar('valid')], false, 'a reply after the grant was withdrawn published its value');

  // A Session lifetime that moved between send and reply authorizes neither the result nor a
  // continuation of the invocation it was captured under.
  const reissued = open(emitted, grantedFacts());
  await dispatch(reissued);
  await tick();
  const staleLifetime = reissued.requests();
  assert.equal(staleLifetime.length, 1, 'the admitted read did not reach the provider');
  reissued.configure(grantedFacts({ lifetime: 'session-2' }));
  reissued.deliver({ id: staleLifetime[0].id, ok: true, value: 42 });
  for (let turn = 0; turn < 200; turn++) await tick();
  current = reissued.state();
  assert.equal(current[scalar('code')], 'REVOKED', 'a reply under another Session lifetime was not refused');
  assert.equal(current[scalar('valid')], false, 'a reply under another Session lifetime published its value');
  assert.equal(current[scalar('value')], 0, 'a reply under another Session lifetime changed the result');

  // An undecidable clock admits no relative window at all, so the call never leaves.
  const uncertain = open(emitted, grantedFacts({ clockTrusted: false, expiry: { 'host/probe/read': { relativeMs: 500 } } }));
  await dispatch(uncertain);
  await tick(); await tick();
  assert.equal(uncertain.requests().length, 0, 'a relative window under an undecidable clock dispatched a call');
  current = uncertain.state();
  assert.equal(current[scalar('code')], 'EXPIRED', 'an undecidable window recorded another code');
  assert.equal(current[scalar('valid')], false, 'an undecidable window published a value');

  // The provider failure keeps its typed classification and publishes no value.
  const failed = open(emitted, grantedFacts());
  await dispatch(failed);
  await tick();
  const failing = failed.requests();
  assert.equal(failing.length, 1, 'the failure scenario issued no call');
  failed.runtime.failure = { id: failing[0].id, ok: false, error: { code: 'PROVIDER_ERROR', message: 'Injected provider failure' } };
  vm.runInContext('dealCapabilities.deliver([failure])', failed.runtime);
  for (let turn = 0; turn < 200; turn++) await tick();
  current = failed.state();
  assert.equal(current[scalar('outcome')], 'failure', 'a provider failure did not record its outcome');
  assert.equal(current[scalar('code')], 'PROVIDER_ERROR', 'a provider failure lost its typed code');
  assert.equal(current[scalar('valid')], false, 'a provider failure published a value');

  const transportExit = await transport.close(); if (transportExit !== 0) throw Error('syntax transport exited ' + transportExit);
  console.log('provider authority: read classification, grant, captured epoch and expiry, send/reply correlation and no-publication refusals passed');
})().catch(error => { transport.close().catch(() => {}); console.error(error); process.exitCode = 1; });

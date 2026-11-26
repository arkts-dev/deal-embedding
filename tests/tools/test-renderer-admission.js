#!/usr/bin/env node
'use strict';
// Real pinned Session + shipped admission/mount adapter/mechanisms; only the compiled ABI/codec are
// fixtures. No compiler, Android, provider or paid request. Proves the Session-boundary ordering: the
// whole candidate is admitted against the issued pack facts before business state, the display tree,
// the published version or an effect can commit, and the isolated checker reports the same fault as a
// located CHECK_PROP/CHECK_CARD diagnostic instead of activating a degraded tree. Trees are built from
// the generated declarations, so every case starts from the props the pack requires.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('node:assert/strict');
const rendererContract = require('./fixtures/renderer-contract.js');

const BINDINGS = ['text-pattern.js', 'renderer-admission.js', 'ui-session.js', 'checking-session.js'];

const FIXTURE = `
  const issued = globalThis.rendererContract;
  const declarations = new Map((issued === undefined ? [] : issued.declarations.components).map(entry => [entry.component, entry]));
  function propOf(row, value) {
    switch (row.kind) {
      case 'number': return {name:row.name, kind:'number', numberValue:value, stringValue:'', booleanValue:false, actionSlot:-1};
      case 'boolean': return {name:row.name, kind:'boolean', booleanValue:value === true, stringValue:'', numberValue:0, actionSlot:-1};
      case 'action': return {name:row.name, kind:'action', actionSlot:value, stringValue:'', numberValue:0, booleanValue:false};
      case 'json':
      case 'numbers': return {name:row.name, kind:'json', stringValue:JSON.stringify(value), numberValue:0, booleanValue:false, actionSlot:-1};
      default: return {name:row.name, kind:'string', stringValue:value, numberValue:0, booleanValue:false, actionSlot:-1};
    }
  }
  function node(component, overrides, children) {
    const declared = declarations.get(component);
    if (declared === undefined) throw Error('fixture tree names an undeclared component: ' + component);
    const props = [];
    for (const row of declared.props) {
      if (Object.hasOwn(overrides, row.name)) { props.push(propOf(row, overrides[row.name])); continue; }
      if (Object.hasOwn(row, 'default')) { props.push(propOf(row, row.default)); continue; }
      if (row.optional === true) continue;
      throw Error('fixture tree omits required ' + component + '.' + row.name);
    }
    for (const name of Object.keys(overrides))
      if (!declared.props.some(row => row.name === name)) throw Error('fixture tree overrides undeclared ' + component + '.' + name);
    return {component: component, props: props, children: children || []};
  }
  let initialTree = null, nextTree = null, effects = 0;
  const fn = f => ({$kind:'function', $f:f, $sig:'(): fixture'});
  const entry = {
    bridgeInitialState:fn(()=>({count:0})), bridgeSchemaDigest:fn(()=>''),
    initialStore:fn(()=>({lifecycle:{disposed:false},queue:[]})),
    initial:fn((state,store)=>({tree:initialTree,store,effect:{effectId:-1}})),
    bridgeSessionStatus:fn(()=>({version:0,fault:''})),
    enqueue:fn((store,action)=>({store:{...store,queue:[...store.queue,action]},accepted:true,startDrain:true})),
    dequeue:fn(store=>({store:{...store,queue:store.queue.slice(1)},present:store.queue.length>0,action:store.queue[0]})),
    nextState:fn(state=>({count:state.count+1})),
    transitionFromCandidate:fn((candidate,old,store)=>({tree:nextTree,store,effect:{effectId:0}})),
    bridgeSessionCommitted:fn(status=>({version:status.version+1,fault:''})),
    bridgeSessionFailed:fn((status,fault)=>({...status,fault})),
    reject:fn(store=>store), finish:fn(store=>store),
    bridgeSessionStarted:fn(status=>status), bridgeSessionExited:fn(status=>status),
    bridgeReplacementReady:fn(()=>true), bridgeEffect0:fn(()=>{effects++;return null;}),
    effectCommand:fn(()=>({operation:'start',key:'',delayMillis:0,cancellationMode:'none'})),
    payloadType_1:fn(()=>'none'), action_1:fn(()=>({})),
    payloadType_2:fn(()=>'json'), decodePayload_2:fn(text=>JSON.parse(text)), action_2:fn(()=>({})),
    payloadType_0:fn(()=>'number'), action_0:fn(()=>({})), checkAction_0:fn(()=>({})),
    bridgeFrameVisibility:fn(()=>({visible:false})),
    checkHandlerCount:fn(()=>0),
  };
  globalThis.createDealUiCheckpoint = ()=>({encode:value=>value,decode:value=>value});
  globalThis.dealLoad = ()=>({entry,rt:{reifyError:error=>error,runtimeDiagnostic:error=>String(error)},checkpointSchema:{}});
  globalThis.dealCapabilities = {request:()=>{throw Error('no provider');}};
  globalThis.__lane = {
    node: (component, overrides, children) => node(component, overrides || {}, children),
    root: children => ({component:'root', props:[], children: children || []}),
    mount: tree => { initialTree = tree; return mountDealUi({},'fixture'); },
    next: tree => { nextTree = tree; },
    effects: () => effects,
    snapshot: () => JSON.parse(dealUi.snapshot()),
    state: () => dealUi.state(),
    dispatch: slot => dealUi.dispatch(slot),
    check: tree => { initialTree = tree; openChecking({},'fixture'); return JSON.parse(checkingStep('start',0,null)); },
    step: (operation, index, payload) => JSON.parse(checkingStep(operation, index, JSON.stringify(payload))),
    // Can a module loaded after the bindings replace the admission verdict? The binding is sealed, so a
    // strict-mode assignment must fail and the verdict must stay the sealed one.
    verdict: () => {
      const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'validateRendererValues');
      let refused = false;
      try { Function('"use strict"; globalThis.validateRendererValues = () => true;')(); } catch (_) { refused = true; }
      return {writable: descriptor === undefined ? null : descriptor.writable,
              configurable: descriptor === undefined ? null : descriptor.configurable,
              refused: refused, intact: typeof globalThis.validateRendererValues === 'function'};
    },
  };
`;

/** One isolated Session lane over the shipped bindings, with the host's issued target. */
function lane(target, issued) {
  const sandbox = {};
  if (target !== null) sandbox.rendererContract = issued === undefined ? rendererContract(target) : issued;
  const context = vm.createContext(sandbox);
  // The host issues the facts by evaluating the assembled contract script, which seals the binding
  // before any module runs; the injected fixture object is sealed the same way here.
  if (target !== null) vm.runInContext("Object.defineProperty(globalThis, 'rendererContract', {value: globalThis.rendererContract, writable: false, configurable: false});", context);
  for (const file of [path.join(process.env.DEAL_UI_ROOT || 'dependencies/deal-ui', 'runtime-js/session.js'),
                      ...BINDINGS.map(name => 'android/assets/embedding/bindings/' + name)])
    vm.runInContext(fs.readFileSync(file, 'utf8'), context);
  vm.runInContext(FIXTURE, context);
  return context.__lane;
}

const shape = index => ({tag:'rect', x:index, y:0, width:1, height:1, color:'#010203'});
const shapes = count => Array.from({length: count}, (_, index) => shape(index));
const identity = [0, 0, 0, 0, 0, 0, 1, 1];

/** The published tree carries the Session's empty binding map on every prop; the fixture tree does not. */
const published = tree => JSON.parse(JSON.stringify(tree, (key, value) => key === 'actionBindings' ? undefined : value));

/** A committed candidate: the version advances once, the tree publishes and no fault is retained. */
function committedLane(lane, tree, message) {
  const before = lane.snapshot().version; lane.next(tree); lane.dispatch(1);
  const snapshot = lane.snapshot();
  assert.equal(snapshot.version, before + 1, message + ': valid candidate did not commit');
  assert.equal(snapshot.fault, '', message + ': ' + snapshot.fault);
  return snapshot;
}

/** A refused candidate: prior state/tree/version retained, no effect, and the located fault kept. */
function refused(lane, baseline, tree, message) {
  lane.next(tree);
  lane.dispatch(1);
  const snapshot = lane.snapshot();
  assert.equal(snapshot.version, baseline.version, 'rejected candidate committed: ' + message);
  assert.equal(lane.state(), baseline.state, message + ': business state changed');
  assert.deepEqual(snapshot.tree, baseline.tree, message + ': display tree changed');
  assert.equal(lane.effects(), baseline.effects, message + ': an effect was scheduled');
  // Every refusal is the admission verdict itself: an unrelated exception would keep the same prior
  // values but leave the candidate undiagnosed, which bounded repair cannot route.
  assert.match(snapshot.fault, /^(?:Error: )?(PROP_FAULT|CARD_FAULT|ADMISSION_[A-Z]+):/, message + ': not a located admission fault: ' + snapshot.fault);
  return snapshot.fault;
}

// ------------------------------------------------------------------ screen target

const screen = lane('screen');
const field = overrides => screen.node('TextField', Object.assign({label:'Value', value:'a', pattern:'(a)\\1', onChange:1}, overrides));
screen.mount(screen.root([field({}), screen.node('Progress', {label:'done', value:1, maximum:2})]));
const baseline = {version: screen.snapshot().version, tree: screen.snapshot().tree, state: screen.state(), effects: screen.effects()};

let fault = refused(screen, baseline, screen.root([field({}), screen.node('Progress', {label:'done', value:0, maximum:0})]),
                    'Progress.maximum = 0');
assert.match(fault, /PROP_FAULT: Progress\.maximum \[positive\]/, 'the prop class is not named: ' + fault);
fault = refused(screen, baseline, screen.root([screen.node('Transform', {pose:identity, durationMs:-1})]), 'negative Transform duration');
assert.match(fault, /PROP_FAULT: Transform\.durationMs \[nonnegative\]/, 'the prop class is not named: ' + fault);
refused(screen, baseline, screen.root([screen.node('ProgressRing', {label:'ring', value:1, maximum:-1})]), 'negative progress maximum');
refused(screen, baseline, screen.root([screen.node('Transform', {pose:[0, 0, 0, 0, 0], durationMs:10})]), 'short Transform pose');
refused(screen, baseline, screen.root([screen.node('Transform', {pose:[0, 0, 0, 0, 0, 0, 1, 3], durationMs:10})]), 'Transform opacity outside [0, 1]');
refused(screen, baseline, screen.root([screen.node('Sparkline', {series:[1, 2], maximum:0})]), 'chart maximum not above minimum');
refused(screen, baseline, screen.root([screen.node('Layers', {width:-1, height:1})]), 'negative size');
refused(screen, baseline, screen.root([screen.node('Layers', {width:Infinity, height:1})]), 'nonfinite size');
fault = refused(screen, baseline, screen.root([field({}), screen.node('Button', {text:'x', value:Infinity})]), 'nonfinite finite-class prop');
assert.match(fault, /PROP_FAULT: Button\.value \[finite\]/, 'the finite class is not named: ' + fault);
refused(screen, baseline, screen.root([screen.node('NumberField', {label:'n', value:1, minimum:0, maximum:10, step:0})]), 'zero step');
refused(screen, baseline, screen.root([screen.node('Select', {labels:['a', 'b'], selected:2})]), 'out-of-range selection');
refused(screen, baseline, screen.root([screen.node('Tabs', {labels:['only', 7], selected:0}, [screen.node('Text', {text:'x'})])]), 'non-string tab label');
refused(screen, baseline, screen.root([screen.node('Icon', {name:'sparkles'})]), 'unknown icon');
refused(screen, baseline, screen.root([screen.node('Surface', {width:10, height:10, shapes:[{tag:'star', color:'#010203'}]})]), 'unsupported shape tag');
fault = refused(screen, baseline, screen.root([screen.node('Surface', {width:10, height:10,
  shapes:[{tag:'polyline', points:Array.from({length: 2002}, (_, index) => index), lineWidth:1, color:'#010203'}]})]), 'too many points');
assert.match(fault, /exceeds 1000 points/, 'the point bound is not stated in spec points: ' + fault);
refused(screen, baseline, screen.root([field({pattern:'['})]), 'invalid pattern');
refused(screen, baseline, screen.root([screen.node('TimeField', {label:'t', value:1440})]), 'minute outside the day');
refused(screen, baseline, screen.root([screen.node('ClockText', {minutes:0.5})]), 'fractional clock');
refused(screen, baseline, screen.root([screen.node('DateField', {label:'d', value:0.5})]), 'fractional date');
const progress = screen.node('Progress', {label:'done', value:5, maximum:10});
refused(screen, baseline, screen.root([{component:'Progress', props:progress.props.concat([{name:'bogus', kind:'string', stringValue:'x', numberValue:0, booleanValue:false, actionSlot:-1}]), children:[]}]),
        'undeclared prop');
refused(screen, baseline, screen.root([{component:'Progress', props:progress.props.map(prop => prop.name === 'maximum' ? {...prop, kind:'string', stringValue:'10', numberValue:10} : prop), children:[]}]),
        'prop kind mismatch');
fault = refused(screen, baseline, {component:'root', props:[], children:[null]}, 'non-object child');
assert.match(fault, /PROP_FAULT: root\.children: child must be an object/, 'a malformed child is not located: ' + fault);
// A rule that reads its rows (Table columns against every row's cells) refuses a malformed row with
// the same located fault; an unlocated TypeError would not be reportable source feedback.
fault = refused(screen, baseline, screen.root([field({}), screen.node('Table', {columns:1, headers:['h']}, [null])]), 'non-object table row');
assert.match(fault, /PROP_FAULT: Table\.children: child must be an object/, 'a malformed table row is not located: ' + fault);
fault = refused(screen, baseline, null, 'absent candidate tree');
assert.match(fault, /PROP_FAULT: candidate tree is absent/, 'an absent tree is not located: ' + fault);

// The whole candidate is refused, never one node: a valid sibling neither commits nor rescues the tree.
refused(screen, baseline, screen.root([field({}), screen.node('Progress', {label:'done', value:0, maximum:0})]), 'invalid node with valid siblings');

// A valid sibling commits after the refusals and dispatches its one effect.
const valid = screen.root([
  field({pattern:'(?<year>\\d{4})'}),
  screen.node('Progress', {label:'done', value:5, maximum:10}),
  screen.node('Transform', {pose:identity, durationMs:0}),
]);
screen.next(valid);
screen.dispatch(1);
let snapshot = screen.snapshot();
assert.equal(snapshot.version, baseline.version + 1, 'valid sibling did not commit');
assert.equal(screen.effects(), baseline.effects + 1, 'valid sibling scheduled no effect');
assert.deepEqual(published(snapshot.tree), published(valid), 'valid sibling did not publish its tree');
assert.equal(snapshot.fault, '', 'a committed candidate keeps a fault: ' + snapshot.fault);

// An invalid initial tree is refused before any Session exists, so nothing is published.
const fresh = lane('screen');
assert.throws(() => fresh.mount(fresh.root([fresh.node('Progress', {label:'done', value:1, maximum:0})])),
              /PROP_FAULT: Progress\.maximum \[positive\]/, 'invalid initial tree published');

// A Session without its issued pack facts fails closed instead of committing unvalidated candidates.
const unissued = lane(null);
assert.throws(() => unissued.mount(unissued.root([{component:'Column', props:[], children:[]}])),
              /ADMISSION_PACK: renderer pack metadata is not issued/, 'an unissued Session admitted a candidate');
// The verdict is sealed exactly as the issued facts are: a module loaded after the bindings cannot
// replace it and keep admitting candidates it never checks.
const sealed = lane('screen');
const verdict = sealed.verdict();
assert.equal(verdict.writable, false, 'the admission verdict binding is writable');
assert.equal(verdict.configurable, false, 'the admission verdict binding is configurable');
assert.equal(verdict.refused, true, 'a later module replaced the admission verdict');
assert.equal(verdict.intact, true, 'the admission verdict did not survive the replacement attempt');
// An out-of-set or empty target is refused by both lanes; the issued object is doctored deliberately,
// because the fixture helper itself only issues the three host targets.
const wrongTarget = lane('print', Object.assign({}, rendererContract('screen'), {target:'print'}));
assert.throws(() => wrongTarget.mount(wrongTarget.root([{component:'Column', props:[], children:[]}])),
              /ADMISSION_TARGET: issued target is absent or outside screen, chat and card/, 'a Session admitted an unissued target');
const targetless = lane('', Object.assign({}, rendererContract('screen'), {target:''}));
assert.throws(() => targetless.mount(targetless.root([{component:'Column', props:[], children:[]}])), /ADMISSION_TARGET/);
const targetReport = wrongTarget.check(wrongTarget.root([{component:'Column', props:[], children:[]}]));
assert.equal(targetReport.ok, false, 'the checker activated an unissued target');
assert.match(targetReport.diagnostics, /^ADMISSION_TARGET:/, targetReport.diagnostics);
const incomplete = lane('screen', Object.assign({}, rendererContract('screen'),
  {card: {components: ['Column'], events: [], limits: {pictures: 5}}}));
assert.throws(() => incomplete.mount(incomplete.root([{component:'Column', props:[], children:[]}])),
              /ADMISSION_PACK: renderer pack metadata is incomplete/, 'an incomplete projection was admitted');
// A constraint row that lost its class is refused with the rest of the projection: falling back to the
// default finite class would silently drop a positive or nonnegative rule instead of enforcing it.
const classless = lane('screen', Object.assign({}, rendererContract('screen'),
  {constraints: {version: 2, constraints: [{prop: 'Progress.maximum'}]}}));
assert.throws(() => classless.mount(classless.root([classless.node('Progress', {label:'done', value:0, maximum:0})])),
              /ADMISSION_PACK: renderer pack metadata is incomplete/, 'a constraint row without its class was admitted');
// A class row that vanished is the same lost rule one step later: the default finite class would admit
// a value the renderer still refuses, so the projection is refused instead of one constraint degrading.
const missing = Object.assign({}, rendererContract('screen'));
missing.constraints = {version: 2, constraints: missing.constraints.constraints.filter(row => row.prop !== 'Progress.maximum')};
const uncovered = lane('screen', missing);
assert.throws(() => uncovered.mount(uncovered.root([uncovered.node('Progress', {label:'done', value:0, maximum:0})])),
              /ADMISSION_PACK: renderer constraint metadata is incomplete/, 'a projection missing a constraint row was admitted');
const kindless = lane('screen', Object.assign({}, rendererContract('screen'),
  {declarations: {version: 2, components: [{component: 'Column', props: [{name: 'style', default: ''}]}]}}));
assert.throws(() => kindless.mount(kindless.root([{component:'Column', props:[], children:[]}])),
              /ADMISSION_PACK: renderer pack metadata is incomplete/, 'a declaration row without its kind was admitted');
// A class this mechanism does not decide is a lost rule too: admitting the value unvalidated would be
// the silent degradation every other projection fault refuses.
const unknownClass = Object.assign({}, rendererContract('screen'));
unknownClass.constraints = {version: 2, constraints: unknownClass.constraints.constraints.map(row =>
  row.prop === 'Progress.maximum' ? {prop: row.prop, class: 'bounded'} : row)};
const undecided = lane('screen', unknownClass);
assert.throws(() => undecided.mount(undecided.root([undecided.node('Progress', {label:'done', value:1, maximum:2})])),
              /ADMISSION_PACK: unknown prop constraint class bounded/, 'an undecided constraint class admitted its prop');

/** The retained-values baseline of a lane: the published version/tree plus business state and effects. */
const stable = lane => ({version: lane.snapshot().version, tree: lane.snapshot().tree, state: lane.state(), effects: lane.effects()});

// Temporal domains are proven in both directions: 0-1439 integral minutes of the day, an integral
// local epoch day, and refusal outside each domain.
for (const value of [0, 1439]) {
  committedLane(screen, screen.root([field({}), screen.node('TimeField', {label:'t', value:value})]), 'minute ' + value + ' inside the day');
  committedLane(screen, screen.root([field({}), screen.node('ClockText', {minutes:value})]), 'clock ' + value + ' inside the day');
}
for (const value of [-1, 0]) committedLane(screen, screen.root([field({}), screen.node('DateField', {label:'d', value:value})]), 'epoch day ' + value);
for (const value of [-1, NaN, Infinity]) {
  refused(screen, stable(screen), screen.root([field({}), screen.node('TimeField', {label:'t', value:value})]), 'TimeField ' + value);
  refused(screen, stable(screen), screen.root([field({}), screen.node('ClockText', {minutes:value})]), 'ClockText ' + value);
}
refused(screen, stable(screen), screen.root([field({}), screen.node('DateField', {label:'d', value:NaN})]), 'nonfinite epoch day');

// The shared path grammar separates coordinates with Kotlin's whitespace class: a non-breaking space
// separates numbers, while the byte-order mark is not whitespace.
committedLane(screen, screen.root([field({}), screen.node('Surface', {width:10, height:10, shapes:[{tag:'path', commands:'M\u00A00 0', color:'#112233'}]})]), 'non-breaking space in path data');
refused(screen, stable(screen), screen.root([field({}), screen.node('Surface', {width:10, height:10, shapes:[{tag:'path', commands:'M\uFEFF0 0', color:'#112233'}]})]), 'byte-order mark in path data');
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Surface', {width:10, height:10, shapes:[{tag:'path', commands:'M 0 .', color:'#112233'}]})]), 'a path number with no digits');
assert.match(fault, /path commands has an invalid number/, 'the command number rule is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Surface', {width:10, height:10, shapes:[{tag:'path', commands:'M 1e999 0', color:'#112233'}]})]), 'a path number that overflows binary64');
assert.match(fault, /path commands has a nonfinite number/, 'the nonfinite command number rule is not named: ' + fault);

// Every remaining class of the constraint table is decided in both directions, from the spec's rule
// (“Dimensions, sizes and durations are finite and nonnegative; Ticker intervals, Stage perspective,
// AdaptiveColumns minimumWidth and progress maxima positive ... Clamp displayed progress only”).
committedLane(screen, screen.root([field({}), screen.node('Progress', {label:'over', value:120, maximum:100})]), 'progress above its maximum is clamped, not refused');
committedLane(screen, screen.root([field({}), screen.node('Progress', {label:'under', value:-5, maximum:100})]), 'negative progress is clamped, not refused');
// A nonfinite value is not an over-range one: the wire form cannot carry it, and the renderer's
// decoder rejects it before the painter could clamp anything.
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Progress', {label:'nan', value:NaN, maximum:100})]), 'nonfinite progress value');
assert.match(fault, /PROP_FAULT: Progress\.value \[display-clamp\]: progress value must be finite/, 'a nonfinite progress value is not refused: ' + fault);
refused(screen, stable(screen), screen.root([field({}), screen.node('ProgressRing', {label:'ring', value:Infinity, maximum:100, text:''})]), 'infinite ring value');
committedLane(screen, screen.root([field({}), screen.node('Ticker', {id:'tick', intervalMs:1, running:false, onTick:1})]), 'positive ticker interval');
committedLane(screen, screen.root([field({}), screen.node('Stage', {perspective:1, height:0})]), 'positive perspective with a zero height');
committedLane(screen, screen.root([field({}), screen.node('AdaptiveColumns', {minimumWidth:1})]), 'positive adaptive minimum width');
committedLane(screen, screen.root([field({}), screen.node('Transform', {pose:identity, durationMs:0, size:0})]), 'zero duration and size');
committedLane(screen, screen.root([field({}), screen.node('Skeleton', {width:0, height:0})]), 'zero skeleton size');
committedLane(screen, screen.root([field({}), screen.node('Grid', {columns:0})]), 'zero grid columns');
committedLane(screen, screen.root([field({}), screen.node('Timer', {millis:-1, down:true})]), 'negative epoch timer origin is finite');
committedLane(screen, screen.root([field({}), screen.node('Sparkline', {series:[], maximum:0})]), 'an empty chart needs no maximum above its minimum');
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Ticker', {id:'tick', intervalMs:0, running:false, onTick:1})]), 'zero ticker interval');
assert.match(fault, /PROP_FAULT: Ticker\.intervalMs \[positive\]/, 'the positive class is not named for a ticker: ' + fault);
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Stage', {perspective:0})]), 'zero perspective');
assert.match(fault, /PROP_FAULT: Stage\.perspective \[positive\]/, 'the positive class is not named for the stage: ' + fault);
refused(screen, stable(screen), screen.root([field({}), screen.node('AdaptiveColumns', {minimumWidth:0})]), 'zero adaptive minimum width');
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('Transform', {pose:identity, durationMs:0, size:-1})]), 'negative transform size');
assert.match(fault, /PROP_FAULT: Transform\.size \[nonnegative\]/, 'the nonnegative class is not named for a transform: ' + fault);
refused(screen, stable(screen), screen.root([field({}), screen.node('Skeleton', {width:-1, height:1})]), 'negative skeleton width');
refused(screen, stable(screen), screen.root([field({}), screen.node('Surface', {width:-1, height:1, shapes:[]})]), 'negative surface width');
refused(screen, stable(screen), screen.root([field({}), screen.node('Grid', {columns:1.5})]), 'fractional grid columns');

// The numeric presentation fields are their own domain (spec: "fixed integral 0-6", "positive
// integral padding"), decided here as well as at paint.
committedLane(screen, screen.root([field({}), screen.node('NumberText', {value:1.5, decimals:-1, minimumDigits:1})]), 'automatic decimals and one padding digit');
committedLane(screen, screen.root([field({}), screen.node('NumberText', {value:1.5, decimals:6, minimumDigits:3})]), 'the widest allowed presentation');
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('NumberText', {value:1.5, decimals:7})]), 'decimals above six');
assert.match(fault, /PROP_FAULT: NumberText\.decimals: presentation/, 'the presentation domain is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('NumberText', {value:1.5, decimals:1.5})]), 'fractional decimals');
assert.match(fault, /PROP_FAULT: NumberText\.decimals: presentation/, 'the fractional decimals rule is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([field({}), screen.node('NumberText', {value:1.5, minimumDigits:0})]), 'zero padding digits');
assert.match(fault, /PROP_FAULT: NumberText\.minimumDigits: presentation/, 'the padding rule is not named: ' + fault);
refused(screen, stable(screen), screen.root([field({}), screen.node('NumberText', {value:1.5, minimumDigits:2.5})]), 'fractional padding digits');

// The remaining component-scoped rules of the same table.
fault = refused(screen, stable(screen), screen.root([screen.node('Tabs', {labels:['a'], selected:0}, [screen.node('Text', {text:'one'}), screen.node('Text', {text:'two'})])]), 'a label without its child');
assert.match(fault, /PROP_FAULT: Tabs\.selected \[integral-index\]: one label per child/, 'the label-per-child rule is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([screen.node('Table', {columns:2, headers:['a', 'b']}, [screen.node('TableRow', {cells:['only']})])]), 'a row short of its columns');
assert.match(fault, /PROP_FAULT: Table\.columns \[integral-index\]/, 'the cell-count rule is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([screen.node('Tile', {label:'l', value:'v', span:3})]), 'a tile spanning three cells');
assert.match(fault, /PROP_FAULT: Tile\.span: span must be 1 or 2/, 'the tile span rule is not named: ' + fault);
fault = refused(screen, stable(screen), screen.root([{component:'Progress', props:'not an array', children:[]}]), 'props that are not an array');
assert.match(fault, /PROP_FAULT: Progress: props must be an array/, 'malformed props are not located: ' + fault);
// The renderer's transport reads these fields with throwing accessors, so a prop that omits the value
// its kind carries is refused here instead of committing and failing to decode at paint. A prop entry
// that is not an object is the same kind of malformed node and is refused with it.
fault = refused(screen, stable(screen), screen.root([{component:'Progress', props:progress.props.concat([null]), children:[]}]), 'a prop entry that is not an object');
assert.match(fault, /PROP_FAULT: Progress: prop must be an object/, 'a malformed prop entry is not located: ' + fault);
fault = refused(screen, stable(screen), screen.root([{component:'Progress', props:progress.props.map(prop =>
  prop.name === 'value' ? {name:'value', kind:'number', stringValue:'1'} : prop), children:[]}]), 'number prop without numberValue');
assert.match(fault, /PROP_FAULT: Progress\.value: numberValue is required/, 'a missing numeric value is not located: ' + fault);
const toggle = screen.node('Toggle', {label:'on', checked:true, onChange:1});
fault = refused(screen, stable(screen), screen.root([{component:'Toggle', props:toggle.props.map(prop => prop.name === 'checked' ? {name:'checked', kind:'boolean'} : prop), children:[]}]), 'boolean prop without booleanValue');
assert.match(fault, /PROP_FAULT: Toggle\.checked: booleanValue is required/, 'a missing flag value is not located: ' + fault);
fault = refused(screen, stable(screen), screen.root([screen.node('Button', {text:'go', onClick:1}), {component:'Button', props:[{name:'text', kind:'string', stringValue:'go', numberValue:0, booleanValue:false, actionSlot:0}, {name:'onClick', kind:'action'}], children:[]}]), 'action prop without actionSlot');
assert.match(fault, /PROP_FAULT: Button\.onClick: actionSlot is required/, 'a missing action slot is not located: ' + fault);

// -------------------------------------------------------------------- card target

const card = lane('card');
const tap = {text:'Start', onClick:1};
card.mount(card.root([card.node('Text', {text:'card'}), card.node('Button', tap)]));
const cardBaseline = {version: card.snapshot().version, tree: card.snapshot().tree, state: card.state(), effects: card.effects()};
fault = refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap), card.node('TextField', {label:'x', value:''})]),
                'component outside the card profile');
assert.match(fault, /CARD_FAULT: TextField/, 'the card fault is not located: ' + fault);
fault = refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap),
  card.node('Surface', {width:10, height:10, shapes:shapes(1), onFrame:1})]), 'event outside the card profile');
assert.match(fault, /CARD_FAULT: Surface\.onFrame/, 'the card event fault is not located: ' + fault);
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap), card.node('Surface', {width:10, height:10, shapes:shapes(61)})]),
        'shape limit per picture');
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap), card.node('Path', {commands:'M 0 0 L 1 1', spinPeriodMs:10})]),
        'Path rotation on the card target');
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap),
  ...Array.from({length: 6}, () => card.node('Motion', {trigger:1}))]), 'six Motions');
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap),
  ...Array.from({length: 4}, () => card.node('Timer', {millis:0, down:true}))]), 'four native Timers');
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap),
  ...Array.from({length: 6}, () => card.node('Sparkline', {series:[1, 2], maximum:3}))]), 'six pictures');
refused(card, cardBaseline, card.root([card.node('Text', {text:'card'}), card.node('Button', tap),
  card.node('Sparkline', {series:Array.from({length: 61}, (_, index) => index), maximum:70})]), 'past the shape bound on one picture');

const fullCard = card.root([
  card.node('Text', {text:'card'}),
  card.node('Button', tap),
  ...Array.from({length: 5}, () => card.node('Surface', {width:10, height:10, shapes:shapes(60)})),
  ...Array.from({length: 5}, () => card.node('Motion', {trigger:1})),
  ...Array.from({length: 3}, () => card.node('Timer', {millis:0, down:true})),
]);
card.next(fullCard);
card.dispatch(1);
snapshot = card.snapshot();
assert.equal(snapshot.version, cardBaseline.version + 1, 'a card at every declared limit did not commit');
assert.equal(card.effects(), cardBaseline.effects + 1, 'an at-limit card scheduled no effect');

// The same content is admitted on the screen and chat targets: the card profile comes from host
// configuration, and a 'chat' Experience must not be refused at its first mount.
for (const target of ['screen', 'chat']) {
  const wide = lane(target);
  wide.mount(wide.root([wide.node('Text', {text:'wide'}), wide.node('Button', tap)]));
  wide.next(wide.root([wide.node('Text', {text:'wide'}), wide.node('Button', tap),
    ...Array.from({length: 6}, () => wide.node('Motion', {trigger:1})),
    ...Array.from({length: 4}, () => wide.node('Timer', {millis:0, down:true}))]));
  wide.dispatch(1);
  assert.equal(wide.snapshot().version, 1, 'the ' + target + ' target applied the card profile');
}

// --------------------------------------------------------------- isolated checker

const checked = lane('screen');
let report = checked.check(checked.root([checked.node('TextField', {label:'Value', value:'a', pattern:'['})]));
assert.equal(report.ok, false, 'the checker activated a candidate with an invalid pattern');
assert.equal(report.tree, '', 'a refused initial candidate published a tree');
assert.equal(report.diagnostics, 'CHECK_PROP: TextField.pattern: invalid ECMAScript Unicode expression', report.diagnostics);
report = checked.check(checked.root([checked.node('Progress', {label:'done', value:1, maximum:0})]));
assert.equal(report.ok, false, 'the checker activated an invalid progress candidate');
assert.equal(report.tree, '', 'a refused initial candidate published a tree');
assert.equal(report.diagnostics, 'CHECK_PROP: Progress.maximum positive', report.diagnostics);
report = checked.check(checked.root([checked.node('Transform', {pose:identity, durationMs:-1})]));
assert.equal(report.diagnostics, 'CHECK_PROP: Transform.durationMs nonnegative', report.diagnostics);
const checkedCard = lane('card');
report = checkedCard.check(checkedCard.root([checkedCard.node('Surface', {width:10, height:10, shapes:shapes(61)})]));
assert.equal(report.diagnostics, 'CHECK_CARD: Surface.shapes (60): shape limit per picture', report.diagnostics);
report = checked.check(checked.root([checked.node('Progress', {label:'done', value:1, maximum:2})]));
assert.equal(report.ok, true, 'the checker refused a valid candidate: ' + report.diagnostics);
report = checked.check(null);
assert.equal(report.ok, false, 'the checker accepted an absent tree');
assert.equal(report.diagnostics, 'CHECK_PROP: candidate tree is absent', report.diagnostics);
report = checked.check(checked.root([checked.node('Table', {columns:1, headers:['h']}, [null])]));
assert.equal(report.diagnostics, 'CHECK_PROP: Table.children: child must be an object', report.diagnostics);
assert.equal(report.ok, false, 'the checker activated a candidate with a malformed table row');

// Every synthetic tree is admitted the same way: a frame event step and a handler step that render an
// invalid candidate return the located fault and keep the last accepted tree instead of adopting it.
const stepping = lane('screen');
const accepted = stepping.root([stepping.node('Surface', {width:10, height:10, shapes:shapes(1), onFrame:0, onPointer:2})]);
assert.equal(stepping.check(accepted).ok, true, 'the checker refused a valid initial tree');
const invalidStep = stepping.root([stepping.node('Surface', {width:10, height:10, shapes:shapes(1), onFrame:0, onPointer:2}),
  stepping.node('Progress', {label:'p', value:0, maximum:0})]);
stepping.next(invalidStep);
report = stepping.step('event', 0, 16);
assert.equal(report.ok, false, 'a synthetic frame step activated an invalid candidate');
assert.equal(report.diagnostics, 'CHECK_PROP: Progress.maximum positive', report.diagnostics);
assert.deepEqual(JSON.parse(report.tree), JSON.parse(JSON.stringify(accepted)), 'the checker adopted the degraded candidate tree');
stepping.next(invalidStep);
report = stepping.step('event', 2, {phase:'down', id:0, x:12, y:12});
assert.equal(report.ok, false, 'a synthetic pointer step activated an invalid candidate');
assert.equal(report.diagnostics, 'CHECK_PROP: Progress.maximum positive', report.diagnostics);
assert.deepEqual(JSON.parse(report.tree), JSON.parse(JSON.stringify(accepted)), 'the checker adopted the degraded candidate tree');
stepping.next(invalidStep);
report = stepping.step('handler', 0, null);
assert.equal(report.ok, false, 'a synthetic handler activated an invalid candidate');
assert.equal(report.diagnostics, 'CHECK_PROP: Progress.maximum positive', report.diagnostics);
assert.deepEqual(JSON.parse(report.tree), JSON.parse(JSON.stringify(accepted)), 'the checker adopted the degraded candidate tree');

// ------------------------------------------------- the mirrored spec mechanisms

// The pack facts are generated and shared, while the spec-fixed mechanisms are stated in both
// boundaries: one runs in Kotlin for painting and one inside the Session isolate. Their tables must
// therefore agree, and this comparison is what catches a copy that drifts: an icon the spec adds, a
// shape tag or limit that moves, or an SVG arity change must be made in both.
const admissionSource = fs.readFileSync('android/assets/embedding/bindings/renderer-admission.js', 'utf8');
const graphicsSource = fs.readFileSync('android/src/rendering/GraphicsValidation.kt', 'utf8');
const styleSource = fs.readFileSync('android/src/rendering/PropResolution.kt', 'utf8');
const stringsOf = text => [...text.matchAll(/["']([^"']*)["']/g)].map(match => match[1]);
const body = (text, open, close) => {
  const at = text.indexOf(open); assert.notEqual(at, -1, 'missing table opener ' + open);
  const stop = text.indexOf(close, at + open.length); assert.notEqual(stop, -1, 'missing table closer ' + close);
  return text.slice(at + open.length, stop);
};
assert.deepEqual(stringsOf(body(admissionSource, 'const ICONS = new Set([', ']);')).sort(),
                 stringsOf(body(styleSource, 'private val icons = setOf(', ')')).sort(),
                 'the closed icon set drifted from StyleFamilies');
assert.deepEqual(stringsOf(body(admissionSource, 'const ALIGN = new Set([', ']);')).sort(),
                 stringsOf(body(graphicsSource, 'private val align = setOf(', ')')).sort(),
                 'the text alignment set drifted from GraphicsValidation');
const grabbed = (text, pattern, what) => {
  const found = text.match(pattern);
  assert.notEqual(found, null, 'missing ' + what);
  return found[1];
};
assert.equal(grabbed(admissionSource, /const COLOUR = \/\^(.+)\$\/;/, 'the admission colour grammar'),
             grabbed(graphicsSource, /private val colour = Regex\("(.+)"\)/, 'the renderer colour grammar'),
             'the shape colour grammar drifted from GraphicsValidation');
assert.deepEqual(Object.fromEntries([...body(admissionSource, 'const ARITY = {', '};').matchAll(/'(\w)': (\d+)/g)].map(match => [match[1], Number(match[2])])),
                 Object.fromEntries([...body(graphicsSource, 'private val arity = mapOf(', '\n    )').matchAll(/'(\w)' to (\d+)/g)].map(match => [match[1], Number(match[2])])),
                 'the SVG command arity drifted from GraphicsValidation');
const ingressSource = fs.readFileSync('android/src/rendering/IngressValidation.kt', 'utf8');
assert.deepEqual(stringsOf(body(admissionSource, 'const TARGETS = new Set([', ']);')).sort(),
                 stringsOf(body(ingressSource, 'private val targets = setOf(', ')')).sort(),
                 'the issued target set drifted from IngressValidation');
assert.deepEqual(stringsOf(body(admissionSource, 'const ROOTS = new Set([', ']);')).sort(),
                 stringsOf(body(ingressSource, 'private val rootNames = setOf(', ')')).sort(),
                 'the root component names drifted from IngressValidation');
const variantTable = (text, open, close, pattern) => Object.fromEntries([...body(text, open, close).matchAll(pattern)]
  .map(match => [match[1], stringsOf(match[2]).sort()]));
assert.deepEqual(variantTable(admissionSource, 'const VARIANTS = {', '\n  };', /(\w+): \[([^\]]*)\]/g),
                 variantTable(graphicsSource, 'private val variants = mapOf(', '\n    )', /"(\w+)" to listOf\(([^)]*)\)/g),
                 'the shape variants drifted from GraphicsValidation');
assert.deepEqual(variantTable(admissionSource, 'const OPTIONAL = {', '\n  };', /(\w+): new Set\(\[([^\]]*)\]\)/g),
                 variantTable(graphicsSource, 'private val optional = mapOf(', '\n    )', /"(\w+)" to setOf\(([^)]*)\)/g),
                 'the accepted shape fields drifted from GraphicsValidation');
const limitsOf = (text, pattern) => Object.fromEntries([...text.matchAll(pattern)].map(match => [match[1], Number(match[2])]));
assert.deepEqual(limitsOf(admissionSource, /const (MAX_\w+) = (\d+);/g), limitsOf(graphicsSource, /private const val (MAX_\w+) = (\d+)/g),
                 'the graphics bounds drifted from GraphicsValidation');
// The rule bodies carry bounds the tables above do not: the same number must appear on both sides, so
// a domain that moves in one copy cannot leave the Session admitting what the painter refuses. These
// are text pins: a reformatted statement in either copy needs the pinned fragment updated with it.
const numericSource = fs.readFileSync('android/src/rendering/NumericPresentation.kt', 'utf8');
const rendererSource = ingressSource + graphicsSource + numericSource;
for (const [what, admission, renderer] of [
  ['minute-of-day domain', /value < 0 \|\| value > 1439/, /value !in 0\.0\.\.1439\.0/],
  ['fixed decimals', /value === Math\.floor\(value\) && value >= 0 && value <= 6/, /value == kotlin\.math\.floor\(value\) && value >= 0 && value <= 6/],
  ['digit padding', /value === Math\.floor\(value\) && value >= 1/, /value == kotlin\.math\.floor\(value\) && value >= 1/],
  ['pose length', /entries\.length !== 8/, /entries\.size != 8/],
  ['pose opacity', /opacity < 0 \|\| opacity > 1/, /opacity\.value < 0 \|\| opacity\.value > 1/],
  ['select-empty index', /number !== -1/, /number == -1\.0/],
  ['display-clamp finiteness', /fault\('progress value must be finite'\)/, /fault\("progress value must be finite"\)/],
]) {
  assert.match(admissionSource, admission, 'the admission ' + what + ' rule drifted from the renderer');
  assert.match(rendererSource, renderer, 'the renderer ' + what + ' rule drifted from the admission');
}

// ------------------------------------------------------------- shipped wiring

// One Session binds the pack facts by loading the validator and then the adapter, and the entry call
// evaluates the issued contract script against the private host target. Both halves are asserted: the
// binding list alone still admits a Session that never receives the facts, which would refuse every
// mount, and no deterministic lane mounts a real Session through these Kotlin files.
const issue = (file, pattern, what) =>
  assert.match(fs.readFileSync(file, 'utf8'), pattern, what + ' must issue the renderer contract');
assert.match(fs.readFileSync('android/src/bindings/DealSessionBindings.kt', 'utf8'),
             /listOf\(\s*"dealui-session\.js",\s*"text-pattern\.js",\s*"renderer-admission\.js",\s*"ui-session\.js"\s*\)/,
             'the live Session does not load the shared validator');
issue('android/src/bindings/DealSessionBindings.kt',
      /CapabilityBindings\.script\(config\.contracts\)\s*\+\s*RendererContract\.script\(context,\s*config\.target\)/, 'the live Session');
assert.match(fs.readFileSync('android/src/bindings/CandidateChecks.kt', 'utf8'),
             /listOf\(\s*"text-pattern\.js",\s*"renderer-admission\.js",\s*"checking-session\.js"\s*\)/,
             'the isolated checker does not load the shared validator');
issue('android/src/bindings/CandidateChecks.kt',
      /CapabilityBindings\.script\(config\.contracts\)\s*\+\s*RendererContract\.script\(context,\s*config\.target\)/, 'the isolated checker');
assert.match(fs.readFileSync('android/src/rendering/SuggestionSurface.kt', 'utf8'),
             /listOf\(\s*"dealui-session\.js",\s*"text-pattern\.js",\s*"renderer-admission\.js",\s*"ui-session\.js"\s*\)/,
             'the fixed suggestion surface does not load the shared validator');
issue('android/src/rendering/SuggestionSurface.kt', /RendererContract\.script\(context,\s*"screen"\)/, 'the fixed suggestion surface');
// The fixed suggestion view is admitted for one target and painted for one target; they must agree or
// the surface is checked against a profile it is not painted with.
assert.match(fs.readFileSync('android/src/rendering/SuggestionSurface.kt', 'utf8'), /DealRenderer\(tree,\s*"screen"/,
             'the fixed suggestion surface is painted for a different target than it is admitted for');
assert.match(fs.readFileSync('android/src/rendering/RendererContract.kt', 'utf8'), /embedding\/prop-declarations\.json/);
for (const projection of ['prop-constraints', 'card-profile', 'prop-declarations'])
  assert.ok(fs.readFileSync('android/src/rendering/RendererContract.kt', 'utf8').includes('embedding/' + projection + '.json'),
            'the issued contract does not read ' + projection);
// Every class the pack can project is decided here: an unknown class would otherwise fall through the
// switch and admit the value.
const handled = [...body(admissionSource, "switch (propClass) {", '\n    }').matchAll(/case '([\w-]+)':/g)].map(match => match[1]);
const projected = [...new Set(JSON.parse(fs.readFileSync('ui/prop-constraints.json', 'utf8')).constraints.map(row => row.class))];
assert.deepEqual(projected.filter(name => !handled.includes(name)), [], 'a projected prop class has no admission rule');
const producers = fs.readdirSync('android/assets/embedding/bindings')
  .filter(name => name.endsWith('.js'))
  .filter(name => fs.readFileSync('android/assets/embedding/bindings/' + name, 'utf8').includes('globalThis.validateRendererValues'));
assert.deepEqual(producers, ['renderer-admission.js'], 'the shared validator has more than one producer');
console.log('renderer admission: pack constraints and card limits precede state/tree/version/effect commit; invalid candidates keep prior values with no dispatch; valid siblings commit; located CHECK_PROP/CHECK_CARD retained');

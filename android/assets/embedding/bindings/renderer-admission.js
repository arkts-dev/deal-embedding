"use strict";
// Whole-tree candidate admission at the Session boundary. The candidate tree is checked against the
// same issued pack rule set the renderer's ingress validator applies (android/src/rendering/
// IngressValidation.kt), before business state, the display tree, the published version or an effect
// can commit. The pack data - prop constraints, card profile and prop declarations - is the generated
// projection the renderer tables are compiled from, so both boundaries read one conformance-checked
// source instead of a second hand-maintained pack copy; only the spec-fixed mechanisms (closed icon
// set, SVG shape grammar, temporal domain, ECMAScript pattern syntax, numeric presentation fields) are
// stated here as rules, and each mirrors the Kotlin mechanism that owns it for painting.
//
// Every rule throws instead of degrading. The pinned Session catches the throw in its transition,
// rejects the queued action, retains the previous state/tree/version and schedules no effect; the
// isolated checker reports the same fault as a located diagnostic and activates nothing. The private
// host configuration supplies the target through the same issued object, never through tree content.
globalThis.validateRendererValues = (function () {
  const TARGETS = new Set(['screen', 'chat', 'card']);
  const ROOTS = new Set(['root', '__root']);
  // The closed icon set of the pack vocabulary (docs/spec-v0.8.md): an empty icon omits, an unknown one
  // faults. StyleFamilies holds the same closed set.
  const ICONS = new Set(['check', 'close', 'plus', 'minus', 'chevron-right', 'navigate', 'pin', 'calendar',
                         'clock', 'star', 'user', 'warning', 'info', 'trash', 'list', 'chart']);

  let factsContract = null;
  let facts = null;

  /** One located fault: code, component, prop, prop class, limit and reason. */
  function fail(code, component, prop, propClass, detail, count) {
    const name = component + (prop ? '.' + prop : '');
    const located = [];
    if (name) located.push(name);
    if (propClass) located.push('[' + propClass + ']');
    if (count !== undefined) located.push('(limit ' + count + ')');
    let message = code;
    if (located.length) message += ': ' + located.join(' ');
    if (detail) message += ': ' + detail;
    const error = new Error(message);
    error.fault = {code: code, component: component, prop: prop, propClass: propClass, count: count, detail: detail};
    throw error;
  }

  /** Declaration membership, constraint classes and the card profile from one issued projection set. */
  function packFacts(contract) {
    if (factsContract === contract) return facts;
    const card = contract.card;
    const declarations = contract.declarations;
    const constraints = contract.constraints;
    const shaped = name => name !== null && typeof name === 'object';
    // An issued projection that lost a member is not a lax pack: the whole set is refused, so no limit
    // silently stops applying while candidates keep being admitted. Every members check precedes the
    // use so a malformed projection reaches this location instead of a bare TypeError.
    if (!shaped(declarations) || !Array.isArray(declarations.components) ||
        declarations.components.some(entry => !shaped(entry) || !Array.isArray(entry.props) ||
            entry.props.some(row => !shaped(row) || typeof row.name !== 'string' || typeof row.kind !== 'string')) ||
        !shaped(constraints) || !Array.isArray(constraints.constraints) ||
        // A row that lost its class is not an unconstrained prop: the projection is refused so a
        // positive or nonnegative rule never silently degrades to the default finite class.
        constraints.constraints.some(row => !shaped(row) || typeof row.prop !== 'string' || typeof row.class !== 'string') ||
        !shaped(card) || !Array.isArray(card.components) || !Array.isArray(card.events) ||
        !shaped(card.limits) ||
        !['pictures', 'shapesPerPicture', 'motions', 'nativeTimers'].every(name => typeof card.limits[name] === 'number'))
      fail('ADMISSION_PACK', '', null, null, 'renderer pack metadata is incomplete for this Session');
    const byComponent = new Map();
    for (const entry of declarations.components) {
      const byName = new Map();
      for (const row of entry.props) byName.set(row.name, row);
      byComponent.set(entry.component, {rows: entry.props, byName: byName});
    }
    const classes = new Map();
    for (const row of constraints.constraints) classes.set(row.prop, row.class);
    // Every numeric declaration carries a class row: the projection covers the whole numeric identity
    // set, so a class that went missing is a lost rule, not an unconstrained prop. Without this the
    // lookup below would fall back to the default finite class and drop a positive or nonnegative
    // constraint while the renderer still refused the value it admitted.
    for (const [component, declared] of byComponent)
      for (const row of declared.rows)
        if ((row.kind === 'number' || row.kind === 'numbers') && !classes.has(component + '.' + row.name))
          fail('ADMISSION_PACK', '', null, null, 'renderer constraint metadata is incomplete for this Session');
    factsContract = contract;
    facts = {
      declarations: byComponent,
      constraints: classes,
      components: new Set(card.components),
      events: new Set(card.events),
      limits: card.limits,
    };
    return facts;
  }

  function componentName(node) { return String(node.component === undefined || node.component === null ? '' : node.component).split('.').pop(); }
  /** The renderer's transport requires both arrays; a malformed node is a located fault, never an empty list. */
  function childrenOf(node) {
    if (!Array.isArray(node.children)) fail('PROP_FAULT', componentName(node), null, null, 'children must be an array');
    return node.children;
  }
  function propsOf(node) {
    if (!Array.isArray(node.props)) fail('PROP_FAULT', componentName(node), null, null, 'props must be an array');
    for (const prop of node.props)
      if (prop === null || typeof prop !== 'object' || Array.isArray(prop))
        fail('PROP_FAULT', componentName(node), null, null, 'prop must be an object');
    return node.props;
  }
  /** One child as a node object: a malformed entry is a located fault, never a skipped node. */
  function childNode(component, child) {
    if (child === null || typeof child !== 'object' || Array.isArray(child))
      fail('PROP_FAULT', component, 'children', null, 'child must be an object');
    return child;
  }
  function isNumber(value) { return typeof value === 'number'; }

  /**
   * The tree encodes json and number[] props as JSON text. NeutralJson.parse is strict and rejects a
   * nonfinite number literal; an unparseable text is the absent JSON value, never a partial one.
   */
  function parseJson(text) {
    if (typeof text !== 'string') return null;
    let value;
    try { value = JSON.parse(text); } catch (_) { return null; }
    return finiteJson(value) ? value : null;
  }

  function finiteJson(value) {
    if (typeof value === 'number') return Number.isFinite(value);
    if (Array.isArray(value)) return value.every(finiteJson);
    if (value !== null && typeof value === 'object') return Object.values(value).every(finiteJson);
    return true;
  }

  /**
   * Transport values keep the renderer's own kind mapping: every other kind is the neutral text value.
   * The renderer's transport reads numberValue, booleanValue and actionSlot with the throwing JSON
   * accessors, so a prop that carries none of them is a located fault here rather than a candidate the
   * Session commits and the painter then fails to decode.
   */
  function valueOf(component, prop) {
    const mine = detail => fail('PROP_FAULT', component, typeof prop.name === 'string' ? prop.name : null, null, detail);
    switch (prop.kind) {
      case 'number':
        if (!isNumber(prop.numberValue)) mine('numberValue is required');
        return {kind: 'number', value: prop.numberValue};
      case 'boolean':
        if (typeof prop.booleanValue !== 'boolean') mine('booleanValue is required');
        return {kind: 'boolean', value: prop.booleanValue};
      case 'action':
        if (!isNumber(prop.actionSlot)) mine('actionSlot is required');
        return {kind: 'action', slot: prop.actionSlot};
      case 'json': return {kind: 'json', value: parseJson(prop.stringValue)};
      default: return {kind: 'string', value: typeof prop.stringValue === 'string' ? prop.stringValue : ''};
    }
  }

  function kindMatches(kind, prop) {
    switch (kind) {
      case 'string': return prop.kind !== 'number' && prop.kind !== 'boolean' && prop.kind !== 'action' && prop.kind !== 'json';
      case 'number': return prop.kind === 'number';
      case 'boolean': return prop.kind === 'boolean';
      case 'json': return prop.kind === 'json';
      case 'numbers': return prop.kind === 'json';
      case 'action': return prop.kind === 'action';
      default: return false;
    }
  }

  function fromDefault(row) {
    switch (row.kind) {
      case 'string': return {kind: 'string', value: typeof row.default === 'string' ? row.default : ''};
      case 'number': return {kind: 'number', value: isNumber(row.default) ? row.default : NaN};
      case 'boolean': return {kind: 'boolean', value: row.default === true};
      case 'action': return {kind: 'action', slot: -1};
      default: return {kind: 'json', value: row.default === undefined ? null : row.default};
    }
  }

  /** Exact-kind values only: a supplied prop must be declared, a missing one takes its default. */
  function resolve(component, node, declared) {
    const supplied = new Map();
    for (const prop of propsOf(node)) {
      const row = declared.byName.get(prop.name);
      if (row === undefined) fail('PROP_FAULT', component, prop.name, null, 'undeclared');
      if (!kindMatches(row.kind, prop)) fail('PROP_FAULT', component, prop.name, null, 'kind');
      supplied.set(prop.name, valueOf(component, prop));
    }
    const values = new Map();
    for (const row of declared.rows) {
      if (supplied.has(row.name)) { values.set(row.name, supplied.get(row.name)); continue; }
      if (Object.hasOwn(row, 'default')) { values.set(row.name, fromDefault(row)); continue; }
      if (row.optional === true) continue;
      fail('PROP_FAULT', component, row.name, null, 'unbound');
    }
    return values;
  }

  function arrayValue(values, name) {
    const value = values.get(name);
    return value !== undefined && value.kind === 'json' && Array.isArray(value.value) ? value.value : null;
  }

  function numericValue(values, name) {
    const value = values.get(name);
    return value !== undefined && value.kind === 'number' ? value.value : null;
  }

  function walk(node, card, counters, pack) {
    const name = componentName(node);
    if (ROOTS.has(name)) {
      for (const child of childrenOf(node)) walk(childNode(name, child), card, counters, pack);
      return;
    }
    const declared = pack.declarations.get(name);
    if (declared === undefined) fail('PROP_FAULT', name, null, null, 'undeclared');
    const values = resolve(name, node, declared);
    // Pattern syntax is the shared ECMAScript mechanism; an unparseable expression rejects here, before
    // the candidate can commit, exactly as the renderer would refuse to paint it.
    if (name === 'TextField') {
      const pattern = values.get('pattern');
      if (pattern !== undefined && pattern.kind === 'string' && pattern.value !== '' &&
          !JSON.parse(textPattern(pattern.value, '')).valid)
        fail('PROP_FAULT', name, 'pattern', null, 'invalid ECMAScript Unicode expression');
    }
    if (name === 'TimeField' || name === 'ClockText' || name === 'DateField') {
      const prop = name === 'ClockText' ? 'minutes' : 'value';
      const value = numericValue(values, prop);
      if (value === null || !Number.isFinite(value) || value !== Math.floor(value) ||
          (name !== 'DateField' && (value < 0 || value > 1439)))
        fail('PROP_FAULT', name, prop, null, 'invalid shared temporal domain');
    }
    if (card) {
      if (!pack.components.has(name)) fail('CARD_FAULT', name, null, null, 'component is outside the card profile');
      // Every issued event a node binds must be listed by the card profile; a declared but unlisted
      // event is refused rather than rendered with a silently dropped handler.
      for (const [prop, value] of values) {
        if (value.kind === 'action' && !pack.events.has(name + '.' + prop))
          fail('CARD_FAULT', name, prop, null, 'event is outside the card profile');
      }
      const spin = numericValue(values, 'spinPeriodMs');
      if (name === 'Path' && spin !== null && spin !== 0)
        fail('CARD_FAULT', name, 'spinPeriodMs', null, 'unsupported prop for the card target');
    }
    for (const [prop, value] of values) {
      const row = declared.byName.get(prop);
      if (row === undefined || row.kind !== 'number' || value.kind !== 'number') continue;
      presentationFault(name, prop, value.value);
    }
    // A tab label is a string and one label is required per child; any other item is refused before the
    // selection rule, because string extraction would otherwise leave fewer labels than children.
    if (name === 'Tabs') {
      const labels = values.get('labels');
      const items = labels !== undefined && labels.kind === 'json' ? labels.value : null;
      if (!Array.isArray(items) || items.some(item => typeof item !== 'string'))
        fail('PROP_FAULT', name, 'labels', null, 'labels must be an array of strings');
    }
    for (const [prop, value] of values) {
      const row = declared.byName.get(prop);
      if (row === undefined) continue;
      if (row.kind === 'number' || row.kind === 'json' || row.kind === 'numbers')
        classFault(name, node, values, prop, value, row.kind, pack);
    }
    styleFault(name, values);
    // Path syntax is the shared SVG grammar; geometry, variants, colours, counts and depth are decided
    // here before the frame can commit.
    if (name === 'Surface') {
      const shapes = values.get('shapes');
      const issue = shapesFault(shapes !== undefined && shapes.kind === 'json' ? shapes.value : undefined);
      if (issue !== null) fail('PROP_FAULT', name, 'shapes', null, issue);
    }
    if (card) cardLimits(name, values, counters, pack);
    for (const child of childrenOf(node)) walk(childNode(name, child), card, counters, pack);
  }

  /** The card profile's picture, Motion and native Timer bounds; a violation faults, never truncates. */
  function cardLimits(name, values, counters, pack) {
    if (name === 'Surface' || name === 'Sparkline') {
      counters.pictures++;
      if (counters.pictures > pack.limits.pictures)
        fail('CARD_FAULT', name, name === 'Surface' ? null : 'series', null, 'picture limit', pack.limits.pictures);
      if (name === 'Surface') {
        const count = shapeCount(values.get('shapes') !== undefined && values.get('shapes').kind === 'json' ? values.get('shapes').value : undefined);
        if (count > pack.limits.shapesPerPicture)
          fail('CARD_FAULT', name, 'shapes', null, 'shape limit per picture', pack.limits.shapesPerPicture);
      } else {
        const points = (arrayValue(values, 'series') || []).length;
        const second = (arrayValue(values, 'series2') || []).length;
        if (points + second > pack.limits.shapesPerPicture)
          fail('CARD_FAULT', name, 'series', null, 'shape limit per picture', pack.limits.shapesPerPicture);
      }
    }
    if (name === 'Motion') {
      counters.motions++;
      if (counters.motions > pack.limits.motions)
        fail('CARD_FAULT', name, null, null, 'motion limit', pack.limits.motions);
    }
    if (name === 'Timer') {
      counters.timers++;
      if (counters.timers > pack.limits.nativeTimers)
        fail('CARD_FAULT', name, null, null, 'native timer limit', pack.limits.nativeTimers);
    }
  }

  /** The shared numeric presentation field domain: a padding count or a fixed decimal count. */
  function presentationFault(component, prop, value) {
    const valid = prop === 'decimals'
        ? Number.isFinite(value) && (value === -1 || (value === Math.floor(value) && value >= 0 && value <= 6))
        : prop === 'minimumDigits'
            ? Number.isFinite(value) && value === Math.floor(value) && value >= 1
            : null;
    if (valid === false) fail('PROP_FAULT', component, prop, null, 'presentation');
  }

  function classFault(component, node, values, prop, value, kind, pack) {
    const propClass = pack.constraints.get(component + '.' + prop) || 'finite';
    const number = value.kind === 'number' ? value.value : null;
    const fault = detail => fail('PROP_FAULT', component, prop, propClass, detail);
    switch (propClass) {
      case 'finite': {
        if (number !== null) { if (!Number.isFinite(number)) fault(null); return; }
        if (kind !== 'numbers') return;
        const items = value.kind === 'json' && Array.isArray(value.value) ? value.value : null;
        if (items === null) { fault('expected a number array'); return; }
        for (const item of items) if (!isNumber(item) || !Number.isFinite(item)) { fault(null); return; }
        return;
      }
      case 'nonnegative':
        if (!(number !== null && Number.isFinite(number) && number >= 0)) fault(null);
        return;
      case 'positive':
        if (!(number !== null && Number.isFinite(number) && number > 0)) fault(null);
        return;
      case 'integral-index':
        integralFault(component, node, values, prop, number, fault);
        return;
      case 'chart': {
        if (number === null || !Number.isFinite(number)) { fault(null); return; }
        const series = arrayValue(values, 'series') || [];
        const second = arrayValue(values, 'series2') || [];
        if (series.length === 0 && second.length === 0) return;
        const minimum = numericValue(values, 'minimum');
        if (!(number > (minimum === null ? 0 : minimum))) fault('maximum must exceed minimum');
        return;
      }
      case 'range': {
        // The declared numeric range of a numeric input: the upper bound is finite and is never below
        // the node's own minimum. The renderer's painter hands the pair to Compose's Slider, which
        // refuses a start above its endInclusive, so an inverted range is decided here, before state,
        // tree, version or an effect can commit, instead of trapping the surface at paint time.
        if (number === null || !Number.isFinite(number)) { fault(null); return; }
        const minimum = numericValue(values, 'minimum');
        if (minimum !== null && Number.isFinite(minimum) && number < minimum)
          fault('maximum must not be below minimum');
        return;
      }
      case 'pose': {
        const entries = value.kind === 'json' && Array.isArray(value.value) ? value.value : null;
        if (entries === null) { fault(null); return; }
        if (entries.length !== 8) { fault('pose requires exactly eight entries'); return; }
        for (const entry of entries) if (!isNumber(entry) || !Number.isFinite(entry)) { fault(null); return; }
        const opacity = entries[7];
        if (opacity < 0 || opacity > 1) fault('pose opacity must be in [0, 1]');
        return;
      }
      case 'display-clamp':
        // Over-range progress is clamped by the painter; it is never an ingress fault. A nonfinite value
        // is not a clamp: the wire form cannot carry it, so admitting it would publish a tree the
        // renderer's decoder rejects before it can paint.
        if (number !== null && !Number.isFinite(number)) fault('progress value must be finite');
        return;
      // A class this mechanism does not decide is not an unclassed prop: the issued class set is
      // closed, so an unknown one is a lost rule and the whole projection is refused rather than
      // admitting the value unvalidated.
      default:
        fail('ADMISSION_PACK', '', null, null, 'unknown prop constraint class ' + propClass);
    }
  }

  function integralFault(component, node, values, prop, number, fault) {
    if (number === null || !Number.isFinite(number) || number !== Math.floor(number)) { fault(null); return; }
    if (prop === 'step') { if (!(number > 0)) fault('step must be a positive integer'); return; }
    if (prop === 'selected' && component === 'Select') {
      const labels = arrayValue(values, 'labels');
      if (labels === null) { fault(null); return; }
      if (labels.length === 0) { if (number !== -1) fault('selected must be -1 when labels are empty'); return; }
      if (!(number >= 0 && number < labels.length)) fault('selected must index the labels');
      return;
    }
    if (prop === 'selected' && component === 'Tabs') {
      const labels = arrayValue(values, 'labels');
      if (labels === null) { fault(null); return; }
      if (number < 0 || number >= labels.length) fault('selected must index the labels');
      else if (labels.length !== childrenOf(node).length) fault('one label per child is required');
      return;
    }
    if (prop === 'columns' && component === 'Table') {
      if (number < 0) { fault(null); return; }
      const headers = arrayValue(values, 'headers');
      if (headers !== null && headers.length !== number) { fault('columns must equal the headers length'); return; }
      for (const child of childrenOf(node)) {
        const row = childNode(component, child);
        const cellsProp = propsOf(row).find(field => field.name === 'cells');
        if (cellsProp === undefined) continue;
        const cells = valueOf(componentName(row), cellsProp);
        if (cells.kind === 'json' && Array.isArray(cells.value) && cells.value.length !== number) {
          fault("columns must equal every row's cell count");
          return;
        }
      }
      return;
    }
    if (prop === 'columns') { if (!(number >= 0)) fault(null); return; }
  }

  function styleFault(component, values) {
    const icon = values.get('icon');
    if (icon !== undefined && icon.kind === 'string' && !ICONS.has(icon.value) && icon.value !== '')
      fail('PROP_FAULT', component, 'icon', null, 'unknown icon ' + icon.value);
    if (component === 'Icon') {
      const name = values.get('name');
      if (name !== undefined && name.kind === 'string' && name.value !== '' && !ICONS.has(name.value))
        fail('PROP_FAULT', component, 'name', null, 'unknown icon ' + name.value);
    }
    if (component === 'Tile') {
      const span = numericValue(values, 'span');
      if (span !== null && span !== 1 && span !== 2) fail('PROP_FAULT', component, 'span', null, 'span must be 1 or 2');
    }
  }

  // ------------------------------------------------------------------ graphics shapes

  const MAX_SHAPES = 4000;
  const MAX_DEPTH = 12;
  const MAX_POINT_NUMBERS = 2000;
  const COLOUR = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/;
  const ALIGN = new Set(['left', 'center', 'right']);
  const ARITY = {'M': 2, 'L': 2, 'C': 6, 'Q': 4, 'A': 7, 'Z': 0};
  const VARIANTS = {
    rect: ['x', 'y', 'width', 'height'],
    roundRect: ['x', 'y', 'width', 'height', 'radius'],
    circle: ['x', 'y', 'radius'],
    arc: ['x', 'y', 'radius', 'startDegrees', 'sweepDegrees', 'lineWidth'],
    line: ['x1', 'y1', 'x2', 'y2', 'lineWidth'],
    polyline: ['points', 'lineWidth'],
    polygon: ['points'],
    path: ['commands'],
    strokePath: ['commands', 'lineWidth'],
    text: ['x', 'y', 'size', 'align', 'text', 'bold'],
    group: ['x', 'y', 'rotation', 'scale', 'alpha', 'shapes'],
  };
  const OPTIONAL = {
    rect: new Set(['x', 'y', 'width', 'height', 'color']),
    roundRect: new Set(['x', 'y', 'width', 'height', 'radius', 'color']),
    circle: new Set(['x', 'y', 'radius', 'color']),
    arc: new Set(['x', 'y', 'radius', 'startDegrees', 'sweepDegrees', 'lineWidth', 'color']),
    line: new Set(['x1', 'y1', 'x2', 'y2', 'lineWidth', 'color']),
    polyline: new Set(['points', 'lineWidth', 'color']),
    polygon: new Set(['points', 'color']),
    path: new Set(['commands', 'color']),
    strokePath: new Set(['commands', 'lineWidth', 'color']),
    text: new Set(['x', 'y', 'size', 'align', 'text', 'bold', 'color']),
    group: new Set(['x', 'y', 'rotation', 'scale', 'alpha', 'shapes']),
  };

  /**
   * The renderer's whitespace class, not JavaScript's: Kotlin's Char.isWhitespace counts the C0
   * separators and every Unicode space character - including the non-breaking spaces U+00A0, U+2007
   * and U+202F, which \s also matches - and excludes the byte-order mark, which \s includes. A path
   * this boundary admits is a path the renderer accepts, so a committed frame is never refused at
   * paint time (RendererRulesTest.kt pins the same characters against GraphicsValidation).
   */
  function isSpace(character) {
    const code = character.charCodeAt(0);
    if (code === 0x1c || code === 0x1d || code === 0x1e || code === 0x1f) return true;
    if (code === 0xfeff) return false;
    return /\s/.test(character);
  }

  /** One frame of shapes: variants, required and optional fields, colours, points, counts and depth. */
  function shapesFault(value) {
    if (!Array.isArray(value)) return value === undefined ? 'shapes missing' : 'shapes must be an array';
    const counted = {value: 0};
    return visitShapes(value, 0, counted);
  }

  /** Shape count of a validated frame: one shape per entry, groups included. */
  function shapeCount(value) {
    if (!Array.isArray(value)) return 0;
    let count = 0;
    const walk = list => {
      for (const item of list) {
        count++;
        if (item !== null && typeof item === 'object' && Array.isArray(item.shapes)) walk(item.shapes);
      }
    };
    walk(value);
    return count;
  }

  /** Absolute SVG path data with the exact parameter count each command requires. */
  function commandIssue(data) {
    let index = 0, command = null, expected = 0, supplied = 0, first = null;
    const isDigit = character => character >= '0' && character <= '9';
    while (index < data.length) {
      const character = data[index];
      if (isSpace(character) || character === ',') { index++; continue; }
      if (/[A-Za-z]/.test(character)) {
        if (!Object.hasOwn(ARITY, character)) return 'must be absolute M/L/C/Q/A/Z';
        // SVG repeats a command's coordinate sets without repeating the letter, but a command letter
        // always carries at least one complete set.
        if (command !== null && (supplied < expected || (expected === 0 && supplied !== 0) ||
            (expected > 0 && supplied % expected !== 0)))
          return 'command ' + command + ' requires at least one complete set of ' + expected + ' numbers';
        if (first === null) first = character;
        command = character;
        expected = ARITY[character];
        supplied = 0;
        index++;
        continue;
      }
      if (isDigit(character) || character === '+' || character === '-' || character === '.') {
        if (command === null) return 'must start with a command';
        if (expected === 0) return 'command ' + command + ' takes no numbers';
        const start = index;
        if (character === '+' || character === '-') index++;
        while (index < data.length && (isDigit(data[index]) || data[index] === '.' || data[index] === 'e' ||
               data[index] === 'E' || ((data[index] === '+' || data[index] === '-') && (data[index - 1] === 'e' || data[index - 1] === 'E'))))
          index++;
        const number = Number(data.substring(start, index));
        if (Number.isNaN(number)) return 'has an invalid number';
        if (!Number.isFinite(number)) return 'has a nonfinite number';
        if (command === 'A') {
          const position = supplied % 7;
          if (position < 2 && number < 0) return 'has a negative arc radius';
          if ((position === 3 || position === 4) && number !== 0 && number !== 1) return 'has an arc flag other than 0 or 1';
        }
        supplied++;
        continue;
      }
      return "has an unsupported character '" + character + "'";
    }
    if (command !== null && (supplied < expected || (expected === 0 && supplied !== 0) ||
        (expected > 0 && supplied % expected !== 0)))
      return 'command ' + command + ' requires at least one complete set of ' + expected + ' numbers';
    if (first !== 'M') return 'must start with M';
    return null;
  }

  function visitShapes(items, depth, counted) {
    if (depth > MAX_DEPTH) return 'depth exceeds ' + MAX_DEPTH;
    for (const item of items) {
      counted.value++;
      if (counted.value > MAX_SHAPES) return 'more than ' + MAX_SHAPES + ' shapes';
      if (item === null || typeof item !== 'object' || Array.isArray(item)) return 'shape must be an object';
      const tag = item.tag;
      if (typeof tag !== 'string') return 'tag is required';
      const required = Object.hasOwn(VARIANTS, tag) ? VARIANTS[tag] : null;
      if (required === null) return 'unsupported tag ' + tag;
      const allowed = OPTIONAL[tag];
      for (const name of required) if (!Object.hasOwn(item, name)) return tag + ' requires ' + name;
      for (const name of Object.keys(item)) if (name !== 'tag' && !allowed.has(name)) return tag + ' does not accept ' + name;
      if (tag !== 'group') {
        if (typeof item.color !== 'string' || !COLOUR.test(item.color))
          return tag + ' requires a #RRGGBB or #RRGGBBAA colour';
        for (const name of ['width', 'height', 'radius', 'size', 'scale']) {
          if (!Object.hasOwn(item, name)) continue;
          if (!isNumber(item[name]) || item[name] < 0) return tag + ' ' + name + ' must be finite and nonnegative';
        }
      }
      for (const name of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'startDegrees', 'sweepDegrees', 'rotation']) {
        if (!Object.hasOwn(item, name)) continue;
        if (!isNumber(item[name]) || !Number.isFinite(item[name])) return tag + ' ' + name + ' must be finite';
      }
      if (Object.hasOwn(item, 'lineWidth') && isNumber(item.lineWidth) && item.lineWidth < 0)
        return tag + ' lineWidth must be nonnegative';
      if (required.includes('lineWidth') && (!isNumber(item.lineWidth) || item.lineWidth < 0))
        return tag + ' lineWidth must be a finite nonnegative number';
      if (Object.hasOwn(item, 'alpha') && isNumber(item.alpha) && (item.alpha < 0 || item.alpha > 1))
        return tag + ' alpha must be in [0, 1]';
      if (tag === 'group') {
        if (!isNumber(item.alpha) || item.alpha < 0 || item.alpha > 1) return 'group alpha must be in [0, 1]';
        if (!isNumber(item.scale) || item.scale < 0) return 'group scale must be nonnegative';
        if (!Array.isArray(item.shapes)) return 'group requires shapes';
        const issue = visitShapes(item.shapes, depth + 1, counted);
        if (issue !== null) return issue;
        continue;
      }
      if (Object.hasOwn(item, 'points') && Array.isArray(item.points)) {
        if (item.points.length % 2 !== 0) return tag + ' points must be coordinate pairs';
        // The bound counts point coordinates: the spec's 1000 points are two numbers each.
        if (item.points.length > MAX_POINT_NUMBERS) return tag + ' exceeds ' + (MAX_POINT_NUMBERS / 2) + ' points';
        for (const number of item.points) if (!isNumber(number) || !Number.isFinite(number)) return tag + ' points must be finite';
      }
      if (required.includes('points') && !Array.isArray(item.points)) return tag + ' requires points';
      if (Object.hasOwn(item, 'commands') && typeof item.commands === 'string') {
        const issue = commandIssue(item.commands);
        if (issue !== null) return tag + ' commands ' + issue;
      }
      if (required.includes('commands') && typeof item.commands !== 'string') return tag + ' requires commands';
      if (tag === 'text') {
        if (typeof item.align !== 'string' || !ALIGN.has(item.align)) return 'text align must be left, center or right';
        if (typeof item.text !== 'string') return 'text requires text';
        if (typeof item.bold !== 'boolean') return 'text requires bold';
        if (!isNumber(item.size) || item.size < 0) return 'text size must be finite and nonnegative';
      }
    }
    return null;
  }

  /** The candidate verdict. A Session without its issued metadata fails closed, never unvalidated. */
  return function validateRendererValues(tree) {
    const contract = globalThis.rendererContract;
    if (contract === null || contract === undefined || contract.constraints === undefined ||
        contract.card === undefined || contract.declarations === undefined)
      fail('ADMISSION_PACK', '', null, null, 'renderer pack metadata is not issued for this Session');
    if (tree === null || typeof tree !== 'object' || Array.isArray(tree))
      fail('PROP_FAULT', '', null, null, 'candidate tree is absent');
    const target = contract.target;
    if (typeof target !== 'string' || !TARGETS.has(target))
      fail('ADMISSION_TARGET', '', null, null, 'issued target is absent or outside screen, chat and card');
    const pack = packFacts(contract);
    walk(tree, target === 'card', {pictures: 0, motions: 0, timers: 0}, pack);
    return true;
  };
})();
// The facts are sealed by the contract script; the verdict is sealed here, before any compiled module
// runs, so nothing loaded afterwards can replace it and keep admitting candidates.
Object.defineProperty(globalThis, 'validateRendererValues', {value: globalThis.validateRendererValues, writable: false, configurable: false});

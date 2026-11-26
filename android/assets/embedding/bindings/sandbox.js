"use strict";
// Host-owned CommonJS environment. Modules are supplied only by local compilation.
(() => {
  let nextId = 1;
  const outgoing = [];
  const pending = new Map();
  // Requests counted by the containment pool; admitted update waits are not among them.
  let pooled = 0;
  globalThis.dealCapabilities = Object.freeze({
    // Update waits are admitted by compiled controller policy; this pool bounds every other
    // native request, so an admitted wait can never starve control or observation transport.
    request: (module, functionName, args) => new Promise((resolve, reject) => {
      const counted = !(module === "embedding" && functionName === "next");
      if (counted && pooled >= 8) { reject({code: "LIMIT", message: "Too many pending requests"}); return; }
      if (counted) pooled += 1;
      const id = nextId++;
      pending.set(id, {resolve, reject, owner:globalThis.protocolInvocation, counted});
      outgoing.push({id, module, function: functionName, args});
    }),
    notify: (module, functionName, args) => { outgoing.push({id: 0, module, function: functionName, args}); },
    take: () => JSON.stringify(outgoing.splice(0)),
    cancelOwner: owner => {
      const ids=[];
      for(const [id,operation] of pending)if(operation.owner===owner){
        pending.delete(id);if(operation.counted)pooled-=1;ids.push(id);operation.reject({code:'DETACHED',message:'Observer detached'});
      }
      for(let i=outgoing.length-1;i>=0;i--)if(ids.includes(outgoing[i].id))outgoing.splice(i,1);
      return JSON.stringify(ids);
    },
    deliver: replies => {
      for (const reply of replies) {
        const operation = pending.get(reply.id);
        if (!operation) continue;
        pending.delete(reply.id);if(operation.counted)pooled-=1;
        if (reply.ok) operation.resolve(reply.value);
        else operation.reject(reply.error);
      }
      return "";
    }
  });
})();
// Saved bytes stay data: the host passes a quoted literal, and an unparseable record is refused
// with a typed incompatibility instead of a syntax error inside the isolate.
globalThis.dealPersisted = function(text) {
  try { return JSON.parse(text); } catch (error) { throw Error('PERSISTENCE_RECORD: saved record is not valid JSON'); }
};
// Public probes: the isolate owns the destination, redirect, media type, encoding, depth and selection
// policy of a credential-free HTTPS JSON read; the host supplies only the socket and its byte/time
// bounds. A probe can therefore reach no destination this binding did not validate, and no later
// response can widen what it read.
(() => {
  let nextId = 1;
  const outgoing = [];
  const pending = new Map();
  // The specification bounds one probe set at three URLs, so up to three reads may be in flight: two live
  // sections of one surface never fail one another, and a fourth concurrent read is refused instead.
  const maximumPending = 3;
  globalThis.dealProbe = Object.freeze({
    request: (url, maximumBytes, timeoutMs) => new Promise((resolve, reject) => {
      if (pending.size >= maximumPending) { reject({code: 'LIMIT', message: 'Too many pending probes'}); return; }
      const id = nextId++;
      pending.set(id, {resolve, reject});
      outgoing.push({id, url, maximumBytes, timeoutMs});
    }),
    take: () => JSON.stringify(outgoing.splice(0)),
    deliver: replies => {
      for (const reply of replies) {
        const operation = pending.get(reply.id);
        if (!operation) continue;
        pending.delete(reply.id);
        if (reply.ok) operation.resolve(reply.value);
        else operation.reject(reply.error);
      }
      return '';
    },
  });
})();
// The probe module issued to composed source. Every rule below is the specification's, not the
// transport's: the host reports what it connected to and this binding refuses anything else.
globalThis.dealProbeModule = function dealProbeModule(rt) {
  const configured = () => {
    const given = globalThis.dealProbeLimits || {};
    const whole = (value, fallback) => Number.isInteger(value) && value > 0 ? value : fallback;
    return Object.freeze({ bytes: whole(given.bytes, 65536), depth: whole(given.depth, 32), characters: whole(given.characters, 6000), timeoutMs: whole(given.timeoutMs, 5000), paths: whole(given.paths, 3) });
  };
  // A refusal is a classified runtime error, so composed source reads `error.name` as the probe code the
  // specification classifies (destination, status, media type, encoding, depth, characters, value).
  function fail(code, message) { throw rt.errorValue(code, message); }
  // A public destination is a globally routable unicast address: loopback, link-local, the private
  // IPv4 ranges, the carrier-grade NAT sharing range, the benchmark ranges, the IPv4 documentation
  // (TEST-NET) ranges, the IETF protocol-assignment block 192.0.0.0/24 with the 6to4 relay anycast block,
  // unique-local and multicast IPv6 and the non-global IPv6 blocks below are all refused. A probe cannot
  // use the assignment block's anycast services, so the whole block is refused as policy.
  function publicIPv4(text) {
    const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(text);
    if (match === null) return null;
    const parts = match.slice(1).map(Number);
    if (parts.some(part => part > 255)) return false;
    const [a, b, c] = parts;
    if (a === 0 || a === 10 || a === 127) return false;
    if (a === 169 && b === 254) return false;
    if (a === 172 && b >= 16 && b <= 31) return false;
    if (a === 192 && b === 168) return false;
    if (a === 192 && b === 0 && c <= 2) return false;
    if (a === 100 && b >= 64 && b <= 127) return false;
    if (a === 198 && (b === 18 || b === 19)) return false;
    if (a === 198 && b === 51 && c === 100) return false;
    if (a === 203 && b === 0 && c === 113) return false;
    if (a === 192 && b === 88 && c === 99) return false;
    if (a >= 224) return false;
    return true;
  }
  // The non-public forms this rule names outside global unicast: the unspecified and loopback addresses,
  // IPv4-mapped and IPv4-compatible forms, unique local fc00::/7, link local fe80::/10, site local
  // fec0::/10, multicast ff00::/8 and the NAT64 well-known prefix. The blocks inside global unicast that a
  // probe cannot reach are refused by the range comment below.
  function publicIPv6(text) {
    let value = text.toLowerCase();
    if (value.startsWith('[') && value.endsWith(']')) value = value.substring(1, value.length - 1);
    // The literal shape comes first: one to four hex digits per group, eight groups, and at most one "::"
    // standing for the rest. A malformed authority is refused here instead of being read as some prefix of
    // an address the socket layer would then fail to parse.
    const groups = value.split(':');
    const compression = value.indexOf('::');
    const hex = group => /^[0-9a-f]{1,4}$/.test(group);
    if (compression < 0) {
      if (value.startsWith(':') || value.endsWith(':') || groups.length !== 8) return false;
      if (!groups.every(hex)) return false;
    } else {
      if (value[compression + 2] === ':') return false;
      if (value.indexOf('::', compression + 2) >= 0) return false;
      if (value.startsWith(':') && !value.startsWith('::')) return false;
      if (value.endsWith(':') && !value.endsWith('::')) return false;
      const present = groups.filter(group => group !== '');
      if (present.length > 7 || !present.every(hex)) return false;
    }
    // Global unicast is 2000::/3, so the range rule refuses by range instead of by the spelling this file
    // happens to name: the second hextet of 2001:: is refused when it is 0x0000 (Teredo), 0x0002
    // (benchmarking, refused as its whole 2001:2::/32), 0x0010-0x003f (the ORCHID and DET spans
    // 2001:10::/28, 2001:20::/28 and 2001:30::/28) or 0x0db8 (documentation), and 3fff::/16 is refused whole,
    // which contains documentation's 3fff::/20. The rest of 2001::/23 (second hextets 0x0001, 0x0003-0x000f
    // and 0x0040-0x01ff) is left to the transport, like an unresolvable name.
    const head = Number('0x' + (groups[0] || '0'));
    const next = Number('0x' + (groups[1] || '0'));
    if (!Number.isInteger(head) || !Number.isInteger(next)) return false;
    if ((head & 0xe000) !== 0x2000) return false;
    if (head === 0x3fff) return false;
    if (head === 0x2001 && (next === 0x0000 || next === 0x0002 || (next >= 0x0010 && next <= 0x003f))) return false;
    return !(head === 0x2001 && next === 0x0db8);
  }
  function publicAddress(text) {
    if (typeof text !== 'string' || text === '') return false;
    const ipv4 = publicIPv4(text);
    if (ipv4 !== null) return ipv4;
    if (text.includes(':')) return publicIPv6(text);
    return false;
  }
  // The destination the source names: credential-free HTTPS on a public host. The scheme, the
  // authority and the host are validated before any request exists, so no caller can widen the
  // transport with userinfo, a fragment, a loopback name or a private address literal. A bracketed IPv6
  // authority is unwrapped before its host is classified.
  function destination(url) {
    if (typeof url !== 'string' || !url.toLowerCase().startsWith('https://')) fail('PROBE_DESTINATION', 'A public probe requires HTTPS');
    const rest = url.substring(8);
    let end = rest.length;
    const slash = rest.indexOf('/');
    if (slash >= 0) end = slash;
    const query = rest.indexOf('?');
    if (query >= 0 && query < end) end = query;
    const authority = rest.substring(0, end);
    if (authority === '' || authority.includes('@') || rest.includes('#')) fail('PROBE_DESTINATION', 'A public probe requires a bare public authority');
    let host = '';
    if (authority.startsWith('[')) {
      const close = authority.indexOf(']');
      if (close < 0) fail('PROBE_DESTINATION', 'A public probe requires a public host');
      host = authority.substring(0, close + 1).toLowerCase();
      if (!port(authority.substring(close + 1))) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    } else {
      const colon = authority.indexOf(':');
      host = (colon < 0 ? authority : authority.substring(0, colon)).toLowerCase();
      if (!port(colon < 0 ? '' : authority.substring(colon))) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    }
    if (host === '' || host === 'localhost' || host.endsWith('.localhost')) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    // The host is classified as an address only when it is one: a public name that happens to begin with
    // an address-looking prefix is a name, and the transport's own address classification decides what it
    // resolves to. A final label that begins with a digit is not a name: the transport's URL parser reads
    // that authority as an address and reports no host, and a bare integer authority is the legacy
    // single-number IPv4 spelling this build refuses as policy. A trailing dot is a server name the TLS
    // handshake refuses. All three are refused here rather than left to a read that cannot happen.
    if (host.endsWith('.')) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    if (host.startsWith('[')) { if (!publicIPv6(host)) fail('PROBE_DESTINATION', 'A public probe requires a public host'); return; }
    const ipv4 = publicIPv4(host);
    if (ipv4 === false) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    // A dotted-quad literal is an address, not a name: only a name is held to the label rules.
    if (ipv4 === true) return;
    // The labels are the ones the transport's URL parser accepts: letters, digits and internal hyphens, at
    // most 63 units each, and a final label that begins with a letter. Anything else is refused here instead
    // of being handed to a parser that reports no host for it.
    const labels = host.split('.');
    for (const label of labels) {
      if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) fail('PROBE_DESTINATION', 'A public probe requires a public host');
    }
    if (/^[0-9]/.test(labels[labels.length - 1])) fail('PROBE_DESTINATION', 'A public probe requires a public host');
  }
  // The port spelling both authority forms admit: absent, or a colon and one to five digits inside the
  // socket range the transport can connect to.
  function port(text) {
    if (text === '') return true;
    if (!/^:[0-9]{1,5}$/.test(text)) return false;
    const value = Number(text.substring(1));
    return value >= 1 && value <= 65535;
  }
  // Strict UTF-8, exactly as the specification requires of the response encoding: an overlong form, a
  // surrogate, a value past U+10FFFF and a truncated sequence are all a refused read.
  function decode(bytes) {
    const units = [];
    let index = 0;
    while (index < bytes.length) {
      const first = bytes.charCodeAt(index);
      if (first < 0x80) { units.push(first); index += 1; continue; }
      let need = 0; let code = 0; let minimum = 0;
      if ((first & 0xe0) === 0xc0) { need = 1; code = first & 0x1f; minimum = 0x80; }
      else if ((first & 0xf0) === 0xe0) { need = 2; code = first & 0x0f; minimum = 0x800; }
      else if ((first & 0xf8) === 0xf0) { need = 3; code = first & 0x07; minimum = 0x10000; }
      else fail('PROBE_ENCODING', 'The probe response is not valid UTF-8');
      index += 1;
      let step = 0;
      while (step < need) {
        if (index >= bytes.length) fail('PROBE_ENCODING', 'The probe response is not valid UTF-8');
        const next = bytes.charCodeAt(index);
        if ((next & 0xc0) !== 0x80) fail('PROBE_ENCODING', 'The probe response is not valid UTF-8');
        code = (code << 6) | (next & 0x3f); index += 1; step += 1;
      }
      if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) fail('PROBE_ENCODING', 'The probe response is not valid UTF-8');
      if (code >= 0x10000) { const offset = code - 0x10000; units.push(0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff)); }
      else units.push(code);
    }
    const parts = [];
    for (let start = 0; start < units.length; start += 4096) parts.push(String.fromCharCode.apply(null, units.slice(start, start + 4096)));
    return parts.join('');
  }
  // The media type is the declared JSON one; a charset is admitted only as the UTF-8 family the decoder
  // above actually applies, so a differently encoded document is refused instead of reinterpreted.
  function mediaType(declared) {
    const text = typeof declared === 'string' ? declared : '';
    const sections = text.split(';');
    const base = sections[0].trim().toLowerCase();
    if (base !== 'application/json' && !(base.startsWith('application/') && base.endsWith('+json'))) fail('PROBE_MEDIA_TYPE', 'A public probe requires a JSON media type');
    for (const section of sections.slice(1)) {
      const key = section.split('=');
      if (key[0].trim().toLowerCase() !== 'charset') continue;
      const charset = (key[1] || '').trim().toLowerCase().replace(/"/g, '');
      if (charset !== 'utf-8' && charset !== 'utf8' && charset !== 'us-ascii') fail('PROBE_ENCODING', 'A public probe requires UTF-8');
    }
  }
  // The nesting bound is walked iteratively with an early cutoff, so a deeply nested response is refused
  // without driving the isolate's call stack.
  function depthOf(value, limit) {
    const stack = [{value: value, depth: 0}];
    let deepest = 0;
    while (stack.length > 0) {
      const entry = stack.pop();
      const current = entry.value;
      if (current === null || typeof current !== 'object') continue;
      const depth = entry.depth + 1;
      if (depth > limit) return depth;
      if (depth > deepest) deepest = depth;
      for (const member of Array.isArray(current) ? current : Object.values(current)) stack.push({value: member, depth: depth});
    }
    return deepest;
  }
  // One RFC 6901 reference token step: `~1` is `/`, `~0` is `~`, an array step is a canonical index.
  function resolve(document, path) {
    if (path === '') return document;
    if (typeof path !== 'string' || !path.startsWith('/')) fail('PROBE_PATH', 'A live path is not a JSON Pointer: ' + String(path));
    let current = document;
    for (const raw of path.substring(1).split('/')) {
      const token = raw.replace(/~1/g, '/').replace(/~0/g, '~');
      if (Array.isArray(current)) {
        if (!/^(0|[1-9][0-9]*)$/.test(token)) return undefined;
        const index = Number(token);
        if (index >= current.length) return undefined;
        current = current[index];
      } else if (current !== null && typeof current === 'object' && Object.hasOwn(current, token)) current = current[token];
      else return undefined;
    }
    return current;
  }
  async function read(url, mode, paths, asofPath) {
    const limits = configured();
    if (mode !== 'api' && mode !== 'search') fail('PROBE_MODE', 'A live source names an unknown mode: ' + String(mode));
    if (!Array.isArray(paths) || paths.length < 1 || paths.length > limits.paths) fail('PROBE_PATH', 'A probe selects one to three paths');
    if (typeof asofPath !== 'string') fail('PROBE_PATH', 'A live source observation path is text');
    destination(url);
    let reply;
    try { reply = await dealProbe.request(url, limits.bytes, limits.timeoutMs); }
    catch (error) { fail(error.code || 'PROBE_UNAVAILABLE', error.message || 'The probe request failed'); }
    if (reply === null || typeof reply !== 'object') fail('PROBE_UNAVAILABLE', 'The probe transport returned no reply');
    if (reply.status !== 200) fail('PROBE_STATUS', 'A public probe requires a successful response');
    mediaType(reply.mediaType);
    // The connected destination is the address the transport actually validated: a name that resolved
    // away from a public address refuses the read instead of being trusted because its spelling looked
    // public (DNS rebinding).
    if (!publicAddress(reply.connected)) fail('PROBE_DESTINATION', 'A public probe requires a public destination');
    if (typeof reply.bytes !== 'string') fail('PROBE_UNAVAILABLE', 'The probe transport returned no body');
    if (reply.bytes.length > limits.bytes) fail('PROBE_LIMIT', 'The probe response exceeds the configured byte bound');
    const text = decode(reply.bytes);
    if (text.length > limits.characters) fail('PROBE_CHARACTERS', 'The probe response exceeds the selected character bound');
    let document;
    try { document = JSON.parse(text); } catch (error) { fail('PROBE_JSON', 'The probe response is not JSON'); }
    if (depthOf(document, limits.depth) > limits.depth) fail('PROBE_DEPTH', 'The probe response exceeds the configured depth bound');
    const values = [];
    for (const path of paths) {
      const value = resolve(document, path);
      if (typeof value !== 'number' || !Number.isFinite(value)) fail('PROBE_VALUE', 'A selected live path does not resolve to a finite number: ' + String(path));
      values.push(value);
    }
    let asof = '';
    if (asofPath !== '') { const observed = resolve(document, asofPath); if (typeof observed === 'string' && observed !== '') asof = observed; }
    // The declared result is one JSON text: composed source decodes it into its own checked type, so no
    // carrier shape is invented across the host boundary.
    return JSON.stringify({values: values, asof: asof});
  }
  return Object.freeze({fetch: (url, mode, paths, asofPath) => read(url, mode, paths, asofPath)});
};
let dealHostContracts = Object.create(null);
globalThis.configureDealCapabilities = function(contracts) {
  const modules = Object.create(null);
  for (const contract of contracts) {
    if (Object.hasOwn(modules, contract.module)) throw Error('Ambiguous capability module');
    modules[contract.module] = contract;
  }
  dealHostContracts = Object.freeze(modules);
};
// The live Session facts compiled admission re-reads before a send and before a reply. The host
// configures them; the isolate never invents one. An unconfigured host answers the fail-closed
// default, so an unclassified or ungranted call admits nothing instead of running on a placeholder.
let dealSessionFacts = Object.freeze({lifetime:'',epochs:Object.freeze({}),granted:Object.freeze([]),classification:Object.freeze({}),confirmation:Object.freeze({}),clockTrusted:false,now:0,expiry:Object.freeze({})});
function freezeNumbers(value) {
  const map = Object.create(null);
  for (const key of Object.keys(value||{})) { const entry = value[key]; if (!Number.isInteger(entry)) throw Error('Session epoch must be an integer'); map[key] = entry; }
  return Object.freeze(map);
}
function freezeMap(value) {
  const map = Object.create(null);
  for (const key of Object.keys(value||{})) { const entry = value[key]; if (typeof entry !== 'string') throw Error('Session fact must be text'); map[key] = entry; }
  return Object.freeze(map);
}
// A published window is host configuration, so a malformed one is refused here rather than read as
// the absence of a window: the published form names exactly one bound, exactly as the compiled window
// parse requires, and an omitted entry is the only way to publish no bound. -1 is the published
// "no absolute bound", so a host that sends both keys still names one window.
// The facts arrive as JSON, so a window and its map are plain records: any other object (a Map, a Set,
// a class instance) would publish no key at all and would read as "no bound", so it is refused.
function plainRecord(value) {
  // Realm-independent: the facts may be handed in from another JavaScript realm, where a plain object
  // does not share this realm's Object.prototype, while a Map or a Set still reports its own class.
  return value !== null && typeof value === 'object' && Object.prototype.toString.call(value) === '[object Object]';
}
function freezeExpiry(value) {
  if (!plainRecord(value)) throw Error('Session expiry must be a map of windows');
  const map = Object.create(null);
  for (const key of Object.keys(value)) {
    const spec = value[key];
    if (!plainRecord(spec)) throw Error('Session expiry must be an object');
    const entry = Object.create(null);
    let bounds = 0;
    if (Object.hasOwn(spec, 'relativeMs')) {
      if (!Number.isFinite(spec.relativeMs) || spec.relativeMs < 0) throw Error('Session expiry relativeMs must be a nonnegative number');
      entry.relativeMs = spec.relativeMs; bounds++;
    }
    if (Object.hasOwn(spec, 'absolute')) {
      if (!Number.isFinite(spec.absolute) || (spec.absolute < 0 && spec.absolute !== -1)) throw Error('Session expiry absolute must be an instant or -1');
      if (spec.absolute >= 0) { entry.absolute = spec.absolute; bounds++; }
    }
    if (bounds !== 1) throw Error('Session expiry must name exactly one of relativeMs or absolute');
    map[key] = Object.freeze(entry);
  }
  return Object.freeze(map);
}
globalThis.configureDealSession = function(config) {
  const granted = Array.isArray(config.granted) ? config.granted.map(module => String(module)) : [];
  const lifetime = typeof config.lifetime === 'string' ? config.lifetime : '';
  const now = typeof config.now === 'number' && Number.isFinite(config.now) ? config.now : 0;
  // The windows are keyed by module/function. A container of another shape would silently publish no
  // window for every function, so it is refused rather than read as an empty map.
  const windows = config.expiry === undefined ? {} : config.expiry;
  // The Experience origin and the frozen-request determination are host facts, never inferred from
  // source: an absent determination is false, so it authorizes no mutation.
  const origin = typeof config.origin === 'string' ? config.origin : '';
  const requestAuthorizes = config.requestAuthorizes === true;
  const requestTarget = typeof config.requestTarget === 'string' ? config.requestTarget : '';
  dealSessionFacts = Object.freeze({lifetime:lifetime,epochs:freezeNumbers(config.epochs),granted:Object.freeze(granted),classification:freezeMap(config.classification),confirmation:freezeMap(config.confirmation),origin:origin,requestAuthorizes:requestAuthorizes,requestTarget:requestTarget,clockTrusted:config.clockTrusted === true,now:now,expiry:freezeExpiry(windows)});
  return '';
};
// A host that publishes no window answers -1 for both bounds, so the absence of a window stays
// distinguishable from a window that is the instant of admission: a relative bound of 0 expires at
// admission and an absolute instant of 0 has already passed. Neither is invented by the isolate.
function sessionExpiry(module, functionName) {
  const spec = dealSessionFacts.expiry[module + '/' + functionName];
  if (spec === undefined || spec === null || typeof spec !== 'object') return Object.freeze({relativeMs:-1,absolute:-1});
  const relativeMs = typeof spec.relativeMs === 'number' && Number.isFinite(spec.relativeMs) && spec.relativeMs >= 0 ? spec.relativeMs : -1;
  const absolute = typeof spec.absolute === 'number' && Number.isFinite(spec.absolute) && spec.absolute >= 0 ? spec.absolute : -1;
  return Object.freeze({relativeMs:relativeMs,absolute:absolute});
}
const dealSession = Object.freeze({
  epoch: module => { const value = dealSessionFacts.epochs[module]; return Number.isInteger(value) ? value : -1; },
  lifetime: () => dealSessionFacts.lifetime,
  granted: module => dealSessionFacts.granted.includes(module),
  classification: (module, functionName) => dealSessionFacts.classification[module + '/' + functionName] || '',
  confirmation: (module, functionName, argumentsJson) => dealSessionFacts.confirmation[module + '/' + functionName + '#' + argumentsJson] || '',
  origin: () => dealSessionFacts.origin,
  requestAuthorizes: () => dealSessionFacts.requestAuthorizes,
  requestTarget: () => dealSessionFacts.requestTarget,
  clockTrusted: () => dealSessionFacts.clockTrusted,
  now: () => dealSessionFacts.now,
  relativeMs: (module, functionName) => sessionExpiry(module, functionName).relativeMs,
  absolute: (module, functionName) => sessionExpiry(module, functionName).absolute
});
globalThis.dealLoad = function(factories, entryId, appModule = null) {
  const cache = Object.create(null);
  let messageBytes = 0;
  function log(value) {
    const text = String(value);
    if (messageBytes + text.length > 4096) throw new Error("Console output limit exceeded");
    messageBytes += text.length;

  }
  const process = Object.seal({
    env: Object.freeze({DEAL_DIAGNOSTICS_JSON: "1"}),
    stdout: Object.freeze({write: log}),
    stderr: Object.freeze({write: log}),
    exitCode: 0
  });
  const console = Object.freeze({
    log,
    error: log
  });
  function resolve(parent, name) {
    if (name === 'embedding/graphics' || name === 'embedding/graphics.js') return 'embedding/graphics';
    if (name === 'embedding/day' || name === 'embedding/day.js') return 'embedding/day';
    if (name === 'embedding/probe' || name === 'embedding/probe.js') return 'embedding/probe';
    // The host Session facts of compiled admission are a host-owned module, not a bundle file: the
    // generated Experience resolves it by its declared identity, exactly as it resolves the filter.
    if (name === 'embedding/session' || name === 'embedding/session.js') return 'embedding/session';
    // Deal UI's framework-owned per-Experience store: the host owns the records and the boundary,
    // the isolate only expresses the declared asynchronous surface.
    if (name === 'host/storage' || name === 'host/storage.js') return 'host/storage';
    if (typeof protocolDeclarations !== 'undefined' && Object.hasOwn(protocolDeclarations, name.replace(/\.js$/, ""))) return name.replace(/\.js$/, "");
    if (Object.hasOwn(dealHostContracts, name.replace(/\.js$/, ""))) return name.replace(/\.js$/, "");
    if (!name.startsWith("./") && !name.startsWith("../")) {
      throw new Error("Non-bundle module denied: " + name);
    }
    const parts = parent.split("/");
    parts.pop();
    for (const part of name.split("/")) {
      if (part === "" || part === ".") continue;
      if (part === "..") {
        if (!parts.length) throw new Error("Module escapes bundle");
        parts.pop();
      } else parts.push(part);
    }
    const id = parts.join("/");
    if (id === 'embedding/graphics' || id === 'embedding/day' || id === 'embedding/probe') return id;
    return id.endsWith(".js") ? id : id + ".js";
  }
  function load(id) {
    // Frozen nominal transport declarations; constructors are issued by DEAL loadHost.
    if (id === 'embedding/graphics') return Object.freeze(Object.fromEntries(['Shape','Pointer'].map(name => [name, {$kind:'class',$classname:'@$external/embedding/graphics/'+name}])));
    // Host-timezone day mechanism: one synchronous adapter reading, the isolate's own host timezone
    // applied to the current instant. It owns no policy: the composed source decides when a day is
    // captured (initialization or an action) and how it scopes a projection. A changed default
    // timezone is therefore observed by the next reading, which is what invalidates a projection.
    if (id === 'embedding/day') return Object.freeze({localDay: () => {
      const now = new Date();
      return Math.floor((now.getTime() - now.getTimezoneOffset() * 60000) / 86400000);
    }});
    // Public-probe mechanism: the transport policy lives in this binding, not in composed source, so a
    // section cannot widen a destination, a media type, an encoding or a bound.
    if (id === 'embedding/probe') { if (!Object.hasOwn(cache,id)) cache[id]={exports:dealProbeModule(load('deal/runtime.js'))}; return cache[id].exports; }
    // The host-owned Session facts of compiled admission, answered synchronously from host
    // configuration: no provider call, no isolate round trip and no snapshot can establish them.
    if (id === 'embedding/session' || id === 'embedding/session.js') return dealSession;
    // The per-Experience store is a host module: every call is one host request, so the host decides
    // which Experience's records it reads and the controller keeps system persistence elsewhere.
    if (id === 'host/storage' || id === 'host/storage.js') {
      if (!Object.hasOwn(cache, 'host/storage')) {
        function refuse(code, message) { return load('deal/runtime.js').errorValue(code, message); }
        function send(name, args) {
          return dealCapabilities.request('host/storage', name, args).catch(error => {
            throw load('deal/runtime.js').errorValue(error && error.code || 'STORAGE', error && error.message || 'Storage unavailable');
          });
        }
        cache['host/storage'] = {exports: Object.freeze({
          load: key => { if (typeof key !== 'string') throw refuse('INVALID_ARGUMENT', 'Storage key must be text'); return send('load', [key]); },
          save: (key, value) => { if (typeof key !== 'string' || typeof value !== 'string') throw refuse('INVALID_ARGUMENT', 'Storage key and value must be text'); return send('save', [key, value]); },
          remove: key => { if (typeof key !== 'string') throw refuse('INVALID_ARGUMENT', 'Storage key must be text'); return send('remove', [key]); }
        })};
      }
      return cache['host/storage'].exports;
    }
    function encode(type, value) {
      if (type.kind === 'array') return value.map(item => encode(type.element, item));
      if (type.kind === 'record') {
        const fields = Object.create(null);
        if (!(value instanceof Map) || [...value.keys()].some(key=>!type.fields.some(field=>field.name===key))) throw Error('Invalid record argument');
        for (const field of type.fields) {
          const name=field.name;
          if (!value.has(name)) {if(field.optional)continue;throw Error('Missing record argument field');}
          fields[name] = encode(field.type, value.get(name));
        }
        return fields;
      }
      return value;
    }
    function convertJson(value) {
      if (Array.isArray(value)) return value.map(convertJson);
      if (value !== null && typeof value === 'object') {
        const fields = Object.create(null);
        for (const key of Object.keys(value)) fields[key] = convertJson(value[key]);
        return load('deal/runtime.js').makeTable(fields);
      }
      return value;
    }
    function convert(type, value) {
      if (type.kind === 'json-object') return convertJson(value);
      if (type.kind === 'array') return value.map(item => convert(type.element, item));
      if (type.kind === 'record') {
        const fields = Object.create(null);
        for (const field of type.fields) if(Object.hasOwn(value,field.name))fields[field.name] = convert(field.type, value[field.name]);
        return load('deal/runtime.js').makeTable(fields);
      }
      return value;
    }
    const hostId = id.replace(/\.js$/, "");
    if (typeof protocolDeclarations !== 'undefined' && Object.hasOwn(protocolDeclarations, hostId)) {
      if (!Object.hasOwn(cache,hostId)) cache[hostId]={exports:protocolHost(hostId,protocolDeclarations[hostId],load('deal/runtime.js'))};
      return cache[hostId].exports;
    }
    if (Object.hasOwn(dealHostContracts, hostId)) {
      if (!Object.hasOwn(cache, hostId)) {
        const exports = Object.create(null);
        for (const fn of dealHostContracts[hostId].functions) exports[fn.name] = async (...args) => {
          try {
            if (args.length !== fn.parameters.length) throw Error('Invalid capability arity');
            return convert(fn.result, await dealCapabilities.request(hostId, fn.name, args.map((value, i) => encode(fn.parameters[i].type, value))));
          }
          catch (error) { throw load('deal/runtime.js').errorValue(error.code || 'BAD_RESPONSE', error.message || 'Capability failed'); }
        };
        cache[hostId] = {exports: Object.freeze(exports)};
      }
      return cache[hostId].exports;
    }
    if (Object.hasOwn(cache, id)) return cache[id].exports;
    if (!Object.hasOwn(factories, id)) throw new Error("Unknown bundle module: " + id);
    const module = {exports: Object.create(null)};
    cache[id] = module;
    const require = name => load(resolve(id, name));
    // Deliberately no require.main: host invocation owns main exactly once.
    factories[id](require, module, module.exports, process, console);
    return module.exports;
  }
  const loaded = {rt: load("deal/runtime.js"), entry: load(entryId), checkpointSchema: Object.hasOwn(factories,'checkpoint-schema.js') ? load('checkpoint-schema.js') : null};
  // A host that restores or projects state names the bundle module owning the compiled
  // persistence roles; it shares this module cache, so nominal identity is never duplicated.
  if (appModule !== null) loaded.app = load(appModule);
  return loaded;
};

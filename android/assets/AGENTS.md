# Assets

`embedding/platform.dealui-pack` defines the renderer's components, props, events and capability identities. Coordinate changes with renderer registration and generation guidance.

JavaScript bindings load compiled modules and transport compiler-issued operations. Preserve bundle confinement, visible-action validation, console output bounds and schema-directed record/table conversion.

Configure trusted generation imports only in generation bundles. The trusted policy-data JSON-object result converts nested objects to DEAL tables and arrays to ABI arrays; keep it separate from closed-schema application capabilities. Reject incompatible JSON shapes rather than using std/json marked-array tables as native arrays.

Package Deal UI `runtime-js/session.js` as `embedding/bindings/dealui-session.js`; load it before `ui-session.js`. The embedding adapter supplies module loading and Promise scheduling only; lifecycle decisions belong to Deal UI.

Package runtime assets explicitly. Keep editing instructions out of APK/AAR assets.

# Android

[spec-v0.8](../docs/spec-v0.8.md) is the sole embedding authority; code and other guidance are subordinate.

Implement native execution, IPC, storage, rendering and resource enforcement mechanisms. Put controller decisions in compiled DEAL, not Kotlin or bridge JavaScript. Boundary validation and native containment remain mechanisms.

Keep application source outside library inputs. Preserve classified failures and cleanup evidence; do not infer repair eligibility from process exit status. Keep credentials out of packaged assets and observations.

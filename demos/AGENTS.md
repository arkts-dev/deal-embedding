# Illustrative demos

Demos are public framework consumers, not a home for framework internals. Keep application source and domain policy here; compiler/generation/runtime/storage/rendering policy stays in the framework. Compile apps separately against the AAR; never add framework source directories or Kotlin friend paths to app compilation. White-box integration fixtures belong in framework tests/. Library builds must remain independent of demos/tests.

Use demos/build.sh; it delegates packaging to framework tooling. Only this repository, DEAL and Deal UI may be project-source inputs. Do not reintroduce paths to the former outer demo repository, symlink its source/build output or copy credentials/private state. Preserve app IDs, manifests, data, authority, configured backends and existing functionality during file reorganization. Functional, provider, inference and visual acceptance remain separate.

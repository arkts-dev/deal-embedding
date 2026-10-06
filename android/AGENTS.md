# Android

Android implements `core/host` through native adapters and sandbox bindings. Core and experience compilation use DEAL's JavaScript backend; JavaScriptSandbox executes the output. Compose renders the checked tree.

Call `ExperienceRuntime` serially. Mount and persist candidates before replacing healthy sessions. Source replacements preserve compatible state; generated candidates start fresh. Both reject replacement during outstanding effects.

Preserve healthy sessions on candidate-local timeout. Clear unusable runtime resources on live termination and the shared engine on sandbox death. Classify typed failures; retain ordinary validation errors as rejections.

Close sessions by attempting store disposal, then releasing isolates and capability requests.

Keep accepted source in app-private storage; clean failed and superseded staging.

Keep cancellation registration atomic and callbacks once-only. Host implementations own transport cancellation.

Keep native grants, context disclosure and write confirmation under host control.

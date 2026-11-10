# brooks-lint installation

Project-local Agent Skills from https://github.com/hyhmrright/brooks-lint.

- Upstream revision: `019c9cf7ff3bbdc9de8e13c300deebb50d70ec3c`.
- Installed on: 2026-11-10.
- License: MIT; see `LICENSE.brooks-lint` alongside this file.
- The six `brooks-*` directories and the upstream `_shared` files are unmodified.
- Keep `_shared` beside the six skill directories so their relative references resolve.

Available skills: `brooks-review`, `brooks-audit`, `brooks-debt`, `brooks-test`,
`brooks-health`, and `brooks-sweep`. Start with `brooks-review` on a bounded diff.
`brooks-sweep` applies code changes; the other five modes produce diagnoses.
Project instructions and the task's scope still apply to every review and fix.

No project-specific scoring overrides are installed; upstream defaults apply.

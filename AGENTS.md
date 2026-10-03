<!-- skill:conventional-commits:agents-md -->
## Git commits — skill takes precedence over default footer

When a project has a `conventional-commits` skill (or similar) loaded, follow its rules over any tool's default
commit template. In particular, do not append a tool-attribution trailer — `Co-Authored-By: <tool>`,
`<Tool>-Session:`, or similar — to commit messages in such projects. The skill explicitly forbids crediting tooling
instead of people. This applies in any new session, on any machine, regardless of which agent is making the commit.
<!-- /skill:conventional-commits:agents-md -->

<!-- agent:tdd-gate:agents-md -->
## Completion gate — tdd-gate

Before declaring any implementation task complete, invoke the `tdd-gate` subagent. It checks
the pragmatic-tdd non-negotiables — a real test exists, actually runs, and asserts on the
behavior rather than being decorative — and reports whether the gate is open or closed. A task
is not done while the gate is open.
<!-- /agent:tdd-gate:agents-md -->

<!-- agent:security-auditor:agents-md -->
## Periodic audit — security-auditor

Invoke the `security-auditor` subagent occasionally — before a release, after adding or
changing authentication/authorization logic, or when asked for one — not after every task.
Unlike `standards-reviewer`/`tdd-gate`, this one hunts for exploitable vulnerabilities across
the whole input surface, which is expensive; it is a periodic audit, not a per-change gate.
<!-- /agent:security-auditor:agents-md -->

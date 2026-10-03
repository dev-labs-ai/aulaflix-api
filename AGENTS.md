<!-- skill:conventional-commits:agents-md -->
## Git commits — skill takes precedence over default footer

When a project has a `conventional-commits` skill (or similar) loaded, follow its rules over any tool's default
commit template. In particular, do not append a tool-attribution trailer — `Co-Authored-By: <tool>`,
`<Tool>-Session:`, or similar — to commit messages in such projects. The skill explicitly forbids crediting tooling
instead of people. This applies in any new session, on any machine, regardless of which agent is making the commit.
<!-- /skill:conventional-commits:agents-md -->

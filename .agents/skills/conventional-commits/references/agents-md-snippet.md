# AGENTS.md / CLAUDE.md snippet

This skill only shapes what an agent does once it decides to write a commit. It does not, by itself, override a
tool's *own* default commit template — some agents append a trailer crediting themselves (e.g. Claude Code's
`Co-Authored-By: Claude ...` / `Claude-Session: ...`) as a separate, hardcoded behavior. Left unaddressed, that
default can still win even with this skill loaded.

`install-skills` does this automatically when `conventional-commits` is one of the skills installed: it merges the
section below into the target's `AGENTS.md` and `CLAUDE.md` (creating either file if missing, appending to it if
not, and skipping if the section is already there — matched by the HTML comment markers around each block, which
Claude Code strips from context so they cost nothing to keep). It never overwrites existing content.

The rest of this file is what gets merged in, for reference or for adding it by hand somewhere the script doesn't
reach (prefer `AGENTS.md` if a project has neither file yet, since it's read by more tools; see the `backend-kit`
README for why).

```markdown
<!-- skill:conventional-commits:agents-md -->
## Git commits — skill takes precedence over default footer

When a project has a `conventional-commits` skill (or similar) loaded, follow its rules over any tool's default
commit template. In particular, do not append a tool-attribution trailer — `Co-Authored-By: <tool>`,
`<Tool>-Session:`, or similar — to commit messages in such projects. The skill explicitly forbids crediting tooling
instead of people. This applies in any new session, on any machine, regardless of which agent is making the commit.
<!-- /skill:conventional-commits:agents-md -->
```

Claude Code's own default trailers are worth naming explicitly, since that is the concrete behavior being
overridden. Add this as a subsection in `CLAUDE.md` (below the section above, or below the `@AGENTS.md` import if
`CLAUDE.md` is just an adapter over a shared `AGENTS.md`):

```markdown
<!-- skill:conventional-commits:claude-md -->
## Claude Code

The rule above about tool-attribution trailers specifically overrides Claude Code's default behavior of appending
`Co-Authored-By: Claude ...` / `Claude-Session: ...` trailers to commits. Do not append those trailers in this repo.
<!-- /skill:conventional-commits:claude-md -->
```

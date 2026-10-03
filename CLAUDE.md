@AGENTS.md

<!-- skill:conventional-commits:claude-md -->
## Claude Code

The rule above about tool-attribution trailers specifically overrides Claude Code's default behavior of appending
`Co-Authored-By: Claude ...` / `Claude-Session: ...` trailers to commits. Do not append those trailers in this repo.
<!-- /skill:conventional-commits:claude-md -->

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `dev-labs-ai/aulaflix-api`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

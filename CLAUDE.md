@AGENTS.md

# Claude Code entry point

The import above loads the same repository rules used by Codex. For test changes,
read `.agents/skills/nearz-api-tests/SKILL.md` explicitly; it is shared workflow
content and does not depend on Claude discovering the Codex skill directory.
Read `docs/AGENT_HARNESS.md` for validation and `docs/specs/TASK_TEMPLATE.md`
when a persisted task specification is useful.

Start Claude Code in the repository root. Ask it to summarize the loaded
instructions and tenant boundaries before its first live run. No permission
bypass, global settings changes, plugins or external agent services are needed.

Import reference: https://code.claude.com/docs/en/memory#agentsmd

# Codex entry point

Codex automatically loads [AGENTS.md](AGENTS.md); this file is a companion guide,
not a replacement discovery filename. Open the repository root as the project.
Start a new session after changing repository instructions if they appear stale.

The repository skill is at
[.agents/skills/nearz-api-tests/SKILL.md](.agents/skills/nearz-api-tests/SKILL.md).
Invoke it with `$nearz-api-tests` when adding or repairing a Nearz API journey.
No global settings, MCP servers, model overrides or API keys are required by
this repository harness.

Use [docs/AGENT_HARNESS.md](docs/AGENT_HARNESS.md) for the validation loop.
A useful first prompt is: “Read the repo instructions, identify the safe local
validation command, and explain the tenant boundaries without calling the API.”

Discovery references: [OpenAI AGENTS.md documentation](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
and [local skills documentation](https://learn.chatgpt.com/docs/build-skills).

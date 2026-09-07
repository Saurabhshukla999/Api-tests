# Task: repository onboarding and agent harness

## Scope and acceptance criteria

Review the README and Java test architecture; document local setup and technical
architecture; add shared Codex/Claude instructions, a repository skill and a
repeatable validation workflow. Work on a new branch with logical commits.
Do not run live API journeys, provision tokens, modify backend behavior or push.

Branch: `codex/docs-agent-harness`.

## Delivered steps

1. `6cb5eca`: replaced machine-specific setup with portable instructions; added
   architecture, configuration behavior, tenant boundaries and corrected README.
2. `63ac6a2`: added AGENTS.md, CODEX.md, CLAUDE.md, the Nearz skill, harness guide
   and reusable task specification.
3. Final harness step: compile-only shell command and GitHub workflow; pinned
   compiler plugin 3.13.0 for the existing Java release 17 property; corrected
   README's direct known-defect selector to bypass the XML exclusion.

## Validation evidence (2026-09-07)

- Markdown relative links: checked against local files; passed.
- `pom.xml` and `testng.xml`: XML parsing passed; every suite class resolves to
  an existing Java source file.
- `git diff --check`: passed during review.
- `sh -n scripts/check.sh`: shell syntax checked.
- `sh scripts/check.sh`: whitespace check passed; exited 127 because Maven is
  not on PATH. Java reports 11.0.12; only JDK 11 was found by macOS java_home.
  Java 17+ and Maven 3.9.x are needed before compilation can be verified.
- Bundled skill validator was attempted but cannot import PyYAML in the available
  Python runtimes. Independent Ruby YAML parsing of the skill frontmatter and
  workflow passed, including skill identity and compile-job presence checks.
  This is not an agent behavior evaluation.
- No live API requests or agent behavior smoke runs were performed. No tokens
  were provisioned and no backend data/settings were changed.

## Handoff

After configuring the documented toolchain, run `sh scripts/check.sh` from the
repository root. CI execution remains unverified until the branch is pushed and
GitHub runs the workflow. Review manual behavior prompts in AGENT_HARNESS.md in
a fresh agent session. Live test execution still requires the environment and
exclusive tenant access described in SETUP.md.

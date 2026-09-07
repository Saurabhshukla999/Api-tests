# Agent harness

The harness is repository instructions, a reusable test workflow, a task spec,
and a compile-only check. It uses the existing Maven toolchain; no agent runtime
or orchestration service is added.

## Entry points

| File | Purpose |
|---|---|
| [AGENTS.md](../AGENTS.md) | Shared project rules and review criteria; Codex discovery entry |
| [CODEX.md](../CODEX.md) | Codex onboarding and skill invocation |
| [CLAUDE.md](../CLAUDE.md) | Imports shared instructions for Claude Code |
| [SKILL.md](../.agents/skills/nearz-api-tests/SKILL.md) | Add/repair/diagnose Nearz tests using existing helpers |
| [TASK_TEMPLATE.md](specs/TASK_TEMPLATE.md) | Scope, acceptance criteria, execution context and evidence |
| [check.sh](../scripts/check.sh) | Repeatable compile-only local gate |
| [compile.yml](../.github/workflows/compile.yml) | Runs that gate on GitHub pushes and pull requests |

The skill filename uses uppercase `SKILL.md` to match agent discovery. Claude
reads the shared skill explicitly through project instructions. All behavior
rules have one source in AGENTS.md; do not copy them into separate tool policies.

## Work loop

1. Inspect Git status and the actual source path. Read setup/architecture only
   as needed; trace callers of a helper before proposing a shared change.
2. Create or use the task's branch. For substantial changes, copy the task spec
   and write measurable acceptance criteria. Keep small task plans in the conversation.
3. Implement one coherent step. Reuse existing helpers and preserve case IDs,
   tenant roles, report freshness and independent expected calculations.
4. Run the local gate from the repo root:

   ```sh
   sh scripts/check.sh
   ```

   On Windows without a POSIX shell, run its two commands directly:

   ```powershell
   git diff --check
   mvn -B -DskipTests test-compile
   ```

5. If live execution is in scope and authorized, reserve the journey tenant and
   run the smallest selector from [SETUP.md](../SETUP.md). Broaden only when the
   change affects shared behavior. Inspect Surefire counts; zero tests or skipped
   scenarios do not establish the acceptance criteria. Known-defect failures and
   diagnostic audit verdicts must be reported separately.
6. Review the diff and artifact paths. Commit explicit files by logical outcome.
   Record validation and remaining gaps in the spec or handoff. Continue to the
   next step; pushing/merging remains a separate user-directed action.

## What is enforced

The script stops on whitespace-check or compiler failure. CI uses Java 17,
compiles test sources without credentials, and preserves Maven's failing exit
code. It never invokes the live suite. The compiler plugin is pinned so release
17 does not depend on Maven's inherited plugin version.

Tenant permissions, exclusive live-run ownership, independent oracles and secret
redaction are instruction/review requirements, **not automated enforcement**.
No live CI schedule, secret provisioning, automatic retries, or Git hooks are
installed. GitHub must run the workflow after this branch is pushed; branch
protection is a remote repository setting, not configured by these files.

## Failure and recovery

If tools or dependency access are unavailable, record the exact blocker and
finish the reviewable local work. Do not label an unrun build as passed. For API
failures distinguish authentication, capacity, date/cache/concurrent-writer
interference, test defects and product defects before editing assertions.
Check server state before repeating a write. Keep sensitive reports out of specs.

For a long task, update the spec at each commit boundary with decisions, commands,
results and the next unresolved step. A new agent can resume from that spec and
Git history without rediscovering the whole repository.

## Harness smoke checks

In a fresh agent session, ask it to explain the first steps for:

- A documentation-only edit: it should inspect files without running live tests.
- A money regression: it should trace `Money` callers and preserve an independent
  expected value, then identify a focused regression check.
- A failed live journey: it should check tenant/run conditions and resulting
  state before retrying a mutation, and retain known-defect assertions.

These are manual behavior checks, not a claim that agent behavior is guaranteed.

Build references: [Maven compiler release support](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html)
and [GitHub setup-java](https://github.com/actions/setup-java).

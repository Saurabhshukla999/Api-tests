---
name: nearz-api-tests
description: Add, repair, or diagnose Nearz REST Assured and TestNG API journeys in this repository. Use for test helpers, report assertions, billing expectations, and live-run diagnosis; not for implementing the external backend.
---

# Nearz API journeys

Read `AGENTS.md` at the repository root. Use `docs/ARCHITECTURE.md` to locate the
relevant block and `SETUP.md` for exact execution commands. Paths here are relative
to the repository root; the skill lives three directories below it.

## Add or repair a journey

1. Find the existing case ID and closest journey in `Block*Test`. For a shared
   fix, search every caller of the affected method before editing. Consult
   `docs/DEFECTS.md` when observed behavior conflicts with expected behavior.
2. State the expected record fields and report deltas. Calculate expected money
   using `Money` and catalogue inputs, independently of the API's returned total.
   Reading `net_payable` to perform settlement does not make it an assertion oracle.
3. Reuse `Steps` actions, `Catalogue` slots and unique phones. Add one action
   helper only if none covers the requested behavior. Snapshot only affected
   reports and retain `_qa` freshness. Restore temporary settings in `finally`.
4. Add the smallest runnable regression assertion for changed behavior. Preserve
   case IDs, skips and known-defect grouping unless evidence justifies a change.
   Register a new class in `testng.xml` when it belongs in the default suite.
5. Run `mvn -B -DskipTests test-compile`. For authorized live validation, use the
   smallest selector in `SETUP.md`, retaining `known-defect` exclusion for normal
   direct class runs. Do not invoke the live suite merely to validate documentation.

## Diagnose without hiding a failure

Check auth/tenant pairing, concurrent writers, calendar capacity, date rollover
and report caching before changing expectations. Read the failed request's
resulting state before retrying a mutation. HTTP 200 can contain `not_found`, and
a product DELETE can return 500 after deleting. Treat full HTTP attachments as
sensitive; summarize evidence without tokens or customer data.

Do not broaden tolerances, remove assertions or unskip unsupported payment
scenarios just to produce a green run. `VerifyDefectsTest` prints findings and
performs writes; its green exit is not proof that defects are fixed.

## Finish

Report commands actually run, test counts when available, checks not run and why,
and any residual data or restored settings. Update the task spec for substantial
work using `docs/specs/TASK_TEMPLATE.md`. Commit reviewed, coherent changes on the
task branch; do not push or merge without the user's instruction.

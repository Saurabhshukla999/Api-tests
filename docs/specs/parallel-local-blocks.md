# Task: run the suite in parallel locally, one salon per fork

Status: **approved 10 Nov 2026, not yet implemented.** Changes the repository rule
"never enable test parallelism"; the owner approved it on 10 Nov 2026.

## Problem and scope

- **Problem.** A full local run is serial. Against production it took ~69 min on
  9 Nov 2026; locally it is ~5x faster but still slow. Most journeys read a
  salon's report totals, act, then assert exact deltas. Two journeys in the
  same salon at once would see each other's bills, which is why `AGENTS.md`
  forbids parallelism.
- **Idea.** The deltas are per salon. Give each parallel worker its **own
  journey salon, owner and token**, so no two concurrent journeys share report
  totals, booking slots, write throttles or settings.
- **Included:**
  - Maven profile `parallel-local` with Surefire forks.
  - Per-fork config files.
  - Seeding N journey salons.
  - A per-fork phone digit.
  - Docs and a rule update.
- **Out of scope:**
  - Parallel runs against production (writes are blocked there anyway).
  - Threads inside one JVM. `SALON` and `Api.journey()` appear about 280
    times, and a thread-local tenant would touch all of them.
  - Making the backend faster (Rails production mode locally).

## Design

| Concern | Today | Parallel design |
|---|---|---|
| Worker | 1 JVM, TestNG `testng.xml` | Surefire `forkCount=4`, `reuseForks=true`; test classes are shared out across 4 JVMs |
| Tenant | `config.local.properties` | `config.local.fork${surefire.forkNumber}.properties` via `systemPropertyVariables`; `Env` already reads `-DconfigFile` |
| Journey salon | salon 1 | salons J1..J4, each with its own `salon_owner` user (`+9160000000 1x`), GST and token |
| Other / owner salon | salon 2 / salon 1 | shared and read-only in all forks: isolation checks only read them |
| Report deltas | per salon | per salon, so each fork is isolated |
| Booking slots | one roster | `Catalogue.prepare` builds a roster per salon, so capacity grows with the fork count |
| Write throttles (enquiries 60/min, reminders, dashboard refresh) | per user | one owner per fork, so the limits are separate |
| Phones | `600` + 7-digit clock counter | **must change**: forks started in the same second would give identical numbers. Use `600` + fork digit + 6-digit counter (wraps after ~11.6 days instead of ~115) |
| Local-only jobs and tokens (`LocalBackend`) | `docker exec` | unchanged; concurrent `rails runner` processes are independent |
| Known-defect filtering | `testng.xml` `<exclude>` | profile sets `excludedGroups=known-defect`, as direct class runs already do |
| Order | `testng.xml` order | class order per fork is Surefire's. Each fork uses its own salon, so no block depends on another fork's data. A block that disturbs its salon (Block 6 marks staff absent) only affects later classes **in the same fork**, as today |

Backend capacity: Puma runs one process with 5 threads, and the database pool
is 5. Four forks give at most 4 requests at once, so `forkCount` stays at 4
or below.

## Implementation and commits

1. **Seed.** `scripts/local_seed.rb` takes `JOURNEY_SALONS=N` and creates or
   finds salons J1..JN, each with an owner and GST. `local-backend.sh seed`
   writes `config.local.fork1..N.properties` (git-ignored by a
   `config.local*.properties` pattern), plus today's file for serial runs.
2. **Phones.** `Steps.newPhone()` puts the fork digit (`surefire.forkNumber`,
   default 0) after the prefix. Document the shorter wrap in SETUP.md.
3. **Profile.** In `pom.xml`, add `parallel-local`: `forkCount` (default 4,
   overridable by `-Dforks=`), `reuseForks=true`, includes `**/Block*Test.java`
   and `ProductCrudTest`, `excludedGroups=known-defect`, and
   `configFile=config.local.fork${surefire.forkNumber}.properties`. Not with
   `suiteXmlFiles`: Surefire runs a suite XML in a single fork.
4. **Guard.** Refuse the profile unless the base URL is localhost. A fork
   whose config points elsewhere fails in `@BeforeSuite`.
5. **Docs.** Update the AGENTS.md rule: parallel runs are allowed only through
   `parallel-local`, on the local backend, with one journey salon per fork.
   Add a SETUP.md section and update the CI templates.

## Acceptance criteria

- `scripts/local-backend.sh seed` with `JOURNEY_SALONS=4` creates four journey
  salons with different owners, and reruns create nothing new.
- `mvn test -Pparallel-local` finishes with **the same pass, fail and skip
  counts as the serial run of the same commit**. Any difference is
  investigated as interference, not accepted.
- Each fork's Allure/Surefire output shows only its own salon id in request
  URIs.
- Wall time at most 40% of the serial local run (target 25–35%).
- No phone number is issued twice across forks in one run, checked from the
  run's request log.
- On production, the profile refuses to start.

## Validation evidence

| Command/check | Result | What it establishes / limitation |
|---|---|---|
| `mvn -B -DskipTests test-compile` | Pending | Sources compile |
| Serial baseline: `mvn test -DconfigFile=config.local.properties` | Running 10 Nov 2026 | Counts and wall time to compare against |
| `mvn test -Pparallel-local` | Pending | Same counts, faster |
| Phone uniqueness across forks | Pending | No customer collision |
| Diff and credential review | Pending | No tokens in tracked files |

## Risks

- **Hidden cross-block coupling**, which the count comparison is there to
  catch. For example: Block 9 dashboard totals assume earlier blocks ran in
  the same salon, or Block 6 attendance leaks into a later block. The fix is
  in that block, never a looser assertion.
- **Rails development mode** reloads code under concurrent requests. If that
  causes timeouts, drop to 3 forks.
- **Uneven blocks** (Block 6 and Block 10 are long) set the wall time. A
  later step could split them.

# Local setup

This repository runs Java API tests against a separately hosted Nearz backend.
It does not contain the backend, database, Docker stack, or a local API server.
Run commands from the directory containing `pom.xml`.

## 1. Toolchain

Install a JDK **17 or newer** and Apache Maven **3.9.x** using your team's
approved distribution. JDK 17 is the baseline for this project. Set `JAVA_HOME`
to the JDK directory and put its `bin` directory and Maven's `bin` on `PATH`.
Reopen the terminal after changing these variables.

```sh
java -version
mvn -v
```

Both must report the intended JDK; Maven can use a different Java installation
from the `java` command. No Maven wrapper is checked in. Maven downloads REST
Assured, TestNG, Hamcrest, Jackson and Allure from the versions in `pom.xml`;
do not install them separately. The first build needs access to Maven repositories.

### This Mac: installed toolchain

Installed and checksum-verified on 7 Sep 2026 from the official
[Temurin Java 17 distribution](https://adoptium.net/temurin/releases/?version=17)
and [Apache Maven downloads](https://maven.apache.org/download.cgi):

- Java: Temurin **17.0.20.1+1**, Intel macOS.
- Maven: **3.9.16**.
- Installation directory: `~/.local/share/nearz-toolchain/`.
- `~/.zshrc` sets `JAVA_HOME` and prepends both tool `bin` directories to PATH.
- Original shell configuration backup: `~/.zshrc.before-nearz-java17`.

Open a new terminal, then run `java -version` and `mvn -v`. Existing terminals
can load just these settings without restarting:

```sh
export JAVA_HOME="$HOME/.local/share/nearz-toolchain/jdk-17.0.20.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/.local/share/nearz-toolchain/apache-maven-3.9.16/bin:$PATH"
```

The older Java installations remain installed. These are user-local binaries;
Homebrew does not manage their updates. To switch later, change the two Nearz
exports at the end of `.zshrc` to the new installation paths.

## 2. Compile before configuring credentials

```sh
mvn -B -DskipTests test-compile
```

This compiles the test sources without invoking TestNG or contacting Nearz.
It needs neither tokens nor `config.properties`. Compilation is not an API test
pass. Do not use `-Dmaven.test.skip=true`: that also skips test compilation.

### Optional offline runtime smoke test

```sh
mvn test "-Dtest=LocalRuntimeTest"
```

This opt-in test verifies Map-to-JSON serialization, JSON assertions and Allure
listener/filter integration using an in-memory response. It does not load `Env`,
open sockets or require credentials. It is deliberately outside `testng.xml`,
which remains the live business suite. Its report proves local dependencies work,
not that the Nearz backend is healthy. See [dependency audit](docs/DEPENDENCIES.md).

## 3. Configure an authorized test environment

Copy the example **only if the destination does not already exist**.

macOS/Linux:

```sh
cp -n src/test/resources/config.properties.example src/test/resources/config.properties
```

PowerShell:

```powershell
if (!(Test-Path src/test/resources/config.properties)) {
  Copy-Item src/test/resources/config.properties.example src/test/resources/config.properties
}
```

Edit the copied file locally. Tokens are not included in a fresh checkout.
Obtain current credentials from the environment owner through an approved
private channel. Do not paste tokens into agent prompts, commits or tickets.

| Key | Example value / purpose |
|---|---|
| `baseUrl` | `https://www.testnearz.co.in` is **production** (the live app and B2B website use it; writes are blocked, see below). `http://localhost:3000` for the local Docker backend |
| `journeySalonId` | `4550`; dedicated tenant for writes and seeding |
| `journeyToken` | Bearer token for that tenant, without the `Bearer ` prefix |
| `ownerSalonId` | `4536`; a real production salon, read only |
| `ownerToken` | Token for the owner tenant (4536) |
| `otherSalonId` | `1725`; separate tenant with retained data for isolation checks |
| `otherToken` | Token for the second tenant |
| `testPhonePrefix` | Optional, default `600`. Every customer and staff phone the suite creates is this prefix plus a counter, so test users are `mobile LIKE '600%'`. 3-4 digits, starting 6-9, not `91` (D22). Agree the block with the backend team and check it holds no real users |

Confirm that each ID/token pair belongs to the selected environment. These IDs
are example roles, not permission to write to any server. `Api.owner()` is a
convention, not a technical write blocker.

**Production write guard.** `testnearz.co.in` serves real customers, so
`ProductionGuard` refuses every POST, PUT, PATCH and DELETE to it before the
request is sent, and the test fails with a message saying so. GETs still pass,
so read-only checks can run there. Almost every journey writes (class setup
saves working days), so in practice the suite does not run on production. To
write there deliberately, add `-DallowProductionWrites=true` on the command
line; it is never read from a config file.

`Env` loads `config.properties` from the classpath, or the file named by
`-DconfigFile`, then uses nonblank Java `-D` properties in preference to file
values. **The file must exist even when using overrides**, and all seven
settings are initialized together. Shell environment variables and `.env`
files are not read. Examples:

```sh
# Local Docker backend, using src/test/resources/config.local.properties
mvn test "-DconfigFile=config.local.properties" "-Dtest=ProductCrudTest"

# One nonsecret override
mvn test "-DbaseUrl=http://localhost:3000" "-Dtest=ProductCrudTest"
```

`config.local.properties` is git-ignored like `config.properties`. It holds
`baseUrl = http://localhost:3000`, the local salon id for all three
`*SalonId` keys and a local token for the three `*Token` keys (see "Preparing
the local Docker backend" below).

Keep credentials in the ignored file rather than command arguments, where they
can appear in shell history or process listings. Verify exclusion with:

```sh
git check-ignore src/test/resources/config.properties
```

### Swagger reference and documentation credentials

Use the [Nearz Swagger API reference](https://testnearz.co.in/api-docs/index.html)
to check endpoint methods, paths, request fields, response schemas and security
requirements when adding or updating automated tests. Use HTTPS when signing in.
On 8 Sep 2026 the page returned `401` with `Basic realm="API Docs"`, requiring
an API Docs username and password.

Store these documentation credentials outside the repository. On macOS/Linux:

1. Create a private directory and open a local file in your editor:

   ```sh
   mkdir -p "$HOME/.config/nearz"
   chmod 700 "$HOME/.config/nearz"
   ```

2. Save `~/.config/nearz/swagger.netrc` with this structure, replacing the
   placeholders locally with credentials from the environment owner:

   ```text
   machine testnearz.co.in
   login YOUR_USERNAME
   password YOUR_PASSWORD
   ```

3. Restrict file access:

   ```sh
   chmod 600 "$HOME/.config/nearz/swagger.netrc"
   ```

4. Tell the coding agent only the file path and that it contains Swagger Basic
   Auth credentials. Do not paste the username/password into chat, command-line
   arguments, tickets or tracked files. The file is plain text, protected by
   local filesystem permissions; it is not an encrypted credential store.

An agent can check access without printing credentials or the page contents:

```sh
curl --netrc-file "$HOME/.config/nearz/swagger.netrc" \
  --proto '=https' --silent --show-error --fail \
  --output /dev/null --write-out '%{http_code}\n' \
  'https://testnearz.co.in/api-docs/index.html'
```

Do not use verbose/trace output when authenticating. The command does not follow
redirects; inspect a redirect's destination before sending credentials elsewhere.
After access is available, inspect the documentation to identify its actual
OpenAPI JSON/YAML URL rather than guessing it. Compare the contract with existing
journeys and record discrepancies instead of weakening assertions to match bugs.

Swagger Basic Auth only unlocks the documentation. API calls may require separate
bearer tokens: keep those in the ignored `config.properties` described above.
The Java suite does not read `swagger.netrc`, and reading Swagger does not execute
or authorize live business journeys. Browser login is an alternative: sign in
yourself and share the authenticated tab with the agent.

## 4. Run live tests deliberately

Every command below contacts the configured API and can mutate test data,
including class setup. Run them against the **local Docker backend**, which runs
the backend checkout (equivalent to `new_master_aws`, the source of truth, for
every endpoint the suite calls) and touches no real data. Against production
the write guard stops them at the first write. Reserve the journey salon for one
run at a time across all developers, agents and CI jobs. Separate Git worktrees
do not isolate API data.

```sh
# Small CRUD check; creates and deletes products
mvn test "-DconfigFile=config.local.properties" "-Dtest=ProductCrudTest" "-DexcludedGroups=known-defect"

# One complete business journey
mvn test "-DconfigFile=config.local.properties" "-Dtest=Block1EnquiryTest#e2e001_enquiryToSettledBill" "-DexcludedGroups=known-defect"

# One block; -Dtest bypasses the XML suite, so retain the group exclusion
mvn test "-DconfigFile=config.local.properties" "-Dtest=Block4AppointmentTest" "-DexcludedGroups=known-defect"

# Normal suite, ordered by testng.xml, with known-defect excluded there
mvn test "-DconfigFile=config.local.properties"

# Explicit defect reproduction: bypass XML's known-defect exclusion
mvn test "-DconfigFile=config.local.properties" "-Dtest=Block*Test" "-Dgroups=known-defect"

# Diagnostic audit; inspect printed verdicts, not just Maven's exit code
mvn test "-DconfigFile=config.local.properties" "-Dtest=VerifyDefectsTest"
```

Quote `-D` arguments as shown for PowerShell and to protect `#` and `*` from
shell interpretation. `VerifyDefectsTest` is not in the default XML suite;
it mostly prints findings rather than asserting them, and also performs writes.

Setup finds/creates named services, products and staff, and updates working-day
configuration. Journeys leave business records behind; there is no global reset
or transactional rollback. Restore any temporary salon settings in `finally`.
The default roster is 20 stylists × 19 slots = 380 appointments per day;
restarting Maven does not reclaim slots. Coordinate capacity before a full run.

### Contract check and CI

```sh
# Every documented GET checked against backend/swagger/v1 (read-only)
mvn test "-DconfigFile=config.local.properties" "-DsuiteXml=testng-contract.xml"
```

`-DswaggerDir` points at another copy of the spec files. The default is
`../backend/swagger/v1`. Findings are SW-14 and SW-15 in docs/DEFECTS.md.

CI templates, written 10 Nov 2026 and **not yet run**:

- `ci/backend-bitbucket-pipelines.yml`: copy it into the backend repository
  to run the suite on every backend pull request against that pull request's
  code. A branch restriction on `new_master_aws` then blocks merging on a
  failure. It needs the `API_TESTS_REPO` and `API_TESTS_TOKEN` variables.
- `.github/workflows/local-stack.yml`: a manual run in this repository
  against any backend branch. It needs the `BITBUCKET_USER` and
  `BITBUCKET_APP_PASSWORD` secrets.

Blocks 13 to 15 are local only. They run jobs and mint customer and staff
tokens inside the web container, and skip on any other target.

## 5. Reports and troubleshooting

```sh
mvn allure:report
mvn allure:serve
```

Surefire output is in `target/surefire-reports/`; raw Allure results are in
`target/allure-results/`; HTML is in
`target/site/allure-maven-plugin/index.html`. Allure downloads its report tool
on first use. For a fresh live run, `mvn clean test` removes old local output
but does **not** remove server data. Full HTTP attachments can contain tokens
and customer data: keep reports local or redact them before sharing.

| Symptom | Next action |
|---|---|
| `mvn` missing / unsupported Java release | Fix PATH and JAVA_HOME; recheck `mvn -v` |
| Dependency resolution fails | Check repository connectivity/proxy and Maven settings |
| Missing configuration / initializer error | Check exact file path and all seven keys |
| 401/403 | Check token validity, tenant pairing and environment with its owner |
| Calendar full / double booking | Stop retries; coordinate a fresh day or reviewed capacity change |
| Unexpected report delta | Check concurrent writers, date rollover and `_qa` cache busting |
| Known defect fails | Compare with `docs/DEFECTS.md`; preserve the regression assertion |
| HTTP 200 but missing record | Inspect JSON; the API can return `error: not_found` with 200 |

## 6. Running with Docker

You can run the tests in an isolated container without installing JDK 17 or Maven locally:

```sh
# Build the test runner image
docker compose build

# Run offline toolchain/JSON smoke test
docker compose run --rm api-tests

# Run tests against local backend running on Docker/localhost (port 3000)
docker compose run --rm api-tests mvn test "-DbaseUrl=http://host.docker.internal:3000"

# Run tests against a staging server, if one exists (none did on 9 Nov 2026)
docker compose run --rm api-tests mvn test "-DbaseUrl=https://apistaging.nearz.in"

# Run a specific journey or block
docker compose run --rm api-tests mvn test "-Dtest=Block1EnquiryTest#e2e001_enquiryToSettledBill" "-DexcludedGroups=known-defect"
```

Allure results are written to `./allure-results` on the host machine.

### Preparing the local Docker backend (measured 2 Nov 2026)

There is no staging environment, and `testnearz.co.in` is production, so
write runs use the backend's own development stack
(`Nearz_B2B/backend/docker/development`). Its `web` container mounts the
backend folder, so it runs whatever is checked out there. On 9 Nov 2026 that
was `new_master_aws` plus a signup fix and local-dev changes; none of them touch
an endpoint the suite calls.

**One command** (Git Bash on Windows, any shell elsewhere):

```sh
scripts/local-backend.sh all          # start/rebuild, seed, status
scripts/local-backend.sh seed         # only re-seed and rewrite config.local.properties
scripts/local-backend.sh status       # containers, Sidekiq, token check
scripts/local-backend.sh reset --yes  # DROPS the local database first
```

`up` layers `docker/local-backend.override.yml` on the backend's compose file
(push and Slack off, `LAUNCHY_DRY_RUN` so mail jobs stop failing on a missing
browser), rebuilds the images, which fixed the Sidekiq container that crashed
from 7 Nov on missing gems, and installs `tzdata-legacy` in `db` if needed.
`seed` (`scripts/local_seed.rb`, idempotent) keeps salon 1 as the journey salon,
adds GST, creates a second salon under a different owner with two staff (owner
phones in the reserved `600` block), mints 90-day local tokens and writes
`src/test/resources/config.local.properties` (git-ignored) without printing
them. Then run with `-DconfigFile=config.local.properties`.

The rest of this section records how the first local stack was prepared by
hand; the script now covers steps 1, 3 and 4. A fresh stack needed this:

1. **Notifications off.** The dev stack has no Firebase key, so every booking
   answered 500, and it inherits the real Slack webhooks. Set
   `DISABLE_EXTERNAL_NOTIFICATIONS: 'true'` on the `web` and `sidekiq`
   services; the backend then skips FCM and Slack outside production.
2. **Seed data.** Run `bundle exec rails db:seed` in the web container
   (`bin/rails` has CRLF endings on a Windows checkout and will not start).
   `salon_setup:seed` is broken against the current `User` model, so create a
   `salon_owner` user and its salon with `rails runner`, and mint the token
   with `JsonWebToken.encode(user_id: ...)`.
3. **GST on.** A new salon has GST off and the GST journeys refuse to run.
   Create its `SalonTax` with a GSTIN and 9% CGST + 9% SGST.
4. **Time zone.** `config.time_zone` is `Asia/Calcutta`; the Postgres 15
   image (Debian 13) only ships `Asia/Kolkata`, and every report answers 500.
   Install `tzdata-legacy` in the `db` container (lost when it is recreated).

Bookings are made for today, between 10:00 and 19:30, in slots that have not
started yet. With the 40-stylist roster that is 760 places a day, so start a
full run early in the day and run it at most once per day. A run started late
fails with "today's calendar is full".

The local stack answers far faster than production, which is why `Steps` waits out the
enquiry write throttle (60 per user per minute). Known differences from the QA
server are recorded in [DEFECTS.md](docs/DEFECTS.md) (D20, D21, D24, SW-8).

See [technical architecture](docs/ARCHITECTURE.md) and
[agent harness](docs/AGENT_HARNESS.md) for implementation and contribution rules.

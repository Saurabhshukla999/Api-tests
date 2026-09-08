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
| `baseUrl` | `https://www.testnearz.co.in`; replace for another authorized backend |
| `journeySalonId` | `4550`; dedicated tenant for writes and seeding |
| `journeyToken` | Bearer token for that tenant, without the `Bearer ` prefix |
| `ownerSalonId` | `4536`; existing QA data, read only |
| `ownerToken` | Token for the owner QA tenant |
| `otherSalonId` | `1725`; separate tenant with retained data for isolation checks |
| `otherToken` | Token for the second tenant |

Confirm that each ID/token pair belongs to the selected environment. These IDs
are example roles, not permission to write to any server. `Api.owner()` is a
convention, not a technical write blocker. The suite has no production URL guard.

`Env` loads the classpath file first, then uses nonblank Java `-D` properties in
preference to file values. **The file must exist even when using overrides**,
and all seven settings are initialized together. Shell environment variables
and `.env` files are not read. Nonsecret override example:

```sh
mvn test "-DbaseUrl=https://staging.example.com" "-Dtest=ProductCrudTest"
```

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
including class setup. Reserve the journey salon for one run at a time across
all developers, agents and CI jobs. Separate Git worktrees do not isolate API data.

```sh
# Small CRUD check; creates and deletes products
mvn test "-Dtest=ProductCrudTest" "-DexcludedGroups=known-defect"

# One complete business journey
mvn test "-Dtest=Block1EnquiryTest#e2e001_enquiryToSettledBill" "-DexcludedGroups=known-defect"

# One block; -Dtest bypasses the XML suite, so retain the group exclusion
mvn test "-Dtest=Block4AppointmentTest" "-DexcludedGroups=known-defect"

# Normal suite, ordered by testng.xml, with known-defect excluded there
mvn test

# Explicit defect reproduction: bypass XML's known-defect exclusion
mvn test "-Dtest=Block*Test" "-Dgroups=known-defect"

# Diagnostic audit; inspect printed verdicts, not just Maven's exit code
mvn test "-Dtest=VerifyDefectsTest"
```

Quote `-D` arguments as shown for PowerShell and to protect `#` and `*` from
shell interpretation. `VerifyDefectsTest` is not in the default XML suite;
it mostly prints findings rather than asserting them, and also performs writes.

Setup finds/creates named services, products and staff, and updates working-day
configuration. Journeys leave business records behind; there is no global reset
or transactional rollback. Restore any temporary salon settings in `finally`.
The default roster is 20 stylists × 19 slots = 380 appointments per day;
restarting Maven does not reclaim slots. Coordinate capacity before a full run.

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

See [technical architecture](docs/ARCHITECTURE.md) and
[agent harness](docs/AGENT_HARNESS.md) for implementation and contribution rules.

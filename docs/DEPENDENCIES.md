# Maven dependency audit

Reviewed on 7 Sep 2026 with Maven 3.9.16 and Temurin Java 17.0.20.1+1.
**No declared dependency is unused. No dependency was removed.**
All seven are test-scoped; this project has no production application artifact.

## Direct dependencies

| Dependency | Version | Evidence / decision |
|---|---|---|
| `io.rest-assured:rest-assured` | 5.5.0 | HTTP request/response specs and filters throughout `Api`, `Steps`, `Catalogue`, reports and tests. Keep. |
| `io.rest-assured:json-path` | 5.5.0 | `JsonPath` is imported by nearly every journey/helper. Also transitive through REST Assured, but directly used APIs should remain explicitly declared. Keep. |
| `org.testng:testng` | 7.10.2 | Test annotations, setup, assertions and skips. Keep. |
| `org.hamcrest:hamcrest` | 2.2 | `Matchers` used in `Api` and product CRUD assertions. Also transitive, but directly used. Keep. |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 | REST Assured discovers it at runtime to serialize Map request bodies. Removing it breaks serialization. Keep. |
| `io.qameta.allure:allure-testng` | 2.29.0 | Loaded through `META-INF/services/org.testng.ITestNGListener`; emits test results without application imports. Keep. |
| `io.qameta.allure:allure-rest-assured` | 2.29.0 | Explicitly attached in `Api` to record HTTP exchanges. Keep. |

Maven's bytecode analyzer reports Jackson and `allure-testng` as unused because
it does not see dynamic discovery. These are false positives, not cleanup targets.
Do not delete them based only on `dependency:analyze` output.

Transitive libraries belong to these frameworks: Groovy/JSON/XML support and
Apache HTTP components to REST Assured; Jackson core/annotations to Databind;
Allure commons/model/attachments and FreeMarker to reporting. Their presence is
not evidence of dead code. No exclusions were added to framework internals.
The nonfatal SLF4J no-binding notice does not require another logging dependency;
the local smoke test and Allure reporting both work without one.

## Build plugins

| Plugin | Version | Purpose |
|---|---|---|
| `maven-compiler-plugin` | 3.13.0 | Compiles the existing release 17 sources; explicit version preserves release support |
| `maven-surefire-plugin` | 3.2.5 | Runs TestNG, honors suite selection and configures Allure output |
| `allure-maven` | 2.12.0 | Generates the documented HTML report; independent of the runtime adapters |

All three are used. Plugin/download versions and transitive dependencies were
reviewed for usage, not upgraded or subjected to a vulnerability audit.

## Reproduce the checks

```sh
# Compiles only, then lists and analyzes dependencies; no API calls
mvn -B -ntp clean test-compile dependency:tree dependency:analyze

# Exercises the runtime in memory, independently of tokens or Env
mvn -B -ntp test "-Dtest=LocalRuntimeTest"
```

Results:

- Clean compilation passed for the original 19 Java sources; adding the smoke
  test compiled 20 sources successfully.
- Analyzer flagged only the two dynamic dependencies described above.
- `LocalRuntimeTest`: **1 test, 0 failures, 0 errors, 0 skipped**.
- The newest Allure result was passed and referenced two existing attachment
  files: the synthetic request and response.
- In isolated temporary project copies, removing **Jackson** made the smoke
  test fail with “no JSON serializer found in classpath.” Removing
  **allure-testng** made listener discovery fail. Both probes exited nonzero.
  The repository POM was not changed for these probes.

The smoke test never proceeds to REST Assured's HTTP transport. It verifies
serialization, JSON/Hamcrest assertions, TestNG listener discovery and execution
of the Allure filter. Its selector bypasses the live XML suite deliberately.
It does not verify the external API, token validity or business journey outcomes.

## Local setup status

Java and Maven are configured in `~/.zshrc`; see [SETUP.md](../SETUP.md).
A local, ignored `src/test/resources/config.properties` was copied from the
example with placeholders. Supply valid credentials and coordinate the journey
tenant before running live tests. No live API calls were made during this audit.

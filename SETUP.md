# Setting up this project

Done on saurabh's machine on 28 Aug 2026. Written down so the next person does
not have to work it out.

---

## The short version

You install **two** things. Everything else Maven downloads by itself.

| | |
|---|---|
| **JDK 17 or newer** | to compile and run Java |
| **Maven** | to fetch libraries and run the tests |

Then:

```powershell
cd C:\Users\<you>\Documents\api-service\nearz-api-tests
mvn test
```

---

## What you do NOT install

This trips people up, so it is worth saying plainly.

**TestNG, REST Assured, Allure, Hamcrest and Jackson are not applications and
you do not download them.** They are listed in `pom.xml`, and the first time
you run `mvn test`, Maven fetches every one of them into `~\.m2\repository` and
puts them on the classpath. That is the entire point of Maven.

If someone hands you a "TestNG installer", you have the wrong thing. The only
TestNG you need is this, already in `pom.xml`:

```xml
<dependency>
  <groupId>org.testng</groupId>
  <artifactId>testng</artifactId>
  <version>7.10.2</version>
  <scope>test</scope>
</dependency>
```

The first `mvn test` takes a few minutes because of those downloads. Every run
after that is fast.

---

## What was actually done on this machine

The JDK and Maven were both already present but neither was on the PATH, so
Windows could not find them. `java -version` answered *"not recognized"* and
`JAVA_HOME` was empty.

**1. Moved Maven out of Downloads.**

```
C:\Users\saura\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16
        ->  C:\Users\saura\apache-maven-3.9.16
```

Downloads gets cleared. A toolchain should not live there.

**2. Set two user environment variables and extended the PATH.**

```powershell
$jdk   = "C:\Program Files\Java\jdk-26.0.2.1"
$maven = "$env:USERPROFILE\apache-maven-3.9.16"

[Environment]::SetEnvironmentVariable("JAVA_HOME",  $jdk,   "User")
[Environment]::SetEnvironmentVariable("MAVEN_HOME", $maven, "User")

$path = [Environment]::GetEnvironmentVariable("Path", "User")
$path = $path.TrimEnd(';') + ";$jdk\bin;$maven\bin"
[Environment]::SetEnvironmentVariable("Path", $path, "User")
```

These are **user-level** variables — no administrator rights needed, and
nothing outside this account is touched.

**3. Opened a new terminal.** Environment variables are read when a shell
starts, so an already-open window will not see them. Close it and open a new
one, or the changes look like they did not work.

---

## Verifying

In a **new** PowerShell window:

```powershell
java -version     # java version "26.0.2.1"
mvn -v            # Apache Maven 3.9.16
echo $env:JAVA_HOME
```

Then the real check:

```powershell
cd $env:USERPROFILE\Documents\api-service\nearz-api-tests
mvn test "-Dtest=ProductCrudTest" "-Dsurefire.suiteXmlFiles=" "-DexcludedGroups=known-defect"
```

Confirmed working on 28 Aug 2026:

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Note the quoting. In PowerShell a `-D` argument must be quoted as
`"-Dtest=ProductCrudTest"`, or PowerShell eats the `=`. In `cmd` you do not
need the quotes.

---

## If you are installing from scratch on another machine

`winget` is on this machine, so it is two commands in an **Administrator**
PowerShell:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install Apache.Maven
```

Then close the terminal, open a new one, and verify as above. Maven installed
this way sets its own PATH; the JDK usually sets `JAVA_HOME` too, but check.

Any JDK 17 or newer works. This machine runs JDK 26 and the project compiles to
release 17, which is fine — a newer JDK can always target an older release.

---

## An IDE (optional)

You do not need one. `mvn test` in a terminal is the whole workflow.

If you want one for reading and writing the tests:

- **Eclipse for Java Developers** — already sitting in your Downloads folder
  (`eclipse-java-2026-06-R-win32-x86_64`). Maven support is built in: unzip it,
  run `eclipse.exe`, then *File > Import > Existing Maven Projects* and point it
  at `nearz-api-tests`. Add the TestNG plugin from the Eclipse Marketplace if
  you want to right-click and run a single test.
- **IntelliJ IDEA Community** — free, and the smoothest of the three for TestNG.
  It bundles Maven and will offer to download a JDK for you.
  `winget install JetBrains.IntelliJIDEA.Community`
- **Antigravity** — you have it. It will open the folder and edit the files
  fine; whether it can *run* a single test depends on the Java extensions it
  has. The terminal always works regardless.

Whichever you pick: open the **`nearz-api-tests` folder**, the one containing
`pom.xml`. Do not open `api-service` itself — that is the Rails app, and the
IDE will be confused about what kind of project it is looking at.

---

## Configuration

`src/test/resources/config.properties` already has the three salon tokens
filled in, so the suite runs immediately.

Tokens expire — these run out on **26 Nov 2026**. When they do, every test
fails with 401. Get fresh ones from the QA dashboard (DevTools > Network > any
XHR > the `Authorization` header) and paste them in, or override on the command
line without editing the file:

```powershell
mvn test "-DjourneyToken=eyJ..."
```

`config.properties` is in `.gitignore` so real tokens never get committed.
`config.properties.example` is the version with placeholders.

---

## Running things

```powershell
mvn test                                   # everything (~11 minutes)
mvn test "-Dtest=ProductCrudTest"          # one class
mvn test "-Dtest=Block1EnquiryTest#e2e001_enquiryToSettledBill"   # one test
mvn test "-Dgroups=known-defect"           # the tests that track open bugs
mvn allure:report                          # build the HTML report
mvn allure:serve                           # build it and open it in a browser
```

The report lands at `target\site\allure-maven-plugin\index.html`.

`mvn allure:report` downloads a ~40MB Allure binary into `.allure\` the first
time. That folder is gitignored.

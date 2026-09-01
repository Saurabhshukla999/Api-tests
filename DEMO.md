# Running one journey, and showing the report

A cheat sheet for the demo. Everything here is done in a terminal inside your
IDE. That is the normal way to work — not a shortcut.

---

## Yes, the IDE terminal is the right way

Maven is a command-line tool. Every Java team runs tests exactly like this:
open a terminal in the project folder and type `mvn test`. The IDE just gives
you that terminal in the same window as the code. Nothing is being done the
"wrong way round".

In **Eclipse**: `Window > Show View > Terminal`, then click the ⊞ icon and pick
*Local Terminal*.
In **Antigravity** (VS Code family): `` Ctrl+` `` — the backtick key, top-left
under Escape.

Either way, first make sure you are in the right folder:

```powershell
cd C:\Users\saura\Documents\api-service\nearz-api-tests
```

You are in the right place if `dir` shows **pom.xml**.

---

## The three commands for the demo

### 1. Run one journey

```powershell
mvn test "-Dtest=Block1EnquiryTest#e2e001_enquiryToSettledBill" "-Dsurefire.suiteXmlFiles="
```

Takes about 40 seconds. You want to see:

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 2. Build the report

```powershell
mvn allure:report
```

### 3. Open it

```powershell
start target\site\allure-maven-plugin\index.html
```

`start` is the Windows command for "open this in whatever app handles it" — it
will open in your browser.

**Practise all three once before the meeting.** The first `allure:report` ever
run downloads a ~40MB tool, which you do not want happening live.

---

## Where the reports live

All under `target\` inside the project folder. You can open them from the IDE's
file tree like any other file.

| Where | What it is |
|---|---|
| `target\site\allure-maven-plugin\index.html` | **the one to show** — charts, test names, every request and response |
| `target\surefire-reports\emailable-report.html` | one plain page, fine to email |
| `target\surefire-reports\index.html` | TestNG's own report |
| `target\surefire-reports\*.txt` | plain text, one file per class |

`target\` is generated — it is deleted and rebuilt every run, and it is not in
git. That is normal and expected.

---

## What to say while it runs

The single sentence that explains the whole project:

> "A normal API test checks that the endpoint answered 200. This follows a real
> customer journey end to end, and then checks that the salon's reports moved by
> exactly the right amount."

Then walk the journey — this is what `e2e001` actually does:

1. Creates an **enquiry** — a walk-in asking about a haircut
2. **Converts** it to a customer
3. **Books an appointment** for that customer today
4. Marks it **completed**
5. Raises a **bill** for the ₹1,000 service
6. **Settles** it in cash

Then it checks **thirteen numbers** across six reports: Sales, Payments,
Customers, Services, Staff Performance and Profit.

The point to land:

> "The expected figures are calculated by the test itself, in Java, not read
> back from the API. If we asked the API what the total was and then checked the
> API's total against itself, we would prove nothing."

---

## Showing the code

Open the **`nearz-api-tests` folder** — the one containing `pom.xml`. Not
`api-service`, which is the Rails application.

- **Eclipse**: `File > Import > Maven > Existing Maven Projects`, browse to
  `nearz-api-tests`, Finish.
- **Antigravity**: `File > Open Folder`, pick `nearz-api-tests`.

The file to show is:

```
src\test\java\com\nearz\api\Block1EnquiryTest.java
```

Scroll to `e2e001_enquiryToSettledBill`. It reads top to bottom in plain
English — that is deliberate, and it is the file to put on screen.

If you have a second file to show, make it
`src\test\java\com\nearz\api\ProductCrudTest.java` — plain Create / Read /
Update / Delete, the shape anyone who has seen REST Assured will recognise.

---

## How one test is built, in four pieces

Enough to answer "how does this work?" without opening every file.

**`Api.java`** — the address and the login. Base URL, JSON content type, the
auth token. Every test starts from `given().spec(Api.journey())`.

**`Steps.java`** — one method per business action: `createEnquiry`,
`convertEnquiry`, `bookAppointment`, `createBill`, `settleInFull`,
`refundInFull`. Each returns the id the next step needs. That chaining is what
makes it a journey rather than six unrelated calls.

**`Reports.java`** — takes a photograph of the salon's reports before the
journey and another after, then says what moved.

**`Money.java`** — works out what the bill *should* come to, in `BigDecimal`,
independently of the API. This is the file that makes the tests worth having.

And `BaseJourneyTest.java` holds the shared setup so each test stays short.

---

## If something goes wrong live

**`mvn` not recognised** — you are in a terminal that opened before the PATH was
set. Close it, open a new one.

**Not in the right folder** — `cd` to the folder with `pom.xml` in it.

**Everything fails with 401** — the tokens expired (they are good until
26 Nov 2026). Not something to fix on stage.

**A test fails on a number** — that is the suite doing its job. The failure
message names the report line, what was expected, what actually moved. It is a
perfectly good thing to show.

**Nothing to worry about if you see `Skipped: 18`** on a full run — those are
the partial-payment cases, switched off in the frontend on purpose.

---

## If you have spare time in the demo

Run a whole block instead of one journey — 25 journeys, about four minutes:

```powershell
mvn test "-Dtest=Block1EnquiryTest" "-Dsurefire.suiteXmlFiles="
```

Or the full suite, 81 tests, about thirteen minutes — too long to run live, but
the report from an earlier run is there to show.

---

## The honest framing

You did not write this by hand, and there is no need to pretend otherwise. What
you can say truthfully:

> "I built this with AI assistance. What I understand is what it checks and
> why: it follows the journey, calculates what the numbers should be, and
> compares. It has found fifteen defects so far, including one where a salon
> could read another salon's price list."

That is a better answer than pretending to have hand-written 82 tests, and it
is the answer a lead will respect.

`docs\DEFECTS.md` is the one-page list of everything found. Worth having open in
another tab.

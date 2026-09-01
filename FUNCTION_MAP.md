# Where every function in API-E2E-001 lives

All files are in `src\test\java\com\nearz\api\`.

**The shortcut that beats memorising this:** put the cursor on any method name
and press **Ctrl+Click** (or **F3** in Eclipse, **F12** in Antigravity). It jumps
straight to the definition. **Alt+<-** jumps back. If the lead asks "what does
`expectSettled` do?", do not scroll - Ctrl+Click it and the answer is on screen.

This file is the backup, and the map to have open in a second tab.

---

## Level 1 - called directly in the test

`Block1EnquiryTest.java`, lines 23-52.

| Line | Call | Defined in | Line |
|---|---|---|---|
| 25 | `Steps.today()` | `Steps.java` | 281 |
| 26 | `snapshot()` | `BaseJourneyTest.java` | 118 |
| 28 | `arriveAsEnquiry(...)` | `BaseJourneyTest.java` | 54 |
| 29 | `completeAppointmentFor(...)` | `BaseJourneyTest.java` | 63 |
| 30 | `billFor(...)` | `BaseJourneyTest.java` | 74 |
| 31 | `Steps.settleInFull(...)` | `Steps.java` | 215 |
| 33 | `snapshot()` | `BaseJourneyTest.java` | 118 |
| 34 | `assertSameDay(...)` | `BaseJourneyTest.java` | 243 |
| 36 | `assertBillIsPaidBy(...)` | `BaseJourneyTest.java` | 197 |
| 37 | `expectSettled(...)` | `BaseJourneyTest.java` | 137 |
| 42 | `Api.journey()` | `Api.java` | 29 |
| 48 | `assertEquals(...)` | TestNG library - `org.testng.Assert` | - |

`given()`, `when()`, `then()`, `statusCode()`, `extract()` on lines 42-45 are
**REST Assured's own methods**, not ours. They come from the `io.restassured`
library in `pom.xml`. Nothing to show for those.

Also worth knowing: `NewCustomer` (line 28) is not a class file of its own - it
is a Java `record` declared inside `BaseJourneyTest.java` at **line 47**. Four
fields: name, phone, enquiryId, id.

---

## Level 2 - what those call in turn

If the lead follows a thread down, this is where it goes.

### `arriveAsEnquiry` - `BaseJourneyTest.java:54`
| Calls | Where |
|---|---|
| `Steps.newPhone()` | `Steps.java:37` |
| `Steps.createEnquiry()` | `Steps.java:44` - `POST /salons/{id}/enquiries` |
| `Steps.convertEnquiry()` | `Steps.java:81` - `POST /enquiries/{id}/convert` |

### `completeAppointmentFor` - `BaseJourneyTest.java:63`
| Calls | Where |
|---|---|
| `Steps.bookAppointment()` | `Steps.java:100` - `POST /appointments`, with the retry loop |
| `Steps.setAppointmentStatus()` | `Steps.java:143` - `PATCH /appointments/{id}/status` |
| `catalogue.nextFreeSlot()` | `Catalogue.java:269` |
| `catalogue.doNotUse()` | `Catalogue.java:265` |

#### Why the loop says 25 - `Steps.java:105`

**It is a ceiling, not a plan.** Almost every run books on the FIRST try - one
POST, one appointment. 25 is the point at which the test gives up and says so
instead of hanging.

The loop exists because a slot can be taken between reading the calendar and
booking it. The test reads today's appointments, picks 10:30 with stylist 7,
sends the booking - and in those two seconds someone in the dashboard, or the
test running just before this one, took 10:30. The API answers *"already has a
booking"*. Retrying the next free slot is correct; failing the journey would be
a false alarm about a bug that does not exist.

Two things can send it round again, and each is handled differently:

| API says | What it means | Line | What the loop does |
|---|---|---|---|
| `already has a booking` | the slot went while we looked | 124 | try the next slot |
| `is not assigned to` | that stylist lost the skill | 127 | strike the stylist off (`doNotUse`), then try again |
| anything else | a real failure | 131 | stop, and print the status code and body |

**Why 25 and not 3 or 200.** The salon is seeded with 20 QA stylists x 19 slots
= 380 bookable places (`Catalogue.java`, `ROSTER` and `SLOTS`). 25 is comfortably
more than the number of collisions a single run can plausibly hit, and far
below 380, so it can never mask a genuinely full calendar. If all 25 are used
up the test throws a message naming the real cause - *"Today's calendar is
effectively full; wait for tomorrow or raise ROSTER"* (`Steps.java:136`) -
rather than a confusing booking error.

**The one-sentence answer:** it retries a slot that got taken while we were
looking, and 25 is just the cut-off that turns an infinite loop into a clear
failure message.

### `billFor` - `BaseJourneyTest.java:74`
| Calls | Where |
|---|---|
| `Steps.createBill()` (6-arg) | `Steps.java:152` |
| `Steps.createBill()` (7-arg, real one) | `Steps.java:168` - `POST /api/v1/billing/bills` |

The 6-arg version just calls the 7-arg one with `null` for the tax override.
That is method **overloading** - same name, different parameter list - if the
lead asks why there are two.

### `Steps.settleInFull` - `Steps.java:215`
| Calls | Where |
|---|---|
| `Steps.netPayable()` | `Steps.java:203` - asks the bill what it owes |
| `Steps.readBill()` | `Steps.java:272` - `GET /api/v1/billing/bills/{id}` |
| `Money.of()` | `Money.java:34` |

### `snapshot` - `BaseJourneyTest.java:118`
| Calls | Where |
|---|---|
| `Reports.snapshot()` | `Reports.java:56` - six GETs, one per report |
| `Reports.MONEY_TRAIL` (the list of six) | `Reports.java:38` |
| the KPI field names read from each report | `Reports.java:41` |

### `expectSettled` - `BaseJourneyTest.java:137` - the heart of it
| Calls | Where | What it works out |
|---|---|---|
| `gross()` | `BaseJourneyTest.java:101` | what the customer pays |
| `taxable()` | `BaseJourneyTest.java:94` | after discount, before tax |
| `tax()` | `BaseJourneyTest.java:111` | the GST |
| `subtotal()` | `BaseJourneyTest.java:89` | price list x quantity |
| `Money.discount()` | `Money.java:77` | |
| `expectMoved()` | `BaseJourneyTest.java:122` | called 15 times, once per number |

### `expectMoved` - `BaseJourneyTest.java:122`
| Calls | Where |
|---|---|
| `Reports.moved()` | `Reports.java:76` - after minus before |
| `Money.closeEnough()` | `Money.java:98` - paisa tolerance |
| `Reports.describe()` | `Reports.java:85` - the failure message |

### The arithmetic chain - `Money.java`
`gross()` at `BaseJourneyTest.java:101` calls down:

```
Money.netPayable()   Money.java:68   adds tax, then rounds to whole rupees
  |_ Money.gross()   Money.java:50   taxable + tax
      |_ Money.tax()  Money.java:45   taxable x rate
          |_ Money.round()  Money.java:40   2 dp, HALF_UP
```

**`Money.java:54-67` is the comment to show if the lead asks whether the tests
have ever caught anything.** It records the 20-paise failure and why the tests
were wrong, not the API.

### `assertBillIsPaidBy` - `BaseJourneyTest.java:197`
| Calls | Where |
|---|---|
| `Steps.readBill()` | `Steps.java:272` |
| `Money.at()` | `Money.java:93` - null-safe money read |

### `assertSameDay` - `BaseJourneyTest.java:243`
Calls `Steps.today()` - `Steps.java:281`.

---

## The setup that runs before the test

The test never calls these, TestNG does.

| What | Where | When |
|---|---|---|
| `prepareSalon()` | `BaseJourneyTest.java:37` | `@BeforeClass` - once per test class |
| `Api.configure()` | `Api.java:102` | from `prepareSalon` |
| `Catalogue.prepare()` | `Catalogue.java:82` | from `prepareSalon` |
| `Catalogue.openEveryDay()` | `Catalogue.java:117` | opens the salon 7 days |
| `Catalogue.service()` | `Catalogue.java:142` | finds or creates "QA Haircut" |
| `Catalogue.product()` | `Catalogue.java:172` | finds or creates "QA Shampoo" |
| `Catalogue.roster()` | `Catalogue.java:207` | 20 QA stylists assigned to the service |
| `Catalogue.readTodaysCalendar()` | `Catalogue.java:301` | reads every booking, paged |
| `Env` fields (`BASE_URL`, tokens) | `Env.java` | loaded from `config.properties` |

---

## One-line answer if asked "how is this organised?"

> Five files. `Steps` makes the API calls, `Money` does the arithmetic,
> `Reports` takes the before/after photographs, `Api` holds the URL and token,
> and `BaseJourneyTest` is the shared vocabulary the tests are written in. The
> test file itself has no HTTP in it - that is why it reads like English.

The one exception is lines 42-45 of the test, where the call is written inline
on purpose so the phone-number check is visible in the test rather than hidden
in a helper.

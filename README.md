# Nearz API end-to-end journey tests

REST Assured + TestNG. Tests the B2B salon management API by following a real
business journey and checking the reports afterwards.

```bash
mvn test
```

---

## What these tests actually do

A normal API test asserts `200 OK`. That tells you the endpoint answered. It
does not tell you the salon owner's revenue report is right.

These tests do something different. Each one follows a whole business journey,
carrying the ids forward from one call to the next, and then checks that the
reports moved by exactly the right amount:

```
create enquiry   -> enquiry_id
convert enquiry  -> customer_id
book appointment -> appointment_id
complete it
create bill      -> bill_id
take payment
                 -> then read every report and check the numbers
```

Before the journey starts, the test takes a snapshot of every report it could
affect. After it finishes, it takes another. Then it says what should have
changed, and by how much.

**The expected figures are worked out by the test, not read back from the API.**
That is the whole point. Asserting that the API's total equals the API's total
proves nothing. `Money.java` computes what a ₹1,000 service at 18% GST should
come to, and the test fails if the API disagrees.

---

## If you have seen REST Assured tutorials

`ProductCrudTest.java` is the familiar shape, on a real Nearz resource: Create,
Read, Update, Delete, one `@Test` each, Hamcrest matchers inline on `.then()`.
Read that file first — the journey tests are built out of exactly these calls.

Two habits from tutorials that this suite deliberately drops:

| Tutorial | Here | Why 
|---|---|---|
| `body().asString().contains("foo")` | `.body("data.name", equalTo(name))` | `contains` passes if the string appears *anywhere*, including inside an error message |
| runs against jsonplaceholder | runs against the real API | on jsonplaceholder POST does not create and DELETE does not delete, so those tests can never fail |

Three habits from tutorials that this suite **does** use, in `Api.java`:

```java
Api.created()   // 201 + application/json + under the response-time budget
Api.ok()        // 200 + application/json + under the response-time budget
```

A reusable `ResponseSpecification` — status, content type and a 5-second
response-time guard in one object, so a test writes `.then().spec(Api.ok())`
instead of repeating three assertions everywhere.

## Are we actually verifying the data? Three layers.

Every test applies three checks, in this order. Only the third one is unusual.

**Layer 1 — the call worked.** `.then().statusCode(201)` inside `Steps`. This
is table stakes, and on its own it proves almost nothing. The Nearz API answers
404 with HTTP 200 and a body of `{"error":"not_found"}`, so a test that stops
here can pass against a record that does not exist.

**Layer 2 — the record is right.** Read the object back and check its fields:

```java
assertEquals(bill.getInt("data.customer_id"), customer.id());
assertEquals(bill.getString("data.status"), "paid");
assertEquals(Money.at(bill, "data.amount_due").compareTo(ZERO), 0);
```

This catches a bill saved against the wrong customer, or one that took the money
without changing status.

**Layer 3 — the reports are right.** Snapshot every affected report before and
after, and assert each figure moved by an amount **this test computed**:

```java
BigDecimal gross = Money.gross(catalogue.servicePrice, catalogue.gstRate);
expectMoved(before, after, "sales.total_revenue", gross);
```

Layers 1 and 2 tell you the API is internally consistent. Only layer 3 tells you
the salon owner's revenue report is correct — and that is the thing the business
actually cares about.

### What these tests do NOT verify

Worth saying out loud so nobody assumes otherwise:

- **No database assertions.** Everything is checked through the API. If a value
  is wrong in the database but consistently wrong everywhere the API reads it,
  these tests will not see it.
- **No JSON-schema validation.** Field types and nullability are not checked
  against the swagger contract.
- **A response-time guard, but not performance testing.** `Api.ok()` fails a
  call that takes over 5 seconds. That catches an endpoint falling off a cliff;
  it is not a load test and does not measure percentiles.
- **No load or concurrency testing.** The suite is deliberately single-threaded.

## Reading one test

`Block1EnquiryTest.e2e001_enquiryToSettledBill()` reads top to bottom:

```java
var before = Reports.snapshot(SALON, Reports.MONEY_TRAIL);   // 1. photograph the books

int enquiryId    = Steps.createEnquiry(SALON, name, phone);  // 2. walk the journey,
int customerId   = Steps.convertEnquiry(SALON, enquiryId);   //    each id feeding
int appointmentId= Steps.bookAppointment(SALON, name, phone, catalogue);
Steps.setAppointmentStatus(appointmentId, "completed");      //    the next step
int billId       = Steps.createBill(customerId, catalogue, 1, 0, staffId, ZERO);
Steps.settleInFull(billId, "cash");

var after = Reports.snapshot(SALON, Reports.MONEY_TRAIL);    // 3. photograph again

BigDecimal gross = Money.gross(catalogue.servicePrice, catalogue.gstRate);

expectMoved(before, after, "sales.total_revenue",     gross);          // 4. say what
expectMoved(before, after, "payments.total_collected", gross);         //    should have
expectMoved(before, after, "customers.total_spend_in_range", gross);   //    moved
```

If `sales.total_revenue` moved by ₹1,150 instead of ₹1,180, the failure says so
in those words, with the before and after values.

---

## The files

| File | What it is |
|---|---|
| `Env.java` | Where the tests point and which salon token they use |
| `Api.java` | The request specs. A base URL, a content type, an auth header |
| `Money.java` | The billing arithmetic, in BigDecimal, worked out independently |
| `Reports.java` | Before/after report snapshots and the difference between them |
| `Catalogue.java` | Seeds the test salon and allocates free appointment slots |
| `Steps.java` | One method per business action, each returning the next id |
| `BaseJourneyTest.java` | Shared setup and the three layers of assertion |
| `Block1EnquiryTest.java` | API-E2E-001..025 — enquiry to a settled bill |
| `Block2PaymentTest.java` | API-E2E-076..100 — payments and settlement |
| `Block3RefundTest.java` | API-E2E-101..125 — refunds and reversal |
| `ProductCrudTest.java` | Plain CRUD on products — the shape to read first |

**75 tests: 56 run and pass, 19 skipped by design.** The skipped ones need
partial payments (switched off in the frontend) or the split-payment `modes[]`
payload. Each is fully written; turning one on is deleting its
`SkipException`.

---

## Three salons, three jobs

| Salon | Role | Rule |
|---|---|---|
| **4550** | journeys write here | empty to start with, so every delta is unambiguous |
| **4536** | the real QA salon | tests only READ here |
| **1725** | the second tenant | must keep its data, or the tenant-leak check has nothing to detect |

This separation is not decoration. Running journeys on 4536 put a permanently
invalid working-day record on the live QA salon. Swapping 1725 for an empty
salon silently stopped the tenant-leak test from detecting anything, because
there was no data left to leak.

---

## Why the suite runs single-threaded

Every journey asserts an *exact* report delta. Two journeys running at once
against the same salon each see the other's money in their "after" snapshot, so
both deltas are wrong. Measured: 18 tests failed under 8 threads and all of them
passed serially. `testng.xml` and the surefire config both pin this to 1.

---

## Things the API taught us that are not obvious

Every one of these was learned by the API refusing the obvious thing. They are
in the code as comments; they are collected here because they will bite anyone
adding a test.

- **`POST /appointments` has no `customer_id`.** The only customer handle it
  accepts is `on_behalf_of_mobile_no`. The enquiry → customer → appointment
  link is a *phone number*, not an id.
- **GST is a property of the bill, not the salon.** Send
  `tax: {gst_enabled: false}` and that bill is untaxed regardless of the salon
  setting. No global config change is needed to test a GST-off bill.
- **Salon 4550 charges 18% GST; salon 4536 charges 5%.** Never hardcode a rate —
  read `/api/v1/billing/tax_config`.
- **`finalize` takes `payment` singular**, and refuses a mismatched amount with
  "Payment amount does not match net payable total". Read the bill's
  `net_payable` first.
- **The bill's money field is `net_payable`, not `total`.**
- **Report KPIs are nested at `data.kpis.<name>.value`**, and on the `rows`
  endpoints the pagination `meta` sits *inside* `data`, not at the top level.
- **Enum values the API will name for you if you guess wrong:**
  `job_title` ∈ {Admin, Manager, Receptionist, Beautician},
  `unit` ∈ {ml, g, pcs, box}.
- **A stylist cannot be double-booked**, and the slot must fall inside their
  shift. `Catalogue.nextFreeSlot()` reads today's calendar and allocates only
  free places.
- **Appointments live for the whole day.** Capacity is 19 slots × 20 stylists =
  380 bookings a day, shared by every run that day.
- **404 comes back as HTTP 200** with `{"error":"not_found"}` (defect D1), so a
  test that only checks the status code can pass against a missing record.
- **`DELETE /salons/{id}/products/{id}` returns 500 but deletes anyway**
  (defect D15, found 28 Aug 2026). Confirmed: a second DELETE answers 404
  "product not found" and the product is gone from the list. The owner clicks
  Delete, sees an error, and the product disappears regardless.
- **The product list is `data.items` on `/salons/{id}/products` but
  `data.products` on `/api/v1/billing/products`** — same records, two names.
- **Prices come back as strings** (`"90.0"`), not numbers.
- **Reports are cached for 60 seconds** when the range includes today
  (`Reports::BaseController#cached`), with no invalidation on write. It appears
  inactive on QA — writes show up in the very next read — but if Redis is
  enabled anywhere else, "after" snapshots could go stale.

---

## Setup

1. Copy `src/test/resources/config.properties.example` to `config.properties`
2. Paste the three salon tokens
3. `mvn test`

Any value can be overridden without editing the file:

```bash
mvn test -DbaseUrl=https://staging.example.com -DjourneyToken=eyJ...
```

---

## Adding a journey

Add a method to `JourneyTest`. If it needs an action that does not exist yet,
add one method to `Steps` that performs it and returns whatever id the next step
needs. Nothing else has to change.

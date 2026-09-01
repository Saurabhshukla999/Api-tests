# API-E2E-001, line by line

For walking a lead through the code. Every line of the test, what it does, why
it is there, and where to click if they want to go deeper.

Open **`src/test/java/com/nearz/api/Block1EnquiryTest.java`** and scroll to
line 24.

---

## The 30-second version

> "One customer, start to finish. An enquiry walks in, becomes a customer, books
> an appointment, has it completed, gets billed and pays. Then we check the
> salon's six reports moved by exactly the amount we calculated ourselves."

---

## Line by line

### Line 23 — the label

```java
@Test(description = "API-E2E-001 enquiry through to a settled bill, every report checked")
```

`@Test` is what tells TestNG this is a test. The `description` becomes the
title in the Allure report, which is why the report reads like the lead's own
list rather than a wall of method names.

### Line 25 — remember the date

```java
LocalDate day = Steps.today();
```

Every report is read for `range=today`. If a run started at 23:59:58 and
finished at 00:00:03, the two snapshots would cover different days and every
number would be nonsense. Line 34 checks the date did not change.

*"Paranoid?"* — it costs nothing and turns a baffling failure into a plain one.

### Line 26 — photograph the books

```java
var before = snapshot();
```

Six GET requests: Sales, Payments, Customers, Services, Staff Performance,
Profit. Stores every KPI in a map like `sales.total_revenue -> 9440.00`.

Defined in `BaseJourneyTest.java:118`, does its work in `Reports.java`.

### Line 28 — the customer arrives

```java
NewCustomer customer = arriveAsEnquiry("QA E2E-001");
```

Two API calls: `POST /enquiries` then `POST /enquiries/{id}/convert`.
Returns a small record holding the name, phone, enquiry id and customer id.

The phone is generated fresh every time. **If we reused one, the convert would
attach to the existing customer instead of creating a new one, and the
"new customers +1" check would fail for the wrong reason.**

`BaseJourneyTest.java:54`

### Line 29 — book it and complete it

```java
completeAppointmentFor(customer);
```

`POST /appointments`, then `PATCH /appointments/{id}/status` to `completed`.

**The interesting part:** `POST /appointments` takes no `customer_id` at all.
The only customer handle it accepts is `on_behalf_of_mobile_no` — the salon
books a walk-in by phone number. That is why line 42 exists.

`BaseJourneyTest.java:63` → `Steps.java` `bookAppointment`

### Line 30 — raise the bill

```java
int billId = billFor(customer, 1, 0, ZERO);
```

Reads as: **1 service, 0 products, 0% discount.** `POST /api/v1/billing/bills`.

`BaseJourneyTest.java:74`

### Line 31 — take the money

```java
Steps.settleInFull(billId, "cash");
```

Reads the bill's `net_payable` back first, then posts exactly that amount.
The API refuses a mismatch with *"Payment amount does not match net payable
total"*, so guessing does not work.

Note the payload is `payment` **singular** — plural is rejected.

### Line 33 — photograph again

```java
var after = snapshot();
```

The same six reports. Now we have before and after.

### Line 34 — the midnight guard

```java
assertSameDay(day);
```

Fails with a plain explanation if the run crossed midnight, instead of blaming
the API for deltas that were never comparable.

### Line 36 — is the record right?

```java
assertBillIsPaidBy(billId, customer);
```

Reads the bill back and checks three things: its `customer_id` matches the
customer the enquiry converted to, its status is `paid`, and `amount_due` is
zero. `BaseJourneyTest.java:197`

### Line 37 — the heart of it

```java
expectSettled(before, after, 1, 0, ZERO);
```

Subtracts the two snapshots and checks **fifteen numbers** moved by exactly the
right amount. `BaseJourneyTest.java:137`

For a ₹1,000 service at 18% GST:

| Report figure | Must move by |
|---|---|
| `sales.total_revenue` | +1180 |
| `sales.bills_count` | +1 |
| `sales.service_revenue` | +1180 |
| `sales.total_discount` | +0 |
| `payments.total_collected` | +1180 |
| `payments.payments_count` | +1 |
| `customers.new_customers` | +1 |
| `customers.total_spend_in_range` | +1180 |
| `services.total_revenue` | +1180 |
| `services.services_sold` | +1 |
| `staff_performance.total_service_revenue` | +1180 |
| `profit.gross_revenue` | **+1000** |
| `profit.net_profit` | **+1000** |
| `profit.tax_collected` | **+180** |
| `profit.total_discount` | +0 |

**Where those numbers come from:** `Money.java` works them out in `BigDecimal`
from the price list. Nothing is read back from the API and compared to itself.

**Why Profit shows 1000 and Sales shows 1180:** Sales counts tax-inclusive,
Profit counts pre-tax. Both are correct. And it gives a free cross-check —
1000 + 180 must equal 1180, so two reports built by two different queries are
forced to agree.

### Lines 42–51 — the join that can break

```java
JsonPath profile = ... GET /salons/{salonId}/salon_customer_profiles/{id}
String mobile = ...
assertEquals(mobile, customer.phone(), "...");
```

Because the appointment was linked by **phone number**, not by id, this reads
the customer back and confirms the phone survived the whole chain.

If it ever did not, the appointment could not be joined to the customer — and
that is exactly the appointment-linking regression the lead named at the start.

---

## One level down, if asked

| File | What it holds |
|---|---|
| `Api.java` | the base URL, the auth token, the reusable response spec |
| `Steps.java` | one method per business action, each returning the id the next needs |
| `Reports.java` | `snapshot()` and the subtraction |
| `Money.java` | the billing arithmetic, in BigDecimal, worked out independently |
| `Catalogue.java` | seeds the test salon and allocates a free appointment slot |
| `BaseJourneyTest.java` | the shared helpers this test calls |
| `Env.java` | reads `config.properties` |

---

## Questions to expect

**"Why BigDecimal and not double?"**
0.1 + 0.2 is not 0.3 in floating point. A money test that rounds its own way
cannot tell you the API rounded wrong.

**"Why single-threaded?"**
Every journey asserts an exact report delta. Two running at once against the
same salon each see the other's money in their after-snapshot. Measured: 18
tests failed on 8 threads, all 18 passed serially.

**"Which salon does this write to?"**
4550, which was empty. 4536 — the real QA salon — is read-only for these tests.
1725 is the second tenant, kept for the isolation checks.

**"What if it fails?"**
The message names the report line, what should have moved, what did, and the
before and after values. The Allure report adds every request and response the
test made.

**"Does it clean up after itself?"**
No, and deliberately. Bills cannot be deleted once settled, and the assertions
are on *changes*, so leftover data does not affect correctness. Salon 4550
exists for exactly this.

**"How long does the whole suite take?"**
82 tests, about thirteen minutes. One journey is about forty seconds.

**"What has it actually found?"**
Fifteen defects — `docs/DEFECTS.md`. The most serious is D9: one salon could
read another salon's service catalogue and price list.

---

## Where "I do not know" is the right answer

Say it plainly for these. Guessing is worse.

- **Why Sales and Payments disagree by ~₹21,463 a month (D10).** The cause is
  not established. An early theory of mine was disproved by a controlled refund
  test. It needs a backend engineer.
- **Why the Appointments report does not move until a bill exists (Finding B).**
  Measured step by step, cause unknown.
- **Whether the Staff Performance "service revenue" including product sales
  (Finding A) is intended.** That is a product decision, not a test one.

Those three are written up with evidence. Being able to say "here is exactly
what we measured, and here is what we could not explain" is a stronger position
than an answer for everything.

# Defects found in the Nearz B2B salon API

Everything below was found by API testing between 26 August and 7 September
2026. The findings are independent of the tool that found them — most of D1–D15
came from the earlier Python suite; **D16, D17, D18, D19 and Findings D, E, F
and G came from the REST Assured journey suite** that replaced it, and each was
found by a journey that ran several mechanisms together rather than by testing
one endpoint at a time.

Full evidence, reproduction steps and raw numbers are in **`RUN_RESULTS.md`**.

---

## Re-verified 4 September 2026

Every defect that can be re-tested mechanically was re-run against the live QA
API. Repeat it any time with:

```
mvn test "-Dtest=VerifyDefectsTest" "-Dsurefire.suiteXmlFiles="
```

`VerifyDefectsTest` prints a verdict per defect rather than asserting, so one
still-open defect cannot hide the other twelve.

| | verdict | evidence today |
|---|---|---|
| **D9** cross-tenant catalogue | **STILL PRESENT** | salon 4550's token requested salon 1725's services → **200, 5 services returned** |
| **D2** unknown filter 500s | **STILL PRESENT** | `?status=bogus` → **500** on both waitlist_entries and customer_memberships |
| **D3** blank date accepted | **STILL PRESENT** | `date=""` → **200** |
| **D8** past-dated booking | **STILL PRESENT** | a booking dated 30 days ago → **200**; `DELETE` → 404 |
| **D12** unattributed revenue | **STILL PRESENT** | a bill with no `staff_id` moved Sales +1180 and Staff Performance **+0** |
| **D15** DELETE product 500s | **STILL PRESENT** | first DELETE **500**, second **404** — it deleted anyway |
| **D17** 60s report cache | **STILL PRESENT** | 11s after a booking: dashboard read 90, cache-busted read **91** |
| **Finding A** staff revenue counts products | **STILL PRESENT** | a 1,000 service + 200 product moved staff service revenue by **1,416**, not 1,180 |
| **Finding C** refund reverses its discount | **CHANGED 22 Oct 2026** | on 7 Sep a full refund left the discount at **+100**; on 22 Oct `sales.total_discount` and `profit.total_discount` stayed unchanged while the bill still carries its **100** discount - the refund now reverses it |
| **Finding D** SHOW omits the user | **STILL PRESENT** | `GET /appointments/{id}` → `data.user` absent |
| **D4** unparseable date | **PARTLY FIXED** | `date="not-a-date"` now answers **400**, not a silent empty list. The blank-date half (D3) still returns 200. |
| **D1** 404 served as 200 | ~~NOT REPRODUCED~~ → **STILL PRESENT** | *Narrowed 7 Sep.* Bills and appointments do answer 404, but the sweep this row asked for found `staffs#show` and `products#show` still answering **200 + `{"error":"not_found"}`** — the latter for a product that exists. See D1 below. |
| **D6** per_page cap | **INCONCLUSIVE** | only 94 bookings today, so the 100 cap was never exercised. The mismatch is still visible in the source. |
| **D11** card vs table | **INCONCLUSIVE** | salon 4536 has no bills this month, so there is nothing for the two figures to disagree about |

### Found since (7 September 2026)

| | verdict | evidence |
|---|---|---|
| **D19** refund keeps COGS booked | **OPEN** | sale +200 revenue / +100 COGS; full refund reversed the revenue and returned the stock, COGS **stayed at +100** — net profit permanently 100 short |
| **D20** refunded bills stay in `bills_count` | **OPEN** | today's sales report: 17 rows = 14 paid + 3 refunded, and `bills_count` = **17**; the spec says fully refunded bills are excluded |
| **D21** first-time visitors misclassified | **OPEN** | `new_customers` 12 / `returning_customers` 3 = exactly the three customers with **two** visits today, although none had visited before the window |
| **D22** phone starting `91` truncated | **OPEN** | a valid 10-digit phone `9100955749` is stored and returned as **00955749** (8 digits) |
| **D23** enquiry conversion 422 after the write | **OPEN (one occurrence)** | enquiry `3536` answered **422** on convert, yet customer `4229` was created with the same name and phone; a retry answered **409 "enquiry is already converted"**. Not reproduced on the two later runs |
| **Finding F** expense write route | **BY DESIGN, worth knowing** | `POST /salons/{id}/expenses` → **404**; the route is `POST /expenses`, salon taken from the token |
| **Finding G** auto-written Credit rows | **CORRECT, and load-bearing** | every completed appointment writes a Credit expense row; salon 4550 carries **160,000** of them and Operating Expenses correctly excludes all of it |

Not mechanically re-testable, and unchanged: **D5** and **D7** (broad sweeps),
**D10** (needs a month of data on 4536), **D13/D14** (a permanent data state on
4536 needing a database change), **D16**, **D18** and **D19** (all three tracked
by deliberately failing tests in the `known-defect` group).

D17 and D18 are also re-measured on every full run by
`Block10CompositeTest#e2e199_theFindingsStillHold`, which is written to go RED
the day either one is fixed — so a fix cannot land unnoticed.

**Nothing has been deleted from this document on the strength of one green
run.** "The symptom did not appear on QA today" is not "the cause was fixed".

---

## Critical

**D9 — one salon can read another salon's service catalogue and price list.**
`GET /salon_services`, `/salon_services/summary`, `/salon_services/categories`
and `/salon_services/export_all` take `salon_id` from the **query string** and
never check ownership. Reproduced with a `role: "customer"` token: salon A
downloaded salon B's full catalogue and its CSV price export. Salon ids are
sequential and enumerable — salon 100 returned 271 services, salon 4530
returned 260.

This is the one finding that no end-to-end journey can catch, because a journey
uses its own salon's id by definition. It is covered by the tenant-isolation
tests, which is why salon 1725 must keep its seeded data.

---

## Correctness of money and reports

**D10 — Sales revenue and Payments collected disagree.** About ₹21,463 per
month on salon 4536. Cause not established; needs a backend engineer. An early
diagnosis of mine (that Sales drops refunded bills while Payments keeps their
payments) was **disproved** by a controlled refund journey — a fresh full refund
reverses both correctly.

**D11 — the Sales card and the Sales table disagree.** The summary card counts
59 bills; the same report's unfiltered table lists 80.

**D12 — staff attribution is optional and its absence is invisible.** Revenue
billed without a `staff_id` never appears in Staff Performance, and
`top_performer` has named a stylist on a day with zero attributed revenue.

**Finding A — Staff Performance "service revenue" includes product sales.** A
bill of one ₹1,000 service plus one ₹200 product moves
`staff_performance.total_service_revenue` by ₹1,416 — the whole bill — not by
the ₹1,180 service portion. A stylist who sells shampoo is credited with
service revenue for it.

**Finding E — the Profit report is not the taxable amount, and the suite was
wrong about it for a hundred tests.** Not a defect: recorded because it took two
corrections to the test model and the reasoning is worth keeping.

Measured on salon 4550, 4 Sep 2026, while building Block 5:

| basket | expected (old model) | actual | why |
|---|---|---|---|
| service + product | `net_profit` +1200 | **+1100** | Net Profit deducts **cost of goods sold** — a ₹200 shampoo bought for ₹100 adds 200 to revenue but only 100 to profit |
| service + product + 10% | `gross_revenue` +1080.00 | **+1079.60** | Gross Revenue is `net_payable − tax`. The bill rounds 1,274.40 down to 1,274, so the salon keeps 40p less revenue than the pre-round taxable figure suggests |

Both were invisible until a basket carried **a product and a discount at once**:
a service-only basket has no cost of sale, and a whole-rupee basket has no
round-off. `expectSettled` now models both, and Block 1 still passes unchanged.

Worth noting for anyone reading the Rails source: `ProfitQuery` in the repo
computes `gross_revenue = bills.sum(:net_payable)` — tax included — which is
**not** what the deployed API returns. The repo checkout and the QA deployment
are not the same version. Trust the measurement.

**D18 — a bill raised through the billing module is never linked to its
appointment, so every booked customer is reported as a walk-in.**
`bills.appointment_id` exists as a column and two reports depend on it:

```ruby
# Reports::AppointmentsQuery#walk_ins_for
bills.where(status: PAID, billed_at: window, appointment_id: nil).count

# Reports::MarketingQuery
Bill.where(...).group(:appointment_id).sum(:net_payable)   # promo attribution
```

But `POST /api/v1/billing/bills` accepts no appointment id, and nothing under
`app/controllers/api/v1/billing` or in the bill composer ever sets one. A
customer who booked at 10:00 and paid at 10:30 is filed as a walk-in, and any
promo code they used is attributed to nobody.

Salon 4550 currently reports **94 appointments and 75 walk-ins** on a day when
every bill on it came from a booked appointment.

The open question for the backend team is which of these is true: the billing
module is supposed to link and does not (a bug), or the calendar has its own
billing path that links (in which case `walk_ins` still miscounts every POS sale
made to a booked customer). Tracked by a deliberately failing test —
`Block4AppointmentTest#e2e049_appointmentIdReachesTheBill`, group
`known-defect`.

**D17 — a write is invisible in the reports for up to 60 seconds.**
`Reports::BaseController#compute_ttl` caches every report summary for
`60.seconds` when the range includes today, and **no write invalidates it**.
Measured on salon 4550, 30 Aug 2026, one appointment created at t=0:

| | plain read | same read with a unique query param |
|---|---|---|
| t=0 | 102 | 102 |
| t=20s | **102** | **103** |

The appointment exists; the report is serving a minute-old copy. The salon owner
books someone, opens the dashboard, and it is not there.

This is inconsistent within the codebase, which is what makes it a defect rather
than a design choice. `EnquiriesController` busts its own cache after every
write, commented:

> "The overview KPI and the recent-enquiries strip are cached for 60s; a write
> the user just made should be visible immediately, not a minute later."

`AppointmentsController#create`, `#update` and the billing controllers do
nothing of the kind.

**Finding B is now explained and closed by D17.** It was previously recorded as
"the Appointments report does not move until a bill exists, cause unknown".
There was no connection to billing: the original measurement simply crossed the
60-second boundary at around the point the bill was created. Kept here rather
than deleted, because a wrong diagnosis that got corrected is worth showing.

The suite reads around the cache — `Reports.snapshot` sends a unique `_qa`
parameter, which changes the key `cache_key()` MD5s — so its assertions measure
the database rather than the cache. The staleness itself is asserted on its own
in `Block4AppointmentTest`.

One part of the original Finding B stands and is unrelated to caching: two
customer counters move at two different moments — `total_customers` on bill
creation, `new_customers` on payment.

**D16 — swapping the service on an appointment leaves the OLD price on it.**
`PATCH /appointments/{id}` with
`appointment_services_attributes: [{id, salon_service_id, staff_id}]` moves the
line onto the new service correctly — `service_name` and `staff_id` both
follow — but the line's `amount` still shows the price of the service it no
longer has. Reproduced 30 Aug 2026 on salon 4550: a booking swapped from
QA Haircut (₹1,000) to QA Blow Dry (₹600) came back reading **1000.0**.

Cause, in the application:

```
app/models/appointment_service.rb          before_create :set_amount
app/services/appointment_update_service.rb line.update!(salon_service_id:, staff_id:)
```

`amount` is a stored column stamped once on create. `apply_service_lines`
**updates** the row, so the callback never fires again. `service_name` and
`price` are derived methods, which is why they look right and `amount` does not.

Impact is on what the customer is shown, not on the books: the bill is composed
from its own items, so revenue and tax stay correct. The appointment card just
quotes the wrong price. Tracked by a deliberately failing test in the
`known-defect` group — `Block1EnquiryTest#d16_swappedServiceKeepsOldPrice`.

**D19 — a full refund gives back the revenue and the stock, but keeps the
cost of goods sold booked.**
Measured on salon 4550, 7 Sep 2026. One product, cost ₹100, sells for ₹200,
sold and then fully refunded:

| | `gross_revenue` | `service_cogs` | `net_profit` | stock on shelf |
|---|---|---|---|---|
| before | 34,279.60 | 3,100.00 | 31,179.60 | 10 |
| after sale | 34,479.60 | 3,200.00 | 31,279.60 | 9 |
| after refund | 34,279.60 | **3,200.00** | **31,079.60** | 10 |
| net effect | 0 | **+100** | **−100** | 0 |

Revenue unwinds. Tax unwinds. The bottle physically comes back to the shelf and
is available to sell again. Its cost does not unwind — and will be charged a
second time when that same bottle is sold again.

So the error **compounds**. Sell and refund one bottle ten times and the books
carry ₹1,000 of cost for goods that never left the building. Net Profit is
understated by the cost price of every product ever returned, and nothing on
the Profit report explains why revenue and profit disagree — `service_cogs`
just quietly runs high.

This is not the part-refund rule. A **part** refund is a price adjustment: the
goods stay sold, so the cost correctly stays booked (asserted in API-E2E-157).
This is a **full** refund — status `refunded`, stock returned — where the cost
should come back with the goods.

The impact is on the owner's Profit report and Net Margin %, not on the
customer's money: the refund itself pays out the right amount. Tracked by a
deliberately failing test in the `known-defect` group —
`Block7InventoryTest#e2e168_refundReversesCogs`.

*Where to look.* In the repo checkout, `Reports::ProfitQuery` scopes both the
revenue and the cost queries to `bills.status: %i[paid partial]`:

```ruby
def date_filtered_bills          # revenue
  Bill.where(salon_id:, status: %i[paid partial]).where(billed_at: @range.range)
end

def calculate_cogs               # cost
  BillItem.joins(:bill)
          .where(bills: { salon_id:, status: %i[paid partial], billed_at: @range.range })
          .pick(Arel.sql(cost_expression))
end
```

On those scopes a bill that goes to `refunded` should drop out of BOTH. The
deployed API drops it from revenue and keeps it in COGS, so whatever is running
on QA does not match this file — the same divergence already noted for
`gross_revenue`, which the checkout computes tax-inclusive and the deployment
returns net of tax. **The measurement above is the fact; this code is only
where to start looking.** The specific question for the backend team is why the
COGS query's bill-status filter does not match the revenue query's.

Two other things are worth confirming while that is open: whether a **part**
refund should reduce COGS proportionally (this suite assumes not — the goods
stayed sold), and whether returning stock to the shelf is even correct for a
refund of a consumable.

**Finding D — GET and PATCH on an appointment disagree about the user.**
`AppointmentsController#show` serialises with `exclude_user: true` while
`#update` uses `exclude_user: false`, so `GET /appointments/{id}` carries no
user block at all. A rename read back with a follow-up GET always looks like
`null` — the change did save. Not a bug so much as a trap: it will make someone
report a working feature as broken. API-E2E-009 reads the PATCH response
instead.

**Finding C — a fully refunded bill's discount (behaviour changed 22 Oct 2026).**
On 7 Sep 2026 a 1,000 service at 10% off, paid then fully refunded, moved Sales
revenue by 0 but Sales discount by **+100**: Profit then reported 0 revenue
alongside 100 of discount given. On 22 Oct 2026 the same journey
(`Block3RefundTest#e2e117`) found the bill record still carrying its **100**
discount and its 1,062 collected/refunded; the settlement had added the 100 to
both discount totals and the refund brought them back to **1,930** - the refund
now reverses the discount as well as the revenue. That is the consistent ledger,
so the test was updated to check all three points: +100 on settlement, 0 after
the refund, and the bill record still carrying its discount.

---

## Error handling

**D1 — every 404 returns HTTP 200** with a body of `{"error":"not_found"}`. Any
test that checks only the status code passes against a record that does not
exist.

*Narrowed 7 Sep 2026.* The 4 Sep re-verification recorded "not reproduced" after
checking bills and appointments, which do now answer a proper 404. That verdict
was too broad — it was drawn from two endpoints and stated as a fact about the
API. Two more were found still doing it while building Blocks 6 and 7:

| endpoint | asked for | answers |
|---|---|---|
| `GET /salons/{id}/staffs/{staffId}` | a real staff id | `200` + `{"error":"not_found"}` |
| `GET /salons/{id}/products/{productId}` | a **real, existing** product | `200` + `{"error":"not_found"}` |

The products case is the worse of the two: the record exists and the endpoint
denies it. `Steps.productRow` reads stock from the product LIST for exactly this
reason — a test that read `products#show` would see nothing, call it a stock of
zero, and pass.

**D15 — `DELETE /salons/{id}/products/{id}` returns 500 but deletes anyway.**
Confirmed: after the 500 the product is gone from the list and a second DELETE
answers `404 "product not found"`. The salon owner clicks Delete, sees an error,
and the product disappears regardless. Tracked by a deliberately failing test in
the `known-defect` group — run `mvn test -Dgroups=known-defect`.

**Finding F — the expense write route is not where the read route is.**
`GET /salons/{id}/expenses` works, but `POST /salons/{id}/expenses` answers
**404**. The write route is the top-level `POST /expenses`, and the salon is
taken from the token rather than the path:

```ruby
# ExpensesController
before_action :get_salon, only: [:create]
def get_salon = @salon = Salon.find_by_user_id(@current_user.id)
```

Not a bug — but it cost a morning, and it means an integrator cannot write an
expense for a salon they can read. Both a flat body and one nested under
`expense` are accepted. `PUT` and `DELETE /expenses/{id}` both work and the
Profit report follows them exactly.

**Finding G — every completed appointment auto-writes a CREDIT expense row.**
`{name: "Appointment", expense_type: "Credit", amount: <service price>}`. Salon
4550's ledger reports **total_credits 160,000 against total_debits 0**. These
are income rows, not costs, and `operating_expenses` correctly excludes them
(measured: a Credit of ₹777 moved Operating Expenses by ₹0.00). Recorded
because the exclusion is load-bearing and undocumented — if it were ever
dropped, this salon would appear to have spent its entire revenue on nothing.
Pinned by `Block8ExpenseTest#e2e175_creditIsNotAnExpense`.

One row is worth a second look: an auto-written Credit for a **QA Blow Dry**
(₹600 in the price list) carries `amount: 1000.0`. Not yet chased down; it may
be the same stale-`amount` cause as D16.

**D2 — an unknown filter value causes a 500.** `?status=bogus` on
`/waitlist_entries` and `/customer_memberships`.

**D7 — nineteen write endpoints return 500 on a wrong field type**, rather than
422.

---

## Contract and validation

**D5 — thirty-five endpoints return `null` where swagger declares the field
non-nullable.**

**D3 — a blank date silently becomes today** instead of being rejected.

**D4 — an unparseable `for_date` returns an empty list** instead of an error.

**D6 — pagination disagrees with its own concern.** `Paginatable` declares
`MAX_PER_PAGE = 100`; three controllers use 200.

**D8 — past-dated appointments are accepted**, and
`DELETE /appointments/{id}` returns 404.

**D13 / D14 — an invalid weekday was accepted and cannot be removed.** Writing
`{"weekday": 9}` to `PUT /working_days` was accepted, and that endpoint turns
out to be a partial update rather than a whole-set replace — so weekday 9 is now
permanently on salon 4536 and needs a database change to remove. This was my
own mistake while testing, and it is the reason journeys now run only on the
dedicated salon 4550.

**Swagger's own examples do not work** in at least three places — the documented
request body is rejected by the endpoint that documents it.

---

## Smaller inconsistencies worth knowing

- The product list is `data.items` on `/salons/{id}/products` but
  `data.products` on `/api/v1/billing/products` — same records, two names.
- Prices come back as strings (`"90.0"`), not numbers.
- ~~Reports are cached for 60 seconds… It appears inactive on QA — writes show
  up in the very next read.~~ **Wrong, and corrected 30 Aug: the cache is very
  much active on QA.** This line was written before the cache was measured
  properly, and it is left here struck through because it is the reason the
  60-second staleness went unnoticed for so long. See **D17**.
- `POST /appointments` accepts no `customer_id`. The only customer handle is
  `on_behalf_of_mobile_no`, so the enquiry → customer → appointment link is a
  phone number, not an id.
  **Corrected 22 Oct 2026:** `customer_id` IS accepted for a converted enquiry -
  tests now send it, and `on_behalf_of_mobile_no` remains the walk-in handle.
  The swagger spec still omits the key; see **SW-4** below.

---

## What was verified as correct

Not everything was broken. These were checked and hold:

- **Billing arithmetic** — 1,140 basket assertions, all passing, to the paisa.
- **Forward-path report deltas** — a bill raised and settled moves Sales,
  Payments, Services, Staff, Profit and Customer spend by exactly the right
  amounts, on both a 5% GST salon and an 18% one.
- **Full refunds reverse cleanly** across every report and the Dashboard, and
  leave a `refund_amount` and `refunded_at` behind rather than erasing the sale.
- **Payment is idempotent** — the settlement call sent twice, then three times,
  collects once.
- **Over-refunds are refused** and the books do not move.
- **Authentication** — 357 checks, all passing. No endpoint served data without
  a token; a self-signed token claiming `role: admin` was refused.
- **Tenant isolation on path-scoped routes** — 240 of 244 passing. The four
  failures are D9.

---

## Swagger documentation drift (22 Oct 2026)

Found while matching the existing Java tests against
`api-service/swagger/v1/*.json` (13 files, 209 operations / 176 endpoints).
These are **spec defects, not API defects**: the suite follows observed live
behaviour and the drift is recorded here so the spec can be corrected. Full
comparison: `docs/SWAGGER_KEY_MATRIX.md`.

| id | The spec says | The API does | Evidence |
|---|---|---|---|
| **SW-1** | `POST /salons/{salon_id}/expense` and `PUT/PATCH/DELETE /salons/{salon_id}/expense/{id}` | `POST /expenses`, `PUT/DELETE /expenses/{id}` work; the salon-scoped write route answers 404 and the salon comes from the token | `Block7InventoryTest` line 480, `Block8ExpenseTest` line 27, `Steps.createExpense` |
| **SW-2** | `PUT /salons/{salon_id}/settings` | `PATCH` is accepted and is what the round-off toggle uses | `Steps.setRoundOff` |
| **SW-3** | bill line price key is `price` | `unit_price` is what the API prices from | every `createBill` helper, and the totals assertions agree with the catalogue price, not with `price` |
| **SW-4** | `POST /appointments` has no `customer_id` | `customer_id` is accepted and links the appointment to the converted enquiry | `Steps.bookAppointment`, added 22 Oct |
| **SW-5** | no `/services` operation in `v1/` | `GET /services` returns the master catalogue; its id is the `service_id` that `POST /salon_services` requires | `Catalogue.masterServiceId` |
| **SW-6** | `PUT /appointments/{id}` updates the booking | `PATCH /appointments/{id}` performs the update | `Steps.attemptAppointmentChange`, `Steps.changeAppointment` |
| **SW-7** | `PATCH /api/v1/billing/bills/{id}` replaces a draft basket | `PUT /api/v1/billing/bills/{id}` performs the replacement | `Steps.updateBillItems` |
| **SW-8** | `sales.service_revenue` is GST-included and `service_revenue + product_revenue = total_revenue` | `service_revenue` is **before GST**; the live tile is labelled "Service Revenue (before GST)" and `service + product + gst = total` (measured: 13,900 + 200 + 2,538 = 16,638) | `BaseJourneyTest.expectSettled` now asserts the net figure; `Block1EnquiryTest#e2e001` |
| **SW-9** | bill `tax` accepts `gst_enabled` and `mode` only | a GST-off bill is refused with *"Give a reason for switching GST off on this bill"* unless `tax.reason` is present; the key is undocumented | `Steps.createBill`, `Block1EnquiryTest#e2e014` |
| **SW-10** | appointment SHOW `service_name` "falls back to the catalogue name when the salon has not set a custom name" | SHOW always returns the **catalogue** name in `service_name` and the salon's label in `custom_name` (`Blow Dry` / `QA Blow Dry`); the calendar list promotes the custom name into `service_name` | `Block1EnquiryTest#e2e008` now asserts `custom_name` |
| **SW-11** | the profit summary is "Revenue minus cost of goods and operating expenses", and `total_cost` is described only as "Total Cost" | `total_cost` also includes **`staff_commission`**: live today `0 opex + 3,900 COGS + 1,300 commission = 5,200`, and `net_profit = gross - total_cost`. The stated formula is short by the commission | `Reports.PROFIT` now reads `staff_commission`; `Block8ExpenseTest.assertIdentityHolds` asserts all three terms |
| **SW-12** | the inventory tab's `low_stock` array and `summary.low_stock` share "identical definition ... so the KPI and the rows can never disagree"; no cap is documented | the array is a **10-row preview** (sorted by stock) while the KPI counts every product at or below reorder (21 live). The complete list is `/dashboard/action_center/low_stock`; a product at 9 vs reorder 9 is in the full list but outside the preview | `Steps.lowStockSeverity` reads the action-center list; `Block9DashboardTest#e2e182` asserts the KPI delta |

Response-schema gaps that block assertions (not request alignment):

- `POST /salon_services` 201 is `schema: {type: object}` - no fields documented,
  so the documented request keys `gender`, `duration_in_minutes`, `discount` and
  `description` cannot be asserted on the create response.
- `POST /api/v1/billing/bills` 201 carries no documented body schema; the suite
  asserts the id and then the money through report deltas rather than fields.

---

## Found 22 October 2026 (Block 1 against the live journey tenant)

These three were found in the same serialized rerun and are **API behaviour**,
not test defects. The assertions stay as they are, so the affected journeys keep
failing until the API changes; the rest of the block proceeds.

### D20 — a fully refunded bill is still counted in `sales.bills_count`

The spec is explicit (`reports.json`, Sales summary):

> `bills_count` — "Bills raised in this period, after refunds. A fully refunded
> bill is excluded here."

Live, on salon 4550 with `range=today`:

```
GET /salons/4550/reports/sales/rows?range=today&per_page=100
  17 rows: 14 paid + 3 refunded
GET /salons/4550/reports/sales/summary?range=today
  bills_count = 17
```

So the refunded rows are not excluded. `Block1EnquiryTest#e2e020` raises a
bill, fully refunds it, then raises and settles a second one; the test expects
`bills_count` to move by **1** and it moves by **2**. Revenue, payments,
discounts and refund totals all reverse correctly — only the count is wrong.

It is also the only failure left in Block 10. On the second full run of
22 Oct 2026, four composite journeys each created a bill, refunded it, and then
did more trade; every one saw `bills_count` include the refunded bill:

| case | expected movement | actual movement | `bills_count` |
|---|---|---|---|
| `e2e192_threeSalesOneRefund` | +2 | +3 | 264 → 267 |
| `e2e193_discountedBasketRefundedAndRebought` | +1 | +2 | 267 → 269 |
| `e2e194_fourStatesReconcile` | +2 | +3 | 269 → 272 |
| `e2e200_theWholeSystem` | +1 | +2 | 296 → 298 |

The same cases failed the same way on the first run (215 → 218, 218 → 220,
220 → 223, 245 → 247), so this is not an artefact of one run.

Block 3 repeats it for a refund-then-trade-again journey:
`e2e120_refundThenBuyAgain` expected +1 and saw +2 (376 → 378), and
`e2e125_settleRefundSellAgain` expected +1 and saw +2 (383 → 385) on the
22 Oct 2026 rerun. The first Block 3 run saw the same shifts (339 → 341 and
346 → 348). These two are the only failures left in that block.

### D21 — "First-Time Visitors" only counts customers with exactly one visit

Swagger (`reports.json`, Customers summary):

> `new_customers` — "Customers who visited in this window and had never visited
> before it."

Live, today:

```
new_customers              = 12
returning_customers        = 3
new_customers_in_range     = 27   (records created today)
```

The three "returning" customers are exactly the three customers who have **two
completed visits today** (customer ids 3959, 3978, 3966). The 27 records created
today split 12 with one visit, 12 with none and 3 with two — there is no
customer whose first visit predates the window. A brand-new customer billed
twice in one day is therefore reported as returning, contradicting "had never
visited before it".

A fully refunded sale is correctly treated as **no visit**: the customer profile
documents a visit as "something the customer was charged for", counted from
bills, and the customer from `e2e004` (id 3977, one completed appointment and a
fully refunded bill) has `total_visits = 0`. `e2e004` was asserting the
opposite and has been corrected to expect no movement; the two-visit case is a
straight contradiction of the documented definition and remains red.

### D22 — a valid 10-digit phone beginning `91` is truncated to 8 digits

`Steps.newPhone()` generated `9100955749` for `e2e009`. The enquiry was created
with that number, but the converted customer profile returned:

```
expected 9100955749
actual   00955749
```

The staff-phone documentation says a leading `91`/`0` trunk prefix is dropped
when it precedes a 10-digit national number (`+91 98765 43210` → `9876543210`).
Here the **whole number is already 10 digits**, and stripping `91` leaves 8 —
so a legitimate local mobile is corrupted. The generator now avoids the `91`
prefix so the journey can test the customer-rename join rather than this
normaliser; the API defect remains.

### D23 — enquiry conversion can answer 422 after creating the customer

On the first Block 10 run, the eighth enquiry conversion in
`e2e198_tenTransactions` answered **422**. The customer profile was nevertheless
created:

```
enquiry 3536  phone 9502686662  status "new" (list, stale)
customer 4229  name "QA E2E-198 #8"  phone 9502686662   (search)
POST /salons/4550/enquiries/3536/convert  ->  409
  {"error":"enquiry is already converted"}
```

The retry was not blind: the customer was looked up first and found, so the
write had succeeded. The test passed on two later runs, so this is recorded as
an observed response/write split rather than a reproducible failure.
`Steps.convertEnquiry` now throws with the redacted response body, so the next
occurrence carries the reason instead of only the status code.

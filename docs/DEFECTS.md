# Defects found in the Nearz B2B salon API

Everything below was found by API testing between 26 and 30 August 2026. The
findings are independent of the tool that found them — most came from the
earlier Python suite; D15, D16 and Finding D came from the REST Assured suite
that replaced it.

Full evidence, reproduction steps and raw numbers are in **`RUN_RESULTS.md`**.

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

**Finding D — GET and PATCH on an appointment disagree about the user.**
`AppointmentsController#show` serialises with `exclude_user: true` while
`#update` uses `exclude_user: false`, so `GET /appointments/{id}` carries no
user block at all. A rename read back with a follow-up GET always looks like
`null` — the change did save. Not a bug so much as a trap: it will make someone
report a working feature as broken. API-E2E-009 reads the PATCH response
instead.

**Finding C — a fully refunded bill keeps its discount.** A ₹1,000 service at
10% off, paid then fully refunded, moves Sales revenue by ₹0 and Sales discount
by **+₹100**. Profit then reports ₹0 revenue alongside ₹100 of discount given.

---

## Error handling

**D1 — every 404 returns HTTP 200** with a body of `{"error":"not_found"}`. Any
test that checks only the status code passes against a record that does not
exist.

**D15 — `DELETE /salons/{id}/products/{id}` returns 500 but deletes anyway.**
Confirmed: after the 500 the product is gone from the list and a second DELETE
answers `404 "product not found"`. The salon owner clicks Delete, sees an error,
and the product disappears regardless. Tracked by a deliberately failing test in
the `known-defect` group — run `mvn test -Dgroups=known-defect`.

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
- Reports are cached for 60 seconds when the range includes today
  (`Reports::BaseController#cached`), with no invalidation on write. It appears
  inactive on QA — writes show up in the very next read — but if Redis is
  enabled anywhere else, before/after snapshots could go stale.
- `POST /appointments` accepts no `customer_id`. The only customer handle is
  `on_behalf_of_mobile_no`, so the enquiry → customer → appointment link is a
  phone number, not an id.

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

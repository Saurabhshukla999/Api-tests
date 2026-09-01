# Run results — 26 Aug 2026

Target: `https://www.testnearz.co.in` · salon 4536 · owner token (expires 24 Nov 2026)

## Tally

| Suite | Run | Pass | Fail | Notes |
|---|---:|---:|---:|---|
| `smoke` | 4 | 3 | 0 | 1 skip — no second salon configured |
| `auth` | 357 | 357 | 0 | clean sweep |
| `tenant` | 244 | — | — | **skipped** — needs `OTHER_TOKEN` |
| `idor` | 37 | — | — | **skipped** — needs `OTHER_TOKEN` |
| `schema` | 336 | 301 | 35 | one root cause (D5) |
| `robustness` | 763 | 724 | 39 | three root causes (D1, D2, D6) |
| `functional` | 299 | 296 | 3 | two root causes (D1, D3) |
| **Total run** | **1,758** | **1,681** | **77** | **6 distinct root causes** |

`destructive` (164) and the second-salon suites were not run.

---

## D1 — Every 404 in the app returns HTTP 200

**Severity: high · affects every client**

```
GET /salons/4536/anything-that-does-not-exist
→ 200 OK
  {"error":"not_found"}
```

`config/routes.rb` ends with `get '/*a', to: 'application#not_found'`, and
`ApplicationController#not_found` is:

```ruby
def not_found
  render json: {error: 'not_found'}     # no status: → defaults to 200
end
```

Any client that branches on `response.ok` / `status < 400` treats a typo'd or
removed endpoint as a successful empty response. Also reached via
`/reports/../sales/filters` and `/reports/sales;DROP TABLE bills/filters`.

**Fix:** `render json: {error: 'not_found'}, status: :not_found`

Found by: `test_unknown_route_returns_json_404`, `test_unknown_report_key_is_404_not_500`

---

## D2 — Unknown enum value in a filter returns 500

**Severity: high · 2 endpoints**

```
GET /salons/4536/waitlist_entries?status=waiting   → 200
GET /salons/4536/waitlist_entries?status=bogus     → 500 {"status":500,"error":"Internal Server Error"}
GET /salons/4536/customer_memberships?status=bogus → 500
```

Any value outside the enum crashes — `o'brien`, `bogus`, `<script>`, a 5,000
character string, all identical 500s. The shape is an unvalidated value reaching
an ActiveRecord enum scope, which raises on an unknown key.

The dashboard can only ever send values from its own dropdown, so this is
unreachable from the UI.

**Fix:** validate `status` against the enum before querying; return 422.

Found by: `test_hostile_filter_values_do_not_crash` (10 of 11 payloads × 2 endpoints)

---

## D3 — Empty string bypasses date validation

**Severity: medium · silent wrong answer**

```
GET /salons/4536/attendance/daily?date=tomorrow   → 422 {"error":"date must be YYYY-MM-DD"}
GET /salons/4536/attendance/daily?date=2026-13-01 → 422 {"error":"date must be YYYY-MM-DD"}
GET /salons/4536/attendance/daily?date=           → 200 {"data":{"date":"2026-08-26","is_today":true,...}}
```

Validation is correct for malformed dates and absent for the empty string, which
silently resolves to today. A client sending an uninitialised date field gets
today's roster labelled as the requested day.

**Fix:** treat blank as invalid, or as "omitted" — but pick one deliberately.

Found by: `test_invalid_dates_are_rejected_not_defaulted`

---

## D4 — Unparseable `for_date` returns an empty list, not an error

**Severity: medium**

```
GET /salons/4536/waitlist_entries?for_date=o'brien → 200 {"data":{"waitlist_entries":[],"meta":{"total":0}}}
```

Reads as "nobody is on the waitlist" rather than "your filter was invalid."

Found by: manual isolation during triage of D2.

---

## D5 — 35 endpoints return `null` where swagger declares a non-nullable type

**Severity: high for the mobile app · invisible in the dashboard**

The single largest cluster: 35 of 336 schema tests. Examples:

| Endpoint | Field | Declared | Actual |
|---|---|---|---|
| `dashboard/overview` | `cancelled_appointments[].reason_code` | `string`, enum | `null` |
| `dashboard/overview` | `cancelled_appointments[].reason_label` | `string` | `null` |
| `dashboard/overview` | `cancelled_appointments[].customer` | `string` | `null` |
| `reports/sales/summary` | `kpis.*.change_pct` | `number` | `null` |
| `working_days` | `break_start`, `break_end` | `string` | `null` |
| `working_days` | `second_shift_start`, `second_shift_end` | `string` | `null` |

JavaScript does not care, so the dashboard renders fine. A typed client —
Swift `Codable`, Kotlin data classes, TypeScript generated from this same
OpenAPI document — fails to decode the response. This is the API the customer
mobile app consumes.

**Fix:** either mark these `nullable: true` in `swagger/v1/*.json`, or stop
returning null. It is a one-line decision per field, but it has to be made —
right now the published contract and the deployed behaviour disagree.

Found by: `test_200_matches_documented_schema`

Full list of the 35 endpoints: see the failure list in `reports/report.html`.

---

## D6 — Pagination has two different maximums

**Severity: low · design inconsistency**

`app/controllers/concerns/paginatable.rb` sets `MAX_PER_PAGE = 100` and its own
comment says the clamp rules live there "in one place instead of being
re-derived per controller." Three controllers re-derive them anyway:

| File | `MAX_PER_PAGE` |
|---|---|
| `concerns/paginatable.rb` | 100 |
| `attendance/base_controller.rb` | **200** |
| `attendance/time_blocks_controller.rb` | **200** |
| `products_controller.rb` | **200** |

Observed: `per_page=1000` returns `meta.per_page = 200` on `/attendance/logs`,
`/attendance/time_blocks`, `/salon_services`, `/products`, `/staff`.

Not a bug in itself — but the concern's stated purpose is no longer true, and
200 rows per page is twice the documented ceiling.

Found by: `test_per_page_is_clamped_not_rejected`

---

## Corrections to the test suite

Ten failures were the tests being wrong, not the API. Both are fixed:

| What | Why it was wrong |
|---|---|
| `test_success_envelope_is_consistent` (7) | Exports, downloads and the bulk-upload template stream a file and correctly bypass `json_response`. Now skips non-JSON responses. |
| `test_action_center_counter_matches_its_rows` (3) | `action_center` is a list of `{key, label, count, link}`, not a dict keyed by panel. Now reads the list. Counts do agree — low_stock card 4 = 4 rows. |

## Still not tested

**244 tenant-isolation tests and 37 IDOR tests did not run.** They need
`OTHER_TOKEN` and `OTHER_SALON_ID` for a second salon owned by a different user.
These cover the 25 endpoints with no salon in the path — the highest-value
target in the whole plan. Getting a second QA login is the single most useful
next step.

164 `destructive` tests also did not run (`--run-destructive` to enable).

## Operational note

Exclude `-m "not slow"` for routine runs. The year-range report downloads and
5,000-character input tests dominate wall-clock; without them the suite finishes
in about three minutes at `-n 8`.

---

# CRUD run — 26 Aug 2026 (second pass)

Run with `--run-destructive`. 164 tests. **25 failures, 2 new defects.**

## Correction to the earlier tally

The first write-up said 244 tenant tests were "blocked." That was wrong. Of the
244 tenant-marked tests:

| | Count | State |
|---|---:|---|
| Ran, passed | 89 | "missing salon → 404 not 500", "missing record → 404". These never needed a second salon. |
| Blocked on `OTHER_TOKEN` | 89 | The actual cross-salon checks. Still the gap. |
| Destructive | 66 | 52 ran in this pass, 14 still need the second salon. |

## D7 — 19 write endpoints return 500 on a wrong field type

**Severity: high · every module with a write**

Send a field that should be a string as `[{"nested": true}]` and the endpoint
returns `500 Internal Server Error` rather than 422:

```
POST /appointments                              → 500
POST /salons/4536/resources                     → 500
POST /salons/4536/waitlist_entries              → 500
POST /salons/4536/enquiries                     → 500
POST /salons/4536/follow_up_tasks               → 500
POST /salons/4536/holidays                      → 500
POST /salons/4536/membership_plans              → 500
POST /salons/4536/salon_customer_profiles       → 500
POST /salons/4536/staff/bulk_upload             → 500
POST /salons/4536/staff/bulk_upload/validate    → 500
POST /salons/4536/attendance/time_blocks        → 500
PUT  /salons/4536/attendance/entries            → 500
POST /api/v1/billing/bills                      → 500
POST /api/v1/billing/bills/calculate            → 500
POST /api/v1/billing/bills/finalize             → 500
PUT  /salons/4536/settings                      → 500
PUT  /salons/4536/roles                         → 500
PUT  /salons/4536/taxes                         → 500
PUT  /salons/4536/notification_preferences      → 500
```

Note the same endpoints reject *plausible-but-invalid* values correctly:
`POST /enquiries` with a blank name returns a clean 422 with a readable message.
It is specifically the wrong-shape case (array or hash where a scalar belongs)
that is unhandled — the classic strong-parameters crash.

The dashboard always sends the right shape, so this is unreachable from the UI.
A mobile client with a serialisation bug, or any retry with a mangled body, hits
it immediately.

**Fix:** rescue the parameter-shape error at `ApplicationController` level and
render 422.

Found by: `test_wrong_types_in_body_are_rejected`

## D8 — Appointments can be created in the past

**Severity: medium**

```
POST /appointments {"salon_id":4536,"date":"2026-07-27","start_time":"10:00","end_time":"10:30"}
→ 200 {"data":{"id":10488,"date":"2026-07-27","status":"Pending"}}
```

Thirty days in the past, accepted, `status: Pending`. It then sits in the
calendar as an appointment that can never happen and, depending on the report
query, may count toward "upcoming."

Also noted while cleaning up: **`DELETE /appointments/{id}` returns 404** —
`resources :appointments` generates the route but the controller has no
`destroy` action. Cancelling (`PUT /appointments/{id}/cancel`) works.

Found by: `test_cannot_book_in_the_past`

## Two failures were bad test payloads, not defects

Written from route names before reading the request schemas. Both need
rewriting against `example_body()` from swagger before their result means
anything:

| Test | What actually happened |
|---|---|
| `test_entries_upsert_is_idempotent` | Sent `{employee_id, date, status}`; endpoint wants an `attendance` key → `400 attendance is required`. **Idempotency is still unverified** — and it feeds payroll, so it matters. |
| `test_impossible_business_hours_are_refused` (×4) | Sent `working_days` as a dict keyed by day name; endpoint wants an array of `{weekday, start_time, end_time}`. It returned `200` with data unchanged. Whether an unrecognised body should 200 is itself worth asking. |

## QA data touched

- 3 appointments created on 2026-07-27 (ids 10486, 10487, 10488) — **all
  cancelled** during cleanup.
- No `APITEST-` products, staff, enquiries, waitlist entries or resources were
  left behind; the poisoned payloads all failed before creating anything.
- No bills were finalised or refunded — salon 4536 has no settled or draft bill,
  so those tests skipped.

---

# Cross-salon run — 26 Aug 2026 (third pass)

Salon A = 4536 (user 802) · Salon B = 1725 (user 1). Different owners.
Full tenant suite, reads and writes: **244 tests, 4 failures, 1 root cause.**

## D9 — Four endpoints serve any salon's service catalogue to any logged-in user

**Severity: critical · confirmed with live data**

`SalonServicesController` takes `salon_id` from the query string and never checks
who is asking:

```ruby
def index
  return json_error_response('salon_id is required', :bad_request) if params[:salon_id].blank?
  scope = SalonServicesQuery.new(SalonService.where(salon_id: params[:salon_id])).call(list_filters)
  # ...no ownership check anywhere in this action
end
```

Same shape in `summary`, `categories` and `export_all`. Reproduced with salon
A's token against salon B:

| Request (salon A's token) | Result |
|---|---|
| `GET /salon_services?salon_id=1725` | `200` — 4 services, byte-identical to salon B's own view |
| `GET /salon_services/summary?salon_id=1725` | `200` — `total_services: 4, active: 4, categories: 2, avg_price: 403` |
| `GET /salon_services/categories?salon_id=1725` | `200` — category ids and names |
| `GET /salon_services/export_all?salon_id=1725` | `200` — **full CSV: category, service, gender, duration, price, discount, status** |

In a B2B product, that is one salon downloading a competitor's complete service
menu and price list. `salon_id` is a small integer, so the whole customer base is
enumerable.

**Why nothing else caught it:** these four are the only B2B endpoints that take
`salon_id` as a *query parameter*. Every path-scoped endpoint goes through the
`SalonScoped` concern and was verified correct in the same run — salon A asking
for `/salons/1725/products`, `/staff`, `/dashboard/overview` and
`/reports/sales/summary` got `401`/`403` every time. The route shape is what
decided whether the check happened.

### Severity is higher than "one salon reads another"

Two follow-ups, both confirmed live:

**It needs no privilege.** The second token used here carries `role: "customer"`
— the mobile app's role, the lowest tier on the platform. Pointed at salon 4536
it returned that salon's catalogue and CSV export. There is no role check and no
ownership check in these actions, so *any* authenticated token reaches them,
including one issued to an ordinary app customer.

**The customer base is enumerable.** Walking `salon_id` with that same customer
token:

```
salon_id=100    200  total_services: 271, categories: 10
salon_id=4530   200  total_services: 260, categories:  8
salon_id=4536   200  total_services:   7, categories:  4   (the other test salon)
salon_id=1725   200  total_services:   4, categories:  2
```

Sequential small integers, no rate limit on these actions, `export_all` returns
CSV. That is the platform's entire service-and-pricing dataset, retrievable by
anyone with a mobile-app login.

**Recommend raising this with your lead today**, ahead of the other eight
findings.

**Fix:** resolve the salon and assert ownership, the way `bulk_create` in the
same controller already does:

```ruby
salon = Salon.find_by(id: params[:salon_id])
return json_error_response('unauthorized', :unauthorized) unless
  @current_user&.admin? || salon&.user_id == @current_user.id
```

Found by: `test_salon_id_query_param_is_authorized`

## What is correctly protected

Verified in the same run, so the fix above is genuinely the only gap:

| Surface | Result |
|---|---|
| All 129 path-scoped endpoints, read | `401`/`403` for another salon |
| All path-scoped endpoints, write | `401`/`403` for another salon |
| `/api/v1/billing/bills/{id}` with salon B's real bill id | refused |
| `/appointments/{id}` with salon B's real appointment id | refused |
| `PATCH /salon_services/{id}/status` on salon B's real service ids | `403` |
| `POST /salon_services`, `bulk_create` | Pundit / explicit ownership check |
| `bulk_delete`, `DELETE /salon_services` | re-scopes to `salons: {user_id: current_user.id}` — cannot touch another salon |

## Minor — `bulk_delete` reports success when it deleted nothing

`POST /salon_services/bulk_delete` with a nonexistent id, or with another salon's
id, returns `200 {"deleted": true}`. The scoping is correct and nothing is
destroyed, but the response cannot be distinguished from a real deletion. A
client shows "Deleted" and refreshes to find the row still there.

---

# End-to-end journey test — 27 Aug 2026 (fourth pass)

Everything above tests endpoints one at a time. This walks one real working day
through the API and checks the data actually travels between modules.

`tests/journeys/test_salon_journey.py` · **24 steps, all passing.**

| # | Step | Module boundary crossed |
|---|---|---|
| 1 | Record today's revenue and booking count | baseline |
| 2–3 | Create a service, confirm it is listed | Salon Setup |
| 4 | Billing's catalogue can find and price it | **Salon Setup → Billing** |
| 5 | Create a beautician | Salon Setup |
| 6 | Create a customer | Customers |
| 7 | Book the service with that beautician for that customer | **Setup → Calendar** |
| 8 | Booking appears on the day grid | Calendar |
| 9 | Booking is counted in the day summary | Calendar (second query) |
| 10 | Filtering the day by that beautician returns it | **Staff → Calendar** |
| 11–13 | Move it arrived → started → completed | Calendar |
| 14 | Preview the bill | Billing |
| 15 | Raise the bill | Billing |
| 16 | Preview total equals the saved bill total | Billing consistency |
| 17 | Settle it with a matching payment | Billing |
| 18 | Revenue moves on the dashboard | **Billing → Dashboard** |
| 19 | Booking count moves on the dashboard | **Calendar → Dashboard** |
| 20 | Bill appears in the Sales report | **Billing → Reports** |
| 21 | Beautician appears in Staff Performance | **Staff → Reports** |
| 22 | Service appears in the Service report | **Setup → Reports** |
| 23 | Customer appears in the Customer report | **Customers → Reports** |
| 24 | Clean up | — |

**Result: the modules are correctly wired to each other.** Money raised in
Billing reaches the Dashboard and the Sales report. A service created in Salon
Setup is sellable in Billing and shows up in the Service report. A beautician
assigned to a booking shows up in Staff Performance. This is the part of the
system nobody had proven, and it holds.

## Five smaller things found while building it

None are outages. All are the kind of thing that costs another developer an
afternoon.

**1. The API docs' own examples do not work.**
`/api-docs` shows `POST /salons/{id}/staff` as `{name, phone, email}`. Sending
exactly that returns `400 Missing required fields: job_title, shift_start,
shift_end`. `POST /salon_customer_profiles` likewise omits `gender`, which is
required. A mobile developer copying from our documentation gets an error.

**2. Billing silently ignores references that do not exist.**

```
POST /api/v1/billing/bills/calculate  {"items":[{"type":"service","ref_id":99999999}]}
→ 200  subtotal: 0.0

POST /api/v1/billing/bills/calculate  {"customer_id":99999999, ...}
→ 200  (accepted)
```

A bill built with a mistyped service id quietly totals zero instead of being
refused. Same pattern as D2/D8 above: invalid input treated as empty input.

**3. The client can set the price.**
Sending `{"ref_id": <a 900-rupee service>, "price": 500}` produced a subtotal of
500. If that is intended for custom line items it is fine — worth confirming it
is deliberate, since it means the price is not server-authoritative.

**4. The Sales report's `id` column is the bill number, not the bill id.**
Row `id` reads `NRZ-90` while the bill's actual id is `316`. A client linking
from a report row to `/bills/{id}` with that value gets a 404.

**5. `finalize` reads `payment`, singular.**
Sending `{"payments": [...]}` is silently ignored, and the endpoint then reports
`Payment amount does not match net payable total` — an accurate message for a
misleading reason. `{"payment": {"mode": "cash", "amount_paid": 525.0}}` works.
Worth rejecting the unknown key explicitly.

## How to run it

```bash
pytest tests/journeys -p no:randomly --run-destructive
```

Do **not** pass `-n` here — the steps are ordered and share state. Each run
tags its records `APITEST-<HHMMSS>` and cleans up after itself; settled bills
are left in place by design, since a settled invoice should not be deletable.

---

# Arithmetic and reconciliation layer — 27 Aug 2026 (fifth pass)

The journey layer went from 22 tests to **1,286**.

| Suite | Tests | Result |
|---|---:|---|
| `test_arithmetic.py` — money to the paisa | 1,140 | **all pass** |
| `test_reconciliation.py` — the same number on every surface | 122 | 8 fail → 2 findings |
| `test_salon_journey.py` — one working day, end to end | 24 | all pass |
| **Journey layer total** | **1,286** | |
| Whole suite | 3,289 | |

## Arithmetic: 1,140 checks, zero failures

80 basket shapes, each asserted against 14 invariants. `bills/calculate` is
side-effect free, so this runs at any scale without creating a record — 80
requests produce 1,140 assertions.

Baskets cover: price sweep (₹0.01 to ₹99,999.99) · quantity sweep (1 to 999) ·
line-level discounts · cart percentage · cart flat · both together · line and
cart stacked · multi-line service/product mixes · twelve rounding-torture prices
whose tax lands on a half-paisa · over-discount clamping · 20-line baskets.

The invariants, each a separate test so a fault names the step that broke:

```
subtotal == service_total + product_total          (the API's own numbers agree)
subtotal == sum(price x qty x (1 - line_disc))     (matches an independent model)
discount == min(subtotal, subtotal x pct + flat)   (clamped, never inverts)
taxable  == subtotal - discount
tax      == taxable x rate                          (rate derived, not hardcoded)
cgst + sgst == tax  and  cgst == sgst
gross    == taxable + tax
net      == gross + round_off
|round_off| <= 0.50
nothing negative · net is whole paise
```

**Every one passes.** Including ₹333.33 × 3 = ₹999.99 → tax ₹50.00, and a flat
₹5,000 discount on a ₹900 bill clamping to ₹900 rather than producing ₹-4,100.
**The billing maths is sound.** That is worth stating as plainly as the defects.

## Reconciliation: settling a bill moves every surface by exactly the right amount

Four bill shapes were raised and settled, with a full snapshot of eleven
reporting surfaces before and after each. **44 of 44 exact-delta assertions
passed.**

Not "revenue went up" — moved by ₹945.00 and not a paisa more, on all four money
surfaces simultaneously:

| Surface | Moved by |
|---|---|
| Dashboard · Overview · Revenue | exactly the bill's net |
| Reports · Sales · Total revenue | exactly the bill's net |
| Reports · Payments · Total collected | exactly the bill's net |
| Billing · Summary · today.collected | exactly the bill's net |
| Six separate count surfaces | exactly 1 |

**The forward path is correct.** Money raised in Billing arrives, intact and
exact, everywhere it is reported.

## D10 — Payments reports money that Sales does not

**Severity: high · the owner sees two revenue figures for the same period**

| Range | Sales · total revenue | Payments · total collected | Gap |
|---|---:|---:|---:|
| today | 1,050 | 1,050 | 0 |
| yesterday | 10,000 | 10,000 | 0 |
| last 7 days | 43,673 | 44,117 | 444 |
| **monthly** | **50,849** | **72,313** | **21,464** |
| quarterly | 50,849 | 72,313 | 21,464 |
| yearly | 50,849 | 72,313 | 21,464 |

They agree exactly for today and yesterday, and diverge as soon as the window
contains a refund. Confirmed cause:

```
sales/rows?range=monthly&status=paid       →  59 rows, sum 50,849   = the card
sales/rows?range=monthly&status=refunded   →  21 rows, paid 142,026
payments/rows?range=monthly                →  83 rows: 77 success, 6 refunded
```

**Reports · Sales removes a refunded bill from revenue. Reports · Payments keeps
its payment in "collected".** Both are defensible on their own. Presented to the
same owner for the same period with no label explaining the difference, one of
them is going to be believed and the other is going to start an argument.

**Decide which is right and label both.** "Collected" probably should be net of
refunds, or should be shown alongside a "refunded" figure that reconciles it.

Found by: `test_sales_revenue_equals_payments_collected`

## D11 — The Sales card counts 59 bills, the table under it lists 80

**Severity: medium**

```
Reports · Sales · summary card   →  bills_count: 59   (settled only)
Reports · Sales · rows, no filter →  meta.total: 80   (settled + refunded)
```

The card is computed over settled bills. The table beneath it, in its default
unfiltered state, also lists the 21 refunded ones. The owner reads "59 bills"
and scrolls a list of 80.

Both numbers are correct for what they measure. Showing them together without
saying so is the defect. Either the default table filter should match the card,
or the card should say "59 settled of 80".

Found by: `test_card_count_matches_the_table_the_owner_actually_sees`

## A correction to an earlier claim

The first version of the reconciliation suite reported the Sales card and its
rows disagreeing on every range. That was **our bug** — the report endpoints put
`meta` inside `data`, not at the top level, so the row total read as zero. Once
read correctly, the card matches its own `status=paid` rows exactly on all six
ranges. Only the unfiltered comparison (D11) is a real finding.

## How to run this layer

```bash
pytest tests/journeys/test_arithmetic.py                     # 1,140, no writes, safe any time
pytest tests/journeys/test_reconciliation.py                 # 122 read-only checks
pytest tests/journeys -p no:randomly --run-destructive       # adds the exact-delta and journey tests
```

`test_arithmetic.py` is the one to run most often: it is the largest, the
fastest, it touches the most valuable logic in the product, and it cannot
pollute anything.

---

# Journey arithmetic across all ten reports — 27 Aug 2026 (sixth pass)

`tests/journeys/test_journey_arithmetic.py` — **35 checks, 27 HTTP requests.**

The original journey asked *did it appear?*. This asks *by how much?*, and asks
all ten reports rather than two.

One bill of a known composition is raised and settled — 2 × ₹800, 10% off — and
every one of the ten report summaries is snapshotted before and after. Three
kinds of assertion:

| Kind | Count | What it means |
|---|---:|---|
| **MOVES** | 14 | must change by a precise amount |
| **STILL** | 16 | must not change at all |
| **AGREES** | 5 | two reports must state the same number |

## MOVES — exact deltas, all passing

| Report · field | Moves by |
|---|---|
| Sales · total revenue | the bill's net |
| Sales · bills count | 1 |
| Sales · service revenue | the bill's net |
| Sales · total discount | the discount |
| Payments · total collected | the bill's net |
| Payments · payments count | 1 |
| Services · total revenue | the bill's net |
| Services · services sold | the quantity |
| Profit · gross revenue | the taxable amount (ex-tax) |
| Profit · tax collected | the tax |
| Profit · net profit | the taxable amount |
| Profit · total discount | the discount |
| Customers · total spend in range | the bill's net |
| Staff Performance · service revenue | the bill's net |

## STILL — the invariants worth as much as the deltas

Selling one service must not move any of these, and none of them moved:

`sales.product_revenue` · `payments.failed_refunded_amount` ·
`profit.operating_expenses` · `profit.total_cost` · `expenses.total_expenses` ·
`expenses.expense_count` · `marketing.total_spend` ·
`marketing.attributed_revenue` · `marketing.total_campaigns` ·
`inventory.units_sold` · `inventory.sales_revenue` · `inventory.total_skus` ·
`appointments.total_appointments` · `customers.total_customers` ·
`staff_performance.total_staff` · `staff_performance.active_staff`

A figure that drifts here is being fed from somewhere it should not be.

## AGREES — cross-report reconciliation

- Sales total revenue == Payments total collected
- Sales service revenue == Services total revenue
- **Profit gross revenue + Profit tax collected == Sales total revenue** —
  Profit reports revenue net of tax, Sales reports it gross. They reconcile
  through the tax figure. Worth knowing: the two headline "revenue" numbers in
  this product are deliberately different, and nothing on screen says so.
- Sales service revenue + product revenue == Sales total revenue
- Average ticket == total revenue / bills count

**All 35 pass.** The arithmetic is exact across every report.

## D12 — Staff attribution is optional, and revenue without it disappears

**Severity: medium · informational, needs a product decision**

A bill line accepts an optional `staff_id`. Omit it and the sale still settles
and still counts in Sales, Services, Payments and Profit — but it is **absent
from Staff Performance and earns no commission.**

Observed on the QA salon:

| Range | Services · revenue | Staff Performance · attributed | Commission | Top performer |
|---|---:|---:|---:|---|
| today | 9,250.51 | **0.00** | 0 | **"QA Stylist two"** |
| monthly | 57,335.51 | 23,640.81 | 645 | "QA Stylist one" |

Two things follow:

1. **Only 41% of monthly service revenue is attributed to anybody.** If
   commission is paid from this figure, the rest earns nobody anything. Whether
   that is correct depends on how the salon actually bills — worth asking.
2. **The report named a top performer on a day when nothing was attributed.**
   Naming the best of nothing is worse than naming nobody.

Once a `staff_id` IS supplied, attribution is exact — the suite now asserts
`staff_performance.total_service_revenue` moves by precisely the bill's net, and
it passes. So the mechanism works; the question is whether it should be optional.

Found by: `test_journey_arithmetic.py`

## Correction

An earlier version of this test asserted staff revenue must simply be non-zero,
and failed. That was our test's fault — the bill it raised had no `staff_id` on
its line. The test now attaches a real beautician and asserts the exact delta,
and separately flags the optional-attribution question rather than reporting it
as a failure.

---

# Consolidation and negative end-to-end — 27 Aug 2026 (seventh pass)

## Correction to D10 — the cause I gave was wrong

D10 above says the Sales/Payments gap is caused by "Sales removing a refunded
bill from revenue while Payments keeps its payment in collected."

**That hypothesis does not hold.** The new negative journey raises a bill,
refunds it in full, and asserts every report reverses by exactly the amount —
and it passes. Both Sales *and* Payments reverse correctly on a fresh refund.

The gap is still real, and still unexplained:

| Range | Sales revenue | Payments collected | Gap | Payments "refunded" |
|---|---:|---:|---:|---:|
| today | 11,707.51 | 11,707.50 | **0.01** | 945.00 |
| last 7 days | 54,330.51 | 54,774.50 | 443.99 | 2,689.00 |
| monthly | 61,506.51 | 82,970.50 | **21,463.99** | 121,507.00 |

And it does not reconcile through the refunded figure either:

```
82,970.50 collected − 121,507.00 refunded = −38,536.50
                                but sales =  61,506.51
```

The refunded figure is *larger than everything collected*, which on its face
cannot be right for a single period — unless that field also counts failed
payments, or counts refunds against bills collected in earlier periods.

**Revised D10:** two reports state different revenue for the same month and the
difference cannot be explained from the API's own figures. The mechanism is
correct for a fresh refund, so the cause is elsewhere — historical data, a
second refund path, or the `failed_refunded_amount` field meaning something
other than its name. **This needs a backend engineer, not more testing.**

Also confirmed, small but reproducible: **Sales and Payments differ by ₹0.01
for today** — a rounding inconsistency between the two queries.

## Removed 541 tests, no coverage lost

3,326 → 2,785. Everything removed was a duplicate, a second request for an
answer we already had, or an arbitrary threshold.

| Removed | Cases | Why |
|---|---:|---|
| `test_no_stack_trace_when_unauthenticated` | 176 | Fired a second identical request to re-read the same 401 body. The check now runs on the response the first test already has. Same coverage, 176 fewer requests. |
| `test_responds_within_sla` | 84 | An arbitrary 3-second threshold on every endpoint. Sensitive to network noise, and not a statement about whether the API is correct. Performance is a known gap, tracked as one. |
| Journey's six "did it appear" checks | 6 | Superseded by `test_journey_arithmetic.py`, which asserts the exact delta on all ten reports. "Revenue went up" is strictly weaker than "revenue went up by 945.00". |
| `test_summary_count_matches_row_count` | 10 | Superseded by the reconciliation suite's version, which does the same comparison across three date ranges instead of one. |
| `test_summary_and_rows_both_answer` | 20 | A bare 200 check on every report, already covered by the generated schema and status-code tests. |
| Hostile strings 11 → 6 | ~245 | `sql-quote`, `sql-tautology` and `sql-comment` all asked one question — is this interpolated into a query. Same for `xss` and `template`. Six distinct ideas remain. |

Also, without removing anything: the four schema checks now **share one request
per endpoint** instead of making four. 336 requests became 84.

## Added: negative end-to-end — 27 tests

`tests/journeys/test_journey_negative.py`. The positive journey proves a sale
moves every report correctly. This proves the two harder things.

**Reversal — undoing a sale must move the reports back.** A system that adds
correctly and subtracts wrongly is worse than one that fails loudly, because
the books stay plausible.

- Full refund reverses Sales, Payments, Services and Customers by exactly the net
- The refund is recorded as refunded, and the bill leaves the settled count
- Expenses, Marketing, customer count and staff count are untouched
- Profit and tax still reconcile with Sales afterwards
- **Partial refund moves revenue by the part only, and keeps the bill counted** —
  `BillsController#refund` is explicit that only a full refund unwinds a sale

**Refusal — an invalid action must be refused AND leave the books untouched.**
Six invalid attempts are made between one pair of snapshots, so the assertion is
simply: nothing moved.

| Attempted | Must |
|---|---|
| Settle an already-settled bill | be refused |
| Refund more than was paid | be refused |
| Edit a settled bill | be refused |
| Set an appointment to an invented status | be refused |
| Settle a bill with no customer | be refused |
| Read another salon's bill | be refused |
| **…and then all seven money and count figures** | **be exactly where they were** |

**All 27 pass**, in 84 requests.

---

# Fixing our own process failures — 27 Aug 2026 (eighth pass)

Three process failures were admitted earlier. Checked honestly: **none were
fixed, and one had got worse.** All three are now addressed, and correcting the
first one turned up two new defects.

## Failure 1 — tests written before reading the request format

**Was still broken.** Both tests still sent invented payloads. The correct
shapes were in swagger the whole time:

| Endpoint | We sent | Swagger documents |
|---|---|---|
| `PUT /attendance/entries` | `{employee_id, date, status}` | `{"attendance": {employee_id, date, status}}` |
| `PUT /working_days` | `{"working_days": {"monday": {...}}}` | `{"days": [{weekday, closed, start_time, ...}]}` |

**Fixed**, and guarded: `lib/spec_loader.documented_body(endpoint_id)` returns
the spec's own example, so a test author starts from the contract instead of
inventing one.

### What the corrected tests then found

**Attendance upsert is idempotent — verified.** Writing the same employee-day
twice returns the same record id (329 both times: 201 then 200). Payroll cannot
count the day twice. This was flagged as unverified for two days; it is now
verified and correct.

**D13 — a day of the week that does not exist is accepted.**

```
PUT /salons/4536/working_days  {"days":[{"weekday":9,...}]}   → 200
GET /salons/4536/working_days                                 → weekdays [0,1,2,3,4,5,6,9]
```

**D14 — and it cannot be taken back out.** `PUT /working_days` reads as a
whole-set replace. It is not — omitting a day leaves it in place:

```
PUT {"days":[ ...only weekdays 0-6... ]}   → 200
GET                                         → weekdays [0,1,2,3,4,5,6,9]
```

So the invalid row written by a test is now stuck on salon 4536 and needs a
database change. **This pair is the strongest argument in
`docs/TEST_ENVIRONMENT_REQUEST.md`**: a test wrote a bad row, the API accepted
it, and the API cannot undo it.

**Issue 10, third instance.** Swagger's attendance example omits `check_in`,
which the API requires (`422 check_in is required`). Copying the documented
example gets an error — the same defect already found on `POST /staff` and
`POST /salon_customer_profiles`.

## Failure 2 — shared salon, no reset

**Had got worse.** 10 of the salon's 17 services were ours; bills had grown to
100.

**Partly fixed.** `tools/cleanup_test_data.py` removes everything the suite
created — services, beauticians, customers, draft bills — matched by the
`APITEST` / `JNEG` / `JARITH` / `JREF` prefixes, so it can never touch a real
record. Run once: **25 records removed.**

**Not fully fixable from here.** Settled bills cannot be deleted by design, and
weekday 9 cannot be removed at all. The real fix is a dedicated salon with a
reset, now written up as a request to the backend team.

## Failure 3 — no baseline

**Was not done.** Now built:

```
python tools/baseline.py        record what currently fails
python tools/daily.py           run, then report only what is NEW
```

`known-issues.txt` holds every currently-failing test with its defect
reference. After a run, `out/new-failures.md` contains only failures that are
not on that list, plus any known failure that has started passing — which
usually means a fix shipped.

Proven working: a run whose four failures were all in the baseline reported

```
0 new · 4 known · 18 not seen this run
```

instead of a wall of red. **That is the difference between a suite that gets
read every morning and one that gets ignored by the second week.**

One implementation note worth recording: the first version tracked failures
in-process, which silently reported nothing under `-n 8` because each xdist
worker is a separate process. The second version read the JUnit XML, which this
pytest does not populate with file paths, so every known failure looked new.
The working version parses pytest's own `FAILED …` summary lines, which carry
the exact node id and are printed by the master process.

---

## 28 Aug 2026 — Block 1 (API-E2E-001..025) and the trust question

Striker asked the right question before we built any more: *are these tests
fully dynamic, do we baseline before the test starts, and how do we know the
reports moved as expected?* Answering it properly changed the suite.

### There are two baselines and they do different jobs

**Per journey.** Every journey snapshots the reports it can move, performs its
steps, snapshots again, and compares the movement against a value computed in
Decimal from the basket. Absolute values never matter, so the salon's starting
state is irrelevant. Proof: salon 4550 began the day empty and ended it with 50
bills and Rs 59,826; the same journeys passed at both ends.

**Per suite.** `known-issues.txt` records the defects that already exist, so the
daily run reports "0 new" rather than a wall of familiar red.

### What was actually wrong: we checked 10 of 28 moving numbers

Journey 001 asserted 10 KPIs. Measured against a full snapshot, **28 figures
moved**. The 18 unwatched ones included `sales.service_revenue`,
`services.total_revenue`, `profit.net_profit`, `sales.total_discount` and
`payments.failed_refunded_amount` — real facts, unchecked.

`compare()` now runs the assertion in **both directions**: every figure we
predicted must move exactly as predicted, **and every other figure in the
snapshot must not have moved at all**. Anything else is named as either an
unmodelled side effect or concurrent writing to the salon. This is what makes a
delta trustworthy — not that the numbers we watched changed, but that nothing
else did.

Enabling the guard immediately surfaced five omissions, all now asserted, and
two candidate defects below.

### FINDING A — Staff Performance "service revenue" includes product sales

A bill of one Rs 1,000 service plus one Rs 200 product moved
`staff_performance.total_service_revenue` by **Rs 1,416** — the whole bill —
not by the Rs 1,180 service portion. A stylist who sells a bottle of shampoo is
credited with service revenue for it. Locked as observed in API-E2E-011 with a
comment; needs a product decision.

### FINDING B — the Appointments report does not move until a bill exists

Isolated step by step on an otherwise idle salon:

| step | Appointments report |
|---|---|
| create the appointment | nothing moves |
| mark it completed | nothing moves |
| create the **bill** | `total_appointments` +1, `completed` +1, `booked_value` +1000 |
| finalize the payment | `walk_ins` +1 |

`customers.total_customers` also moves on bill creation while
`customers.new_customers` moves on payment — two counters for the same event at
two different moments. A salon with 20 booked-but-unbilled appointments today
appears to have none. Cause not established; needs a backend engineer. Those
KPIs sit in `UNDER_INVESTIGATION` so the guard stays honest instead of silently
ignoring them.

### Limits that are real and should be known

- **Report cache.** `Reports::BaseController#cached` uses a 60s TTL for any
  range including today, with no invalidation on write. Journeys complete in
  ~4.5s, well inside that window. Measured behaviour on QA shows no staleness —
  writes appear in the very next read — so the cache is evidently not active
  here. **If Redis is enabled on this environment, every journey could start
  reading stale "after" values and failing.** Worth confirming before anyone
  points the suite at another environment.
- **Daily appointment capacity.** `SLOTS x ROSTER` = 19 x 20 = **380 bookings a
  day**, consumed for the whole day because appointments outlive the run.
  Roughly three full passes of 195 journeys. Exhaustion fails with an explicit
  message, not an obscure 422.
- **Midnight.** Both snapshots read `range=today`. A journey spanning midnight
  now fails with a plain explanation instead of nonsense deltas.
- **Exclusive access.** Journeys assume nothing else writes to salon 4550 while
  they run. The unexplained-movement guard is what detects a breach of that.

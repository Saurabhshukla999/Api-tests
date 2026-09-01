# API Test Plan — Nearz B2B Salon Management

**Component:** `api-service` (Rails 7 JSON API) — the seven B2B dashboard modules
**Client under test:** https://nearz-b2b-qa.netlify.app/dashboard
**Author:** QA · **Date:** 26 Aug 2026 · **Status:** ready for review

---

## 1. Why this exists

Frontend testing is complete: employee, product and service CRUD all work from
the dashboard. That establishes the UI can drive the API correctly. It says
nothing about what the API does when something *other* than the UI calls it.

The gap is structural, not a matter of thoroughness:

| The UI guarantees | So the API has never been asked |
|---|---|
| Your own `salon_id` in every request | What if it's someone else's? |
| A token, always attached | What if there is none, or a forged one? |
| Fields in the shape the form produced | What if a field is a string where a number belongs? |
| One click, one request | What if the same request arrives twice? |
| `page=1,2,3` | What if `page=999999` or `per_page=100000`? |

`api-service` is a single Rails app serving three clients — the customer mobile
app, the salon onboarding app, and this B2B dashboard. The API is a shared
contract, not a private helper for one frontend, and it is documented as one:
`/api-docs` publishes a live OpenAPI spec. A change that only the dashboard was
tested against can still break the mobile app.

**The concrete risk this plan addresses:** 21 of the 176 B2B endpoints take a
record id with no salon anywhere in the path, and 4 more take `salon_id` as a
query parameter. For those 25, nothing structural keeps salon A out of salon B's
data — only a hand-written ownership check inside each individual action. A
missing check is invisible in code review and unreachable from the UI.

---

## 2. Scope

### In scope — 176 endpoints across 7 modules

| Module | Endpoints | Reads | Writes | Swagger source |
|---|---:|---:|---:|---|
| Salon Setup (Services, Products, Beauticians, Customers) | 48 | 20 | 28 | `salon_setup.json`, `customer_information.json` |
| Billing & Invoicing | 36 | 19 | 17 | `billing.json`, `customer_memberships.json`, `membership_plans.json` |
| Business Dashboard (incl. Enquiries, Reminders) | 29 | 19 | 10 | `dashboard.json` |
| Reports | 23 | 23 | 0 | `reports.json` |
| Appointment Calendar | 16 | 6 | 10 | `appointment_calendar.json`, `appointments.json` |
| Salon Settings | 14 | 5 | 9 | `salon_settings.json` |
| Attendance | 10 | 6 | 4 | `attendance.json` |
| **Total** | **176** | **98** | **78** | |

### Out of scope

- Customer mobile app endpoints (`/salons/nearby`, `/restaurants`, `/users/*`)
- Salon onboarding app endpoints
- `/admins/*` — a separate role and a separate test effort
- UI rendering, browser compatibility, visual regression
- Load and soak testing (this suite measures per-request latency only)

### Assumptions

- A QA salon exists with representative data: at least one settled bill, one
  draft bill, several products, services, staff and appointments.
- **A second QA salon owned by a different user is available.** Without it the
  tenant-isolation tests cannot run, and they are the highest-value tests here.
- The QA database can absorb test-created records.

---

## 3. Approach

Three layers, each answering a different question.

**Layer 1 — Contract tests (generated, 1,700 tests).**
Parametrized over every endpoint parsed from `swagger/v1/*.json`. One test
function covers all 176 endpoints, so an endpoint added to the spec is tested on
the next run with no test-code change. This is where the leverage is: the
authentication matrix alone is 357 assertions from about forty lines.

**Layer 2 — Functional tests (hand-written, 299 tests).**
One file per module, for the rules a generated test cannot infer: that a bill's
preview total equals its finalised total, that attendance upsert is idempotent,
that a report's summary card agrees with the rows beneath it.

**Layer 3 — Existing RSpec request specs (127 files, already in the repo).**
Run in CI against a test database. They cover 166 of the 176 endpoints. This
suite complements them: RSpec proves the code is right against fixtures, these
tests prove the *deployed QA environment* is right against real data.

### Test categories

| Category | Tests | What it asks |
|---|---:|---|
| `auth` | 357 | Does every endpoint refuse a missing, malformed or forged token? |
| `tenant` | 244 | Can salon A reach salon B's data — by path, by query param, or by record id? |
| `schema` | 336 | Does the response match the schema swagger publishes? |
| `robustness` | 763 | Do malformed input, out-of-range pagination and injection strings ever produce a 500? |
| `functional` | 299 | Does the business logic hold? |
| **Total** | **2,003** | of which 164 are destructive and opt-in |

### What each category actually checks

**Authentication.** Every endpoint with no `Authorization` header at all; with an
empty header; with a non-JWT string; with a syntactically valid JWT signed with
the wrong key and carrying `role: admin`. Expected: 401 every time, with no
ActiveRecord class names or file paths in the body.

**Tenant isolation.** Three separate shapes, because the codebase has three:
- *Salon in the path* (129 endpoints) — `SalonScoped` sets `@salon` and checks
  ownership. Tested by sending salon B's id with salon A's token.
- *Salon in the query string* (4 endpoints — `/salon_services` and friends) —
  outside `SalonScoped` entirely, so the route pattern offers no protection.
- *No salon at all* (21 endpoints — `/api/v1/billing/bills/{id}`,
  `/appointments/{id}`, `/customer_memberships/{id}`) — classic IDOR shape. The
  suite discovers a real record id using salon B's own token, then requests it
  as salon A.

**Schema and contract.** Response bodies validated against the swagger schema
with `$ref`s resolved; status codes checked against the documented set; the
`{data: ..., token: ...}` envelope confirmed on every 200; per-request latency
measured against `SLA_SECONDS`.

**Robustness.** `per_page=1000` must clamp to 100, not return 1,000 rows —
`Paginatable` documents this, so it is a contract, not a guess. `page=999999`
must return an empty list with a correct `meta.total`. Eleven hostile strings
(SQL quotes, tautologies, markup, null bytes, a 5,000-character value) sent
through every filter, sort and search parameter. Malformed JSON bodies. Wrong
types in every documented field.

**Business logic**, per module — the checks worth naming:

- *Billing* — `bills/calculate` must be side-effect free; negative prices,
  fractional quantities and >100% discounts must not produce a negative total;
  a settled invoice must not be editable; a refund must not exceed the amount
  paid; finalising twice must not charge twice; the PDF must actually start with
  `%PDF`.
- *Attendance* — `PUT /attendance/entries` is documented as an idempotent upsert
  keyed by (employee, date). Written twice, it must produce one record. This
  feeds payroll.
- *Appointment Calendar* — a suggested free gap must never overlap a booking;
  the same beautician must not be bookable into one slot twice; appointments
  must not be creatable in the past.
- *Reports* — each report's summary count must equal its rows' `meta.total`; the
  four documented custom-range rules (`from`/`to` required, from ≤ to, to not in
  the future, span ≤ 366 days) must all be enforced; every download must return
  a real CSV within a request timeout.
- *Settings* — read the current settings, write them back unchanged, read again.
  Anything that differs was silently dropped. Impossible business hours (closes
  before it opens, hour 25) and invalid GST rates must be refused.
- *Dashboard* — `refresh=true` is throttled to 10/minute; the Action Center
  counter must equal the number of rows behind it.

---

## 4. A finding from reading the code

Three modules use three different vocabularies for the same `range` dropdown:

| Module | Accepted values | Source |
|---|---|---|
| Dashboard | `today`, `yesterday`, `last_7_days`, `monthly`, `quarterly`, `yearly` (+ aliases `month`, `year`, `quarter`, `7d`) | `Dashboard::RangeResolver` |
| Reports | the same, plus `custom` with `from`/`to` | `Reports::RangeResolver` |
| Billing summary | `today`, `week`, `month` | `Api::V1::Billing::SummaryController::RANGES` |

`week` is valid in Billing and invalid everywhere else. `monthly` is valid
everywhere else and invalid in Billing. The suite asserts both directions
deliberately, so if either module starts quietly accepting the other's
vocabulary — and resolving it to a window the label does not describe — a test
fails. **Worth raising with the lead as a design question, not only a test.**

---

## 5. Items to verify with the team before testing

These came out of reading the source and should be confirmed as intended
behaviour or raised as defects. Each has a test in the suite.

1. **OTP bypass via the `Origin` header.** `AuthenticationController#verify`
   accepts the fixed OTP `3227` whenever the request's `Origin` header contains
   the string `netlify`. `Origin` is set by the client, not the server. If this
   reaches production it is an authentication bypass for any account. Confirm it
   is QA-only and gated by environment.
2. **Five hardcoded test mobile numbers** with the fixed OTP `2244`
   (`TEST_MOBILE_NO`). Same question: is this compiled into production builds?
3. **`get_salon_jwt` matches on a mobile substring** (`mobile LIKE %param%`) and
   takes `.last`. An admin requesting a partial number can be handed a token for
   a user they did not intend.
4. **CORS is `origins '*'`** with all methods allowed, on an API that
   authenticates by bearer token.
5. **Rate limiting fails open.** `RateLimitable` allows the request when Redis is
   unreachable — deliberate, documented, and worth the lead knowing, because a
   Redis outage silently removes every throttle in the app.
6. **Two error envelopes.** Auth failures render `{errors: ...}`; everything else
   renders `{error: ...}`. Every client has to check both.

---

## 6. Coverage gaps in the existing RSpec suite

`tools/build_inventory.py` cross-references all 176 endpoints against the 127
specs in `spec/requests`. Result: **166 covered, 10 with no matching spec.**

| Module | Endpoint | Method |
|---|---|---|
| Attendance | `/salons/{salon_id}/attendance/entries/{id}` | DELETE |
| Attendance | `/salons/{salon_id}/attendance/time_blocks/{id}` | DELETE |
| Dashboard | `/salons/{salon_id}/dashboard/action_center/complaints` | GET |
| Dashboard | `/salons/{salon_id}/dashboard/action_center/membership_renewals` | GET |
| Dashboard | `/salons/{salon_id}/follow_up_tasks/{id}` | GET, PATCH, DELETE |
| Dashboard | `/salons/{salon_id}/follow_up_tasks/{id}/complete` | POST |
| Dashboard | `/salons/{salon_id}/follow_up_tasks/{id}/dismiss` | POST |
| Dashboard | `/salons/{salon_id}/follow_up_tasks/{id}/snooze` | POST |

The pattern is worth noting: the gaps are almost all **deletes and lifecycle
transitions** — the operations least often exercised and most consequential when
wrong. Full detail in `coverage_gaps.md`; per-endpoint data in
`endpoint_inventory.csv`.

This is a heuristic (path + verb matching against spec source), so treat it as a
prioritisation signal rather than proof.

---

## 7. Environment and test data

| | |
|---|---|
| Target | QA API behind `nearz-b2b-qa.netlify.app` — never staging or production |
| Auth | JWT bearer, 24h expiry. CI uses the mobile login path; local runs can paste a token. |
| Data | Two salons under two different owners. Salon B needs at least one bill, service and appointment for the IDOR tests to have a target. |
| Isolation | Records the suite creates are prefixed `APITEST-`. Destructive tests are opt-in (`--run-destructive`) so a default run cannot alter data. |
| Reset | None required for the default run. After a destructive run, delete `APITEST-*` records. |

---

## 8. Entry and exit criteria

**Entry:** QA API deployed and reachable · two salon logins available · swagger
specs current with the deployed build · `pytest -m smoke` green.

**Exit:**

| Criterion | Threshold |
|---|---|
| Auth contract tests | 100% pass — no exceptions |
| Tenant isolation and IDOR tests | 100% pass — no exceptions |
| No 5xx from any robustness test | 0 occurrences |
| Schema contract tests | ≥ 95% pass; each failure triaged as spec drift or defect |
| Functional tests | ≥ 95% pass; all failures triaged |
| Coverage gaps | all 10 have a test, in this suite or in RSpec |
| Suite in CI | runs on every PR to `main` |

Auth and tenant tests have no tolerance because their failure mode is one salon
reading another's revenue. Everything else is negotiable; those two are not.

---

## 9. Phasing

| Phase | Work | Effort |
|---|---|---|
| 1 | Environment: `.env`, second salon login, `pytest -m smoke` green | 0.5 day |
| 2 | Run auth + tenant + IDOR. Triage every failure. **Do this first** — it is where UI testing is blindest. | 1 day |
| 3 | Run schema + robustness. Separate spec drift from real defects. | 1–2 days |
| 4 | Run functional per module, in this order: Billing → Attendance → Calendar → Reports → Settings → Setup → Dashboard (ordered by consequence of failure). | 2–3 days |
| 5 | Wire into CI; fill the 10 coverage gaps. | 1 day |

Roughly a working week to a green, automated, repeatable suite — against several
weeks to click through 176 endpoints once, with no repeatability at the end.

---

## 10. Deliverables

| File | What it is |
|---|---|
| `api-tests/` | The runnable suite — 2,003 tests |
| `api-tests/README.md` | Setup and run instructions |
| `docs/API_TEST_PLAN.md` | This document |
| `docs/endpoint_inventory.csv` | All 176 endpoints: params, auth, scoping, documented responses, existing coverage |
| `docs/coverage_gaps.md` | The 10 uncovered endpoints, plus the 25 with no salon in the path |
| `reports/report.html` | Generated on every run |

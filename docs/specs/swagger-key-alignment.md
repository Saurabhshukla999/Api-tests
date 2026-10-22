# Task: align existing API tests with the swagger request/response keys

## Problem and scope

- Observable problem: the Java suite predates the current swagger spec
  (`api-service/swagger/v1`, last changed 2026-09-21). Several documented request
  keys are never sent, and some documented keys are absent from the tests while
  the suite still passes - so the tests do not prove the documented contract.
- Expected behavior: for every endpoint the suite already calls, every documented
  request key is either exercised and asserted, or explicitly listed as
  deliberately unexercised with a reason.
- Source evidence: 13 spec files, 209 operations / 176 endpoints. The suite makes
  82 calls over 49 distinct method+path pairs. Full comparison in
  `docs/SWAGGER_KEY_MATRIX.md`.
- Included changes: documented keys on enquiry, staff, service, product,
  appointment, attendance and working-day payloads; response assertions where the
  spec documents a response shape; spec-drift records.
- Out of scope: adding tests for the ~130 documented endpoints the suite never
  calls. That is endpoint coverage, not key alignment.

## Execution context

- Branch and starting revision: `codex/swagger-key-alignment`, cut from
  `master` at `e3eae0c` (PR #2 merged).
- Affected classes/helpers and callers: `Steps.createEnquiry`,
  `Steps.attemptEnquiry`, `Steps.bookAppointment`, `Steps.attemptBooking`,
  `Steps.createStaff`, `Steps.markAttendance`, `Catalogue.openEveryDay`,
  `Catalogue.findOrCreateService`, `ProductCrudTest` payloads. Callers are the
  Block1/4/5/6/7/8/9/10 journeys and `ProductCrudTest`.
- Local or live validation: both. Live runs started 22 Oct 2026 on the journey
  tenant, serialized; `ProductCrudTest` 7/7 green, then Block 1.
- Data created/changed: journey-tenant enquiries, customers, appointments,
  bills, payments and refunds created by the journeys. No shared setting was
  changed; GST-off is per-bill.
- Settings to restore: none.

## Acceptance criteria

- `mvn -B -DskipTests clean test-compile` succeeds.
- Every documented key on a tested endpoint is either:
  - sent by a helper and asserted on the documented response shape, or
  - listed in `docs/SWAGGER_KEY_MATRIX.md` under "deliberately not sent" with a
    reason.
- Spec/live contradictions are recorded as SW-1..SW-10 in `docs/DEFECTS.md`; no
  test is bent to a documented route or verb that the API does not serve.
- Genuine API defects found by the live runs are recorded as D20..D22 and the
  affected assertions are left in place (no relaxation to force green).
- Existing case IDs, skips, known-defect grouping, money expectations and
  `_qa` cache busting are unchanged.

## Implementation and commits

1. `docs: record swagger-vs-test key matrix` - matrix plus this spec.
2. `test: send and assert documented swagger keys` - `Steps`, `Catalogue`,
   `ProductCrudTest`.
3. `docs: note swagger drift on expense route and settings verb` -
   `docs/DEFECTS.md`.

Material deviations:

- Bill line price key: the spec says `price`, the API prices from `unit_price`.
  The suite keeps `unit_price` (SW-3); renaming would change what is billed.
- `staff_ids` on services and `break_start`/`break_end` on staff are not sent:
  both change bookable capacity, and the journeys book fixed slots.
- `lead_source_id` and `assigned_to_id` on enquiries are not sent: they need a
  lookup and a staff id, and no current test asserts assignment.
- `sales.service_revenue` is asserted **net of GST**, because that is what the
  live tile is labelled and what makes `service + product + gst = total`; the
  spec says GST-included (SW-8).
- GST-off bills send `tax.reason`, which the live API requires and the spec
  omits (SW-9).
- Appointment SHOW is asserted on `custom_name`; the live `service_name` is the
  catalogue name even when a custom label exists (SW-10).
- Slots that have already started are not allocated: the live API
  auto-completes a past-dated appointment, after which every status change is
  refused. The one-minute margin covers the booking round trip.
- `newPhone()` no longer derives digits from the millisecond window (two runs
  could collide) and avoids a `91` prefix, which the live normaliser truncates
  (D22). `Catalogue.secondServiceStaffId()` re-assigns the dual stylist to both
  QA services when an older record is reused.
- `Catalogue.roster()` repairs a staff record whose non-empty `service_ids`
  point at a deleted service (salon 4550 still had stylists on 54645 after QA
  Haircut became 54943). The API refuses those bookings with "not assigned to";
  empty service_ids are treated as unrestricted and left alone.
- `staffRevenue()` filters the Staff Performance rows by the documented
  `staff_id` instead of paging 444 staff 100 at a time.
- Multi-service appointments allocate a contiguous block (`nextFreeBlock`) -
  two 30-minute services need a 60-minute end time.

## Validation evidence

| Command/check | Result | What it establishes / limitation |
|---|---|---|
| `mvn -B -DskipTests clean test-compile` | BUILD SUCCESS, 20 test sources | Test sources compile - no API behaviour |
| `mvn test -Dtest=ProductCrudTest -DexcludedGroups=known-defect` | **7/7 pass** | The documented product keys (`sku`, `status`, `linked_service_ids`) round-trip live |
| `mvn test -Dtest=Block1EnquiryTest -DexcludedGroups=known-defect` | **22 run: 19 pass, 2 fail, 1 skip** | The two remaining failures are `e2e010` (D21) and `e2e020` (D20); `e2e004` was a test model, since corrected. Serialized on salon 4550, 22 Oct 2026 |
| `mvn test -Dtest=Block1EnquiryTest#e2e004_fullRefundReverses ...` | **1/1 pass** | Corrected refund/customer model - a fully refunded bill is zero visits |
| `mvn test -Dtest=Block1EnquiryTest#e2e008_serviceChanged+e2e009_customerDetailsChanged ...` | **2/2 pass** | Dual-stylist assignment repair, SHOW `custom_name`, non-`91` phone |
| `mvn test -Dtest=Block1EnquiryTest#e2e014_gstDisabled+e2e016_cancelRebookComplete ...` | **2/2 pass** | `tax.reason` for GST-off and future-only slot allocation |
| `mvn test -Dtest=Block4AppointmentTest -DexcludedGroups=known-defect` | **24 run: 22 pass, 0 fail, 2 skip** | The two skips are the parked reschedule tests (feature not enabled on 4550). First run had two test-side failures - multi-service duration and staff-row pagination - fixed and re-verified 2/2 before the full rerun. |
| `mvn test -Dtest=Block5BillTest -DexcludedGroups=known-defect` | **25 run: 24 pass, 0 fail, 1 skip** | Green on the first run. The skip is `e2e068_splitPayment`: partial settlement is switched off in the frontend, matching `docs/coverage_gaps.md`. |
| `mvn test -Dtest=Block6StaffTest -DexcludedGroups=known-defect` | **25 run: 25 pass, 0 fail** | Attendance status is derived from `check_in` against the rostered shift: a 10:00 check-in on a 09:00 shift comes back `late`, so the journeys check in at 09:00. First run had three attendance failures from that assumption, fixed and re-verified 3/3 before the full rerun. |
| `mvn test -Dtest=Block7InventoryTest -DexcludedGroups=known-defect` | **19 run: 19 pass, 0 fail** | Green on the first run. `e2e168` (refund should reverse COGS - D19) is in the known-defect group and excluded. |
| `mvn test -Dtest=Block8ExpenseTest -DexcludedGroups=known-defect` | **10 run: 10 pass, 0 fail** | Green after the P&L identity was completed: `total_cost` also carries `staff_commission` (SW-11). First run's two identity failures were the missing term, fixed and re-verified 2/2 before the full rerun. |
| `mvn test -Dtest=Block9DashboardTest -DexcludedGroups=known-defect` | **5 run: 5 pass, 0 fail** | Fixed two test-side basis errors: the dashboard is gross-incl-tax while `sales.service_revenue` is net (SW-8), and the inventory tab's low-stock array is a 10-row preview (SW-12). The first run also hit one transient `SSLHandshake` on e2e184; the rerun was clean. |
| `mvn test -Dtest=Block10CompositeTest -DexcludedGroups=known-defect` | **15 run: 11 pass, 4 fail, 0 skip** | All four failures are D20 (`bills_count` keeps refunded bills): `e2e192`, `e2e193`, `e2e194`, `e2e200`. The first run also hit a one-off 422 on `e2e198` after the conversion had already created the customer (D23); it passed on the rerun and on the second full run. |
| `mvn test -Dtest=Block2PaymentTest -DexcludedGroups=known-defect` | **25 run: 13 pass, 0 fail, 12 skip** | Green on the rerun. The 12 skips are the parked partial-payment journeys. The first run's only failure was `e2e084_profit`: a bill whose row was exactly 1,000 + 180 moved Profit revenue by 999.98; the case passed alone and on the full rerun, so it is a watch item, not a recorded defect. |
| `mvn test -Dtest=Block3RefundTest -DexcludedGroups=known-defect` | **25 run: 18 pass, 2 fail, 5 skip** | The two failures are D20: `e2e120` (376 → 378) and `e2e125` (383 → 385). `e2e117` was updated for the new, consistent behaviour: settlement adds the bill's 100 discount, the full refund reverses it in both Sales and Profit, and the bill record keeps it (Finding C). The final three-point version passed 1/1 after the full run. The 5 skips are partial-payment journeys. |
| `git diff --check` | Clean | Whitespace and secret/artifact review |

Serialized-run note: every command above was the only live run against the
journey tenant at the time. `-Dtest` selects the class/method but
`-DexcludedGroups=known-defect` keeps the known-defect group excluded.

## Handoff

- Block 1 now stands at **19 pass, 2 fail, 1 skip**: `e2e010` (D21) and
  `e2e020` (D20) are genuine API defects and must stay red until the API
  changes. Do not weaken them to green.
- `e2e004` was a test-model error, not an API defect: a fully refunded bill is
  zero visits by the documented billing-history definition.
- Blocks 4-10 are green except **D20** (22 + 24 + 25 + 19 + 10 + 5 pass,
  3 documented skips; Block 10 11 pass / 4 fail). Block 2 is **13 pass, 0 fail,
  12 documented skips**; one first-run 0.02 Profit drift on `e2e084` did not
  recur and is only a watch item. Block 3 is **18 pass, 2 fail, 5 skips**; both
  failures are D20 (`e2e120`, `e2e125`).
- All suite blocks have now run. The only red left is D20 in seven cases
  (`e2e020`, `e2e120`, `e2e125`, `e2e192`, `e2e193`, `e2e194`, `e2e200`) and
  D21 in `e2e010`; every one is a genuine API defect and stays red until the
  API changes. Do not weaken them to green.
- `Catalogue.ROSTER` was raised from 20 to 40 on 22 Oct 2026: a day of reruns
  had filled all 380 appointment places (19 slots x 20 stylists). This is test
  infrastructure, not an API change; the API accepted the extra QA stylists.
- `e2e198` (Block 10) hit a one-off **422 on enquiry conversion after the
  customer had been created** (D23): customer 4229 existed for the request that
  answered 422, and a retry said "enquiry is already converted". It passed on
  both later runs; `Steps.convertEnquiry` now includes the redacted body so the
  next occurrence is diagnosable.
- The failure diagnostics no longer print the refreshed JWT: `Steps.redacted`
  strips the `token` field from every response body included in an assertion
  message.

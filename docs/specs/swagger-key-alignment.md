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
- Local-only or live validation: local only. No live run was authorized.
- Data created/changed: none - no test was executed against the API.
- Settings to restore: none.

## Acceptance criteria

- `mvn -B -DskipTests clean test-compile` succeeds.
- Every documented key on a tested endpoint is either:
  - sent by a helper and asserted on the documented response shape, or
  - listed in `docs/SWAGGER_KEY_MATRIX.md` under "deliberately not sent" with a
    reason.
- Spec/live contradictions are recorded as SW-1..SW-7 in `docs/DEFECTS.md`; no
  test is bent to a documented route or verb that the API does not serve.
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

## Validation evidence

| Command/check | Result | What it establishes / limitation |
|---|---|---|
| `mvn -B -DskipTests clean test-compile` | BUILD SUCCESS, 20 test sources | Test sources compile; **no API behavior verified** |
| Live selector on the journey tenant | Not run | Live validation was not authorized; added response assertions remain unverified against the deployed API |
| Diff review | Pending | Confirm only intended files changed and no secrets or report artifacts are staged |

## Handoff

- Compile-only. The added assertions are derived from documented response shapes
  (`Enquiry`, `Staff`, `AttendanceEntry`, product create response). If a later
  live run disagrees, the failure is evidence about that shape, not a reason to
  drop the assertion.
- Next action for the environment owner: authorize a serialized journey-tenant
  run of `ProductCrudTest` and Block1/6 to verify the new assertions.

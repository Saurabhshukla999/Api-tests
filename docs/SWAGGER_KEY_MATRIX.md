# Swagger key matrix - existing tests vs. API documentation

Snapshot taken 22 Oct 2026 against the API service's own spec:

```
C:\Users\saura\documents\api-service\swagger\v1\*.json   13 files
```

The spec is outside this repository and is not committed here. Git in that
checkout last changed `swagger/` on 2026-09-21 (`77e545d`), so it is newer than
the suite's original 7 Sep baseline.

**Shape of the spec.** It mixes OpenAPI 3 `requestBody` with Swagger-2
`parameters[in=body]`, and uses `$ref` into `components/schemas` in some files
and inline schemas in others. Both were resolved before extracting keys;
reading only `requestBody` silently reports "no keys" for services, products and
staff.

**Scope measured.** 209 operations / 176 endpoints documented. The Java suite
makes 82 calls across 49 distinct method+path pairs (excluding fake ids used by
negative tests). This matrix covers only those endpoints - adding tests for the
other ~130 documented endpoints is endpoint coverage, not key alignment.

`R` marks a key the schema lists as required.

## Request bodies

| Endpoint | Documented keys | Sent before | Added / now asserted | Deliberately not sent |
|---|---|---|---|---|
| `POST /appointments` | salon_id R, date R, start_time R, end_time R, comment, booking_source, on_behalf_of_mobile_no, on_behalf_of_username, discount, appointment_services_attributes[salon_service_id R, staff_id] | all but `discount`; **sends `customer_id`, which the spec does not list** | `discount` = 0 | - |
| `PATCH /appointments/{id}` | Spec documents **PUT** only: discount, comment, date, start_time, end_time, appointment_services_attributes (no required) | caller-supplied change maps | - | - |
| `PATCH /appointments/{id}/status` | status R, reason | status | - | `reason` - only meaningful on cancel/no-show; no test asserts it |
| `POST /api/v1/billing/bills` | customer_id, appointment_id, status, items[type, ref_id, name, category, staff_id, staff_name, qty, **price**, disc_pct, duration], discount[pct, flat, coupon_code, membership, staff, approved_by_staff_id], tax[gst_enabled, mode], notes[bill_note, private_note], payment[partial, advance, mode, amount_paid, amount, modes[mode, amount R]] | customer_id, status, items(type, ref_id, qty, **unit_price**, staff_id), discount[pct, flat], tax[gst_enabled, mode] | - | `appointment_id`, `notes`, `payment` (settlement goes through finalize), item `name`/`category`/`staff_name`/`disc_pct`/`duration` |
| `PUT /api/v1/billing/bills/{id}` | Spec documents **PATCH** with the same keys as create | items[type, ref_id, qty, unit_price, staff_id] | - | partial update by design |
| `POST /api/v1/billing/bills/{id}/finalize` | customer_id, appointment_id, items, discount, tax, notes, payment[partial, advance, mode, amount_paid, amount, modes[amount R, mode]] | payment[mode, amount_paid] | - | partial/advance/modes |
| `POST /api/v1/billing/bills/{id}/payments` | partial, advance, mode, amount_paid, amount, modes[mode, amount R] | mode, amount_paid | - | partial, advance, amount, modes |
| `PATCH /api/v1/billing/bills/{id}/refund` | amount R, reason R, refund_mode, mode | amount (part refunds), reason | - | refund_mode, mode |
| `POST /salon_services` | salon_id R, service_id, category, custom_name, gender R, duration_in_minutes R, price R, discount, description, active, staff_ids | salon_id, service_id, custom_name, category, price, duration_in_minutes, gender, active | `discount` = 0, `description` | `staff_ids` - stylists are attached by the roster |
| `POST /salons/{id}/enquiries` | name R, phone R, source, lead_source_id, interest, notes, follow_up_date, assigned_to_id | name, phone, interest | `source` = Walk-in, `notes`, `follow_up_date`; all three asserted on the 201 body | lead_source_id, assigned_to_id - need a lookup and a staff id |
| `POST /salons/{id}/enquiries/{id}/convert` | no documented body | `{}` | - | - |
| `POST /salons/{id}/products` | name R, brand R, category R, sku, unit R, cost_price R, selling_price R, opening_stock R, reorder_level R, status, linked_service_ids | all required keys | `status` in the CRUD payload; a dedicated test sends `sku`, `status` and `linked_service_ids` and asserts sku, status and linked_services back | `sku` is left unset in the shared helper so the generated-SKU behaviour stays covered |
| `PUT /salons/{id}/products/{id}` | name, brand, category, sku, unit, cost_price, selling_price, reorder_level, status, linked_service_ids (none required) | name, selling_price | - | partial update by design; unmentioned fields are asserted unchanged |
| `POST /salons/{id}/staff` | name, phone, email, job_title (Admin/Manager/Receptionist/Beautician), experience_years 0-50, shift_start, shift_end, break_start, break_end, weekly_off[Mon..Sun], salon_service_ids[], commission_type (none/percentage/flat), commission_value >= 0, status (active/on_leave/inactive) | name, phone, job_title, shift_start, shift_end, commission_type, commission_value, salon_service_ids | `email` (unique), `experience_years` = 3, `weekly_off` = [], `status` = active; email/experience/status/weekly_off asserted on the 201 body | break_start/break_end - a break shrinks the bookable window and the journeys book fixed slots |
| `PUT /salons/{id}/staff/{id}` | same as create, none required | status | - | partial update by design |
| `PUT /salons/{id}/attendance/entries` | attendance[employee_id R, date R, status R, check_in, check_out, notes] | employee_id, date, status, check_in, check_out | `notes`; asserted on 200/201 only, because a refusal is a legitimate answer here | - |
| `PUT /salons/{id}/working_days` | days[weekday R, closed, start_time, end_time, has_break, break_start, break_end, second_shift_start, second_shift_end] | weekday, closed, start_time, end_time | `has_break` = false | break and second-shift windows - truthfully not configured |
| `PATCH /salons/{id}/settings` | Spec documents **PUT** with 16 setting keys | setting.round_off_enabled | - | partial by design |
| `POST /expenses`, `PUT /expenses/{id}`, `DELETE /expenses/{id}` | **Not documented.** The spec lists `POST /salons/{salon_id}/expense` and `PUT/PATCH/DELETE /salons/{salon_id}/expense/{id}` | as above | - | whole route family is drift, see SW-1 |

Read endpoints used by the suite (`GET /salons/{id}/products`, `/appointments`,
`/attendance/*`, reports, dashboard, `/api/v1/billing/*`) carry query parameters
only. `_qa` is a deliberate cache-buster (DEFECTS D17) and is not documented;
every other query key used (`date`, `range`, `page`, `per_page`, `q`) is
documented.

## Documentation drift found while matching

These are spec defects, not test defects. Tests stay on observed live behaviour;
the drift is recorded in `docs/DEFECTS.md` as SW-1..SW-7.

| id | Spec says | Suite observes |
|---|---|---|
| SW-1 | expense writes under `/salons/{salon_id}/expense` | `POST /expenses`, `PUT/DELETE /expenses/{id}`, read via `GET /salons/{id}/expenses` - the salon-scoped write route answers 404 |
| SW-2 | `PUT /salons/{salon_id}/settings` | `PATCH` is accepted and is the only verb the round-off toggle uses |
| SW-3 | bill item price key is `price` | `unit_price` is what the live API accepts and prices from |
| SW-4 | `POST /appointments` has no `customer_id` | `customer_id` is accepted and links the appointment to a converted enquiry |
| SW-5 | no `/services` operation in `v1/` | `GET /services` returns the master catalogue and supplies the `service_id` that `POST /salon_services` now requires |
| SW-6 | `PUT /appointments/{id}` | `PATCH /appointments/{id}` performs the update |
| SW-7 | `PATCH /api/v1/billing/bills/{id}` | `PUT` performs the draft-basket replacement |

Response-schema gaps that limit assertion, not request alignment:

- `POST /salon_services` 201 has `schema: {type: object}` - no fields documented,
  so `gender`/`duration_in_minutes`/`discount`/`description` are sent but can
  only be asserted on a later read, not on the create response.
- `POST /api/v1/billing/bills` 201 has no documented response schema; the suite
  asserts the id and then the money through report deltas.

## Validation status

- `mvn -B -DskipTests clean test-compile` - BUILD SUCCESS, 20 test sources.
- **No live run.** The added assertions are derived from documented response
  shapes; they are unverified against the deployed API until a serialized run on
  the journey tenant is authorised.

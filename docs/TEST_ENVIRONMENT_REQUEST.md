# Request to the backend team — a test environment the suite can own

Three things. The first is the only one that blocks anything.

## 1. A dedicated QA salon, with a way to reset it

Today the suite runs against **salon 4536**, which people also use by hand.

| | |
|---|---|
| Settled bills on it | 68, and rising — the API refuses to delete a settled invoice, correctly |
| Services the suite created | cleaned up, but it recreates them every run |
| Working days | now contains **weekday 9**, written by a test, and the API has no way to remove it |

That last row is the clearest argument. A test wrote an invalid row, the API
accepted it (a defect in itself), and **there is no API call that can take it
back out** — `PUT /working_days` turns out to be a partial update, not the
whole-set replace it looks like. The only fix is a database change.

**What we need:** one salon nobody works in by hand, with a known owner login,
and either a rake task or a database restore that returns it to a known state.
A monthly reset would be enough.

**Why it matters:** without it, the suite slowly fills a real salon with test
data, and some checks start failing because of what previous runs left behind
rather than because anything is wrong.

## 2. A second salon, under a different owner — already provided

Salon 1725 was supplied and is in use. Please keep it. Without a second owner,
**281 cross-salon tests cannot run**, and those are the ones that found the
service-list leak.

## 3. Seed data on the test salon

Some checks skip because there is nothing to act on — no draft bill, no settled
bill, no appointment. A skipped test protects nothing.

Minimum useful seed:

- one customer, one beautician, one service, one product
- one draft bill and one settled bill
- one appointment today

The suite can create most of this itself; it cannot create a *settled* bill
without also leaving one behind permanently, which is exactly the accumulation
problem above.

---

Until these land, the suite works around them: `tools/cleanup_test_data.py`
removes what it can after a run, and everything it creates is prefixed
`APITEST` / `JNEG` / `JARITH` / `JREF` so it is always identifiable.

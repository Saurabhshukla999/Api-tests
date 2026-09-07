package com.nearz.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Re-checks every defect in docs/DEFECTS.md that can be checked mechanically.
 *
 * NOT part of the suite - it is a periodic audit, run on demand:
 *     mvn test "-Dtest=VerifyDefectsTest" "-Dsurefire.suiteXmlFiles="
 *
 * It PRINTS a verdict per defect instead of asserting, on purpose. An audit
 * that aborts on the first still-open defect tells you about one defect; this
 * one tells you about all of them in a single run.
 *
 * A defect that reports FIXED should be re-read by a human before anyone
 * deletes it from DEFECTS.md - "the symptom went away on QA today" is not the
 * same as "the cause was fixed".
 */
public class VerifyDefectsTest extends BaseJourneyTest {

    private static void verdict(String id, boolean stillPresent, String evidence) {
        System.out.println("\n>>> " + id + "  ->  "
                + (stillPresent ? "STILL PRESENT" : "NOT REPRODUCED")
                + "\n    " + evidence);
    }

    private static void inconclusive(String id, String why) {
        System.out.println("\n>>> " + id + "  ->  INCONCLUSIVE\n    " + why);
    }

    // -----------------------------------------------------------------
    @Test(description = "D9 - can one salon read another salon's catalogue?")
    public void d9_crossTenantCatalogue() {
        // The journey salon's token, asking for a DIFFERENT salon's services.
        Response r = given().spec(Api.journey())
                .queryParam("salon_id", Env.OTHER_SALON)
                .queryParam("per_page", 5)
                .when().get("/salon_services");

        // The payload shape varies by endpoint, so count defensively rather
        // than guessing a path - a ClassCastException here would look like a
        // fixed defect, which is the worst possible failure mode for an audit.
        int count = -1;
        if (r.statusCode() == 200) {
            for (String path : new String[] {"data.services", "data.items", "data"}) {
                try {
                    List<?> list = r.jsonPath().getList(path);
                    if (list != null) { count = list.size(); break; }
                } catch (Exception ignored) { /* wrong shape, try the next */ }
            }
        }
        String body = r.asString();
        int cut = body.indexOf("\"token\"");
        if (cut > 0) { body = body.substring(0, Math.min(cut, 400)); }

        if (r.statusCode() == 200 && count < 0) {
            inconclusive("D9  cross-tenant catalogue read",
                "HTTP 200 but the payload shape was not recognised, so nothing "
              + "can be concluded. Body: " + body);
            return;
        }
        verdict("D9  cross-tenant catalogue read", r.statusCode() == 200 && count > 0,
            "salon " + SALON + "'s token asked for salon " + Env.OTHER_SALON
          + "'s services -> HTTP " + r.statusCode() + ", " + count + " services returned."
          + "\n      A fixed API would answer 403, or return an empty list.");
    }

    @Test(description = "D1 - does a missing record return 200?")
    public void d1_notFoundReturns200() {
        Response bill = given().spec(Api.journey())
                .when().get("/api/v1/billing/bills/99999999");
        Response appt = given().spec(Api.journey())
                .when().get("/appointments/99999999");

        boolean billIs200 = bill.statusCode() == 200;
        verdict("D1  404 served as 200", billIs200,
            "GET a nonexistent BILL  -> " + bill.statusCode()
          + "  body starts: " + bill.asString().substring(0, Math.min(60, bill.asString().length()))
          + "\n    GET a nonexistent APPOINTMENT -> " + appt.statusCode()
          + "   (measured 404 on 30 Aug, so this defect is endpoint-specific)");
    }

    @Test(description = "D2 - does an unknown filter value 500?")
    public void d2_unknownFilterValue() {
        Response waitlist = given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("status", "bogus")
                .when().get("/salons/{salonId}/waitlist_entries");
        Response memberships = given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("status", "bogus")
                .when().get("/salons/{salonId}/customer_memberships");

        verdict("D2  unknown filter value 500s",
            waitlist.statusCode() >= 500 || memberships.statusCode() >= 500,
            "waitlist_entries?status=bogus     -> " + waitlist.statusCode()
          + "\n    customer_memberships?status=bogus -> " + memberships.statusCode()
          + "   (a 422 or an empty 200 would be correct)");
    }

    @Test(description = "D6 - is per_page still capped at 100 while callers ask for 200?")
    public void d6_paginationCap() {
        JsonPath page = given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("date", LocalDate.now().toString())
                .queryParam("per_page", 200)
                .when().get("/salons/{salonId}/appointments")
                .then().extract().jsonPath();

        List<?> rows = page.getList("data.appointments");
        int returned = rows == null ? 0 : rows.size();

        // The cap can only be OBSERVED on a day with more than 100 bookings.
        // Fewer than that and the endpoint returning everything proves nothing
        // either way - saying "not reproduced" here would be a false all-clear.
        if (returned < 100) {
            inconclusive("D6  per_page 200 silently capped at 100",
                "asked for 200 of today's appointments and got " + returned
              + ", which is under the cap, so the cap was never exercised. "
              + "Re-run on a day with more than 100 bookings on salon " + SALON
              + ". The cap itself is still visible in the source: Paginatable "
              + "declares MAX_PER_PAGE = 100 while three controllers ask for 200.");
            return;
        }
        verdict("D6  per_page 200 silently capped at 100", returned == 100,
            "asked for 200 of today's appointments, got exactly " + returned
          + ". A cap is fine; silently returning 100 while the caller believes "
          + "it asked for 200 is what makes it a defect.");
    }

    @Test(description = "D8 - are past-dated appointments still accepted?")
    public void d8_pastDatedAppointment() {
        NewCustomer customer = arriveAsEnquiry("QA VERIFY D8");
        Catalogue.Booking slot = catalogue.nextFreeSlot();

        Response booked = given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().minusDays(30).toString(),
                        "start_time", slot.start(), "end_time", slot.end(),
                        "on_behalf_of_mobile_no", customer.phone(),
                        "on_behalf_of_username", customer.name(),
                        "appointment_services_attributes", List.of(Map.of(
                                "salon_service_id", catalogue.serviceId,
                                "staff_id", slot.staffId())))))
                .when().post("/appointments");

        String deleteNote = "not attempted";
        if (booked.statusCode() == 200) {
            int id = booked.jsonPath().getInt("data.id");
            deleteNote = "DELETE /appointments/" + id + " -> "
                       + given().spec(Api.journey()).pathParam("id", id)
                               .when().delete("/appointments/{id}").statusCode();
        }
        verdict("D8  a booking 30 days in the past", booked.statusCode() == 200,
            "POST with date=" + LocalDate.now().minusDays(30) + " -> "
          + booked.statusCode() + "\n    " + deleteNote);
    }

    @Test(description = "D3 / D4 - blank and unparseable dates")
    public void d3d4_dateHandling() {
        Response blank = given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("date", "")
                .when().get("/salons/{salonId}/appointments");
        Response nonsense = given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("date", "not-a-date")
                .when().get("/salons/{salonId}/appointments");

        verdict("D3/D4  blank and unparseable dates",
            blank.statusCode() == 200 || nonsense.statusCode() == 200,
            "date=\"\"          -> " + blank.statusCode()
          + "\n    date=\"not-a-date\" -> " + nonsense.statusCode()
          + "   (400/422 would be correct; a silent 200 is the defect)");
    }

    @Test(description = "D15 - does deleting a product 500 and delete anyway?")
    public void d15_deleteProduct() {
        int id = given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .body(Map.ofEntries(
                        Map.entry("name", "QA VERIFY D15 " + System.currentTimeMillis()),
                        Map.entry("brand", "QA Brand"), Map.entry("category", "Haircare"),
                        Map.entry("unit", "pcs"), Map.entry("cost_price", 10),
                        Map.entry("selling_price", 20), Map.entry("opening_stock", 5),
                        Map.entry("reorder_level", 1)))
                .when().post("/salons/{salonId}/products")
                .then().statusCode(201).extract().jsonPath().getInt("data.id");

        int first = given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when().delete("/salons/{salonId}/products/{id}").statusCode();
        int second = given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when().delete("/salons/{salonId}/products/{id}").statusCode();

        verdict("D15 DELETE product answers 500 but deletes", first >= 500,
            "first DELETE  -> " + first + "  (200/204 would be correct)"
          + "\n    second DELETE -> " + second
          + "  (404 here proves the first one DID delete despite the error)");
    }

    @Test(description = "D17 - are reports still cached for 60s with no invalidation?")
    public void d17_reportCache() throws Exception {
        Object plainBefore = plainApptCount();
        arriveAsEnquiry("QA VERIFY D17");
        long t0 = System.currentTimeMillis();
        bookAppointmentFor(arriveAsEnquiry("QA VERIFY D17b"));

        Thread.sleep(8_000);
        Object plainAfter  = plainApptCount();
        Object freshAfter  = Reports.snapshot(SALON, Reports.CALENDAR_ONLY)
                                    .get("appointments.total_appointments");
        long elapsed = (System.currentTimeMillis() - t0) / 1000;

        boolean stale = String.valueOf(plainBefore).equals(String.valueOf(plainAfter))
                     && !String.valueOf(plainAfter).equals(String.valueOf(freshAfter));
        verdict("D17 reports cached 60s, no invalidation on write", stale,
            "an appointment was created, then " + elapsed + "s later:"
          + "\n      plain read (as the dashboard reads it) : " + plainBefore + " -> " + plainAfter
          + "\n      cache-busted read (the truth)          : " + freshAfter);
    }

    private Object plainApptCount() {
        return given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today")
                .when().get("/salons/{salonId}/reports/appointments/summary")
                .then().statusCode(200).extract()
                .jsonPath().get("data.kpis.total_appointments.value");
    }

    @Test(description = "Finding A - does staff service revenue still include products?")
    public void findingA_staffRevenueIncludesProducts() {
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA VERIFY FA");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);      // 1 service + 1 product
        Steps.settleInFull(billId, "cash");
        var after = snapshot();

        BigDecimal moved      = Reports.moved(before, after, "staff_performance.total_service_revenue");
        BigDecimal wholeBill  = gross(1, 1, ZERO);       // 1416 - service AND product
        BigDecimal serviceOnly = gross(1, 0, ZERO);      // 1180 - the service portion

        verdict("Finding A  staff 'service revenue' counts products too",
            Money.closeEnough(moved, wholeBill),
            "a 1,000 service + 200 product bill moved staff service revenue by "
          + moved + "\n      the whole bill is " + wholeBill
          + ", the service portion alone is " + serviceOnly);
    }

    @Test(description = "Finding C - does a fully refunded bill keep its discount?")
    public void findingC_refundKeepsDiscount() {
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA VERIFY FC");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, new BigDecimal("10"));
        Steps.settleInFull(billId, "cash");
        Steps.refundInFull(billId, "QA verify Finding C");
        var after = snapshot();

        BigDecimal revenue  = Reports.moved(before, after, "sales.total_revenue");
        BigDecimal discount = Reports.moved(before, after, "sales.total_discount");

        verdict("Finding C  a refunded bill keeps its discount",
            revenue.compareTo(ZERO) == 0 && discount.compareTo(ZERO) != 0,
            "paid then fully refunded a 1,000 service at 10% off:"
          + "\n      sales.total_revenue  moved " + revenue + "   (0 is correct - the sale reversed)"
          + "\n      sales.total_discount moved " + discount
          + "   (0 would be correct; anything else means the books show a discount on a sale that no longer exists)");
    }

    @Test(description = "Finding D - does GET /appointments/{id} still omit the user?")
    public void findingD_showOmitsUser() {
        NewCustomer customer = arriveAsEnquiry("QA VERIFY FD");
        int id = bookAppointmentFor(customer);
        JsonPath shown = Steps.readAppointment(id);

        verdict("Finding D  GET appointment carries no user block",
            shown.get("data.user") == null,
            "GET /appointments/" + id + " -> data.user is "
          + (shown.get("data.user") == null ? "ABSENT" : "present")
          + "\n      (PATCH returns it; SHOW does not - a rename read back this way looks like it failed)");
    }

    @Test(description = "D11 - do the Sales card and the Sales table still disagree?")
    public void d11_cardVersusTable() {
        // Read-only, against the REAL QA salon 4536 where the discrepancy was seen.
        Object cardCount = given().spec(Api.owner())
                .pathParam("salonId", Env.OWNER_SALON)
                .queryParam("range", "monthly").queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/reports/sales/summary")
                .then().statusCode(200).extract()
                .jsonPath().get("data.kpis.bills_count.value");

        JsonPath rows = given().spec(Api.owner())
                .pathParam("salonId", Env.OWNER_SALON)
                .queryParam("range", "monthly").queryParam("limit", 1)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/reports/sales/rows")
                .then().statusCode(200).extract().jsonPath();
        Object tableCount = rows.get("data.meta.total_count");
        if (tableCount == null) { tableCount = rows.get("meta.total_count"); }
        if (tableCount == null) { tableCount = rows.get("data.data.meta.total_count"); }

        // Two ways this proves nothing: the table count was not found, or the
        // salon simply has no bills this month. Either way, say so.
        if (tableCount == null) {
            inconclusive("D11 Sales card vs Sales table",
                "the rows endpoint's total_count was not found at any known path, "
              + "so the two figures could not be compared. Card said " + cardCount + ".");
            return;
        }
        if ("0".equals(String.valueOf(cardCount))) {
            inconclusive("D11 Sales card vs Sales table",
                "salon " + Env.OWNER_SALON + " has no bills in the current month "
              + "(card 0, table " + tableCount + "), so there is nothing for the two "
              + "to disagree about. The original observation was 59 vs 80 on a month "
              + "with data - re-run against a populated range.");
            return;
        }
        verdict("D11 Sales card vs Sales table",
            !String.valueOf(cardCount).equals(String.valueOf(tableCount)),
            "salon " + Env.OWNER_SALON + ", range=monthly:"
          + "\n      summary card bills_count : " + cardCount
          + "\n      table total_count        : " + tableCount);
    }

    @Test(description = "D12 - is revenue billed without a stylist still invisible?")
    public void d12_unattributedRevenue() {
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA VERIFY D12");
        completeAppointmentFor(customer);

        // The same bill, but with no staff_id on the line at all.
        int billId = given().spec(Api.journey())
                .body(Map.of("customer_id", customer.id(), "status", "draft",
                        "items", List.of(Map.of("type", "service",
                                "ref_id", catalogue.serviceId, "qty", 1,
                                "unit_price", catalogue.servicePrice))))
                .when().post("/api/v1/billing/bills")
                .then().statusCode(201).extract().jsonPath().getInt("data.id");
        Steps.settleInFull(billId, "cash");
        var after = snapshot();

        BigDecimal sales = Reports.moved(before, after, "sales.total_revenue");
        BigDecimal staff = Reports.moved(before, after, "staff_performance.total_service_revenue");

        verdict("D12 revenue with no stylist never reaches Staff Performance",
            sales.compareTo(ZERO) != 0 && staff.compareTo(ZERO) == 0,
            "a bill with NO staff_id moved:"
          + "\n      sales.total_revenue                     " + sales
          + "\n      staff_performance.total_service_revenue " + staff
          + "\n      (the sale is real but no one is credited, and nothing warns you)");
    }
}

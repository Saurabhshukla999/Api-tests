package com.nearz.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * TEMPORARY. The appointments report did not move for 60 seconds after a
 * booking. Reports::BaseController caches summaries for 60s when the range
 * includes today. If the SALES report does the same, then every exact delta
 * assertion in blocks 1-3 has been racing that cache.
 */
public class ProbeCache extends BaseJourneyTest {

    private JsonPath report(String name) {
        return given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today")
                .when().get("/salons/{salonId}/reports/" + name + "/summary")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    public void doesTheSalesReportCacheToo() {
        System.out.println("\n===== does a SETTLED BILL show up straight away? =====");
        Object salesBefore = report("sales").get("data.kpis.total_revenue.value");
        Object apptBefore  = report("appointments").get("data.kpis.total_appointments.value");

        long t0 = System.currentTimeMillis();
        NewCustomer customer = arriveAsEnquiry("QA PROBE CACHE");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        long spent = (System.currentTimeMillis() - t0) / 1000;

        Object salesNow = report("sales").get("data.kpis.total_revenue.value");
        Object apptNow  = report("appointments").get("data.kpis.total_appointments.value");
        System.out.println("journey took " + spent + "s");
        System.out.println("sales.total_revenue        " + salesBefore + " -> " + salesNow);
        System.out.println("appointments.total_appts   " + apptBefore + " -> " + apptNow);

        try { Thread.sleep(65_000); } catch (InterruptedException ignored) { }
        System.out.println("after a further 65s:");
        System.out.println("sales.total_revenue        -> " + report("sales").get("data.kpis.total_revenue.value"));
        System.out.println("appointments.total_appts   -> " + report("appointments").get("data.kpis.total_appointments.value"));
    }

    @Test
    public void whatDoesRescheduleWant() {
        System.out.println("\n===== reschedule =====");
        NewCustomer customer = arriveAsEnquiry("QA PROBE RS");
        int id = bookAppointmentFor(customer);
        Catalogue.Booking slot = catalogue.nextFreeSlot();

        Response wrapped = given().spec(Api.journey()).pathParam("id", id)
                .body(Map.of("appointment", Map.of(
                        "date", LocalDate.now().toString(),
                        "start_time", slot.start(), "end_time", slot.end())))
                .when().put("/appointments/{id}/reschedule");
        System.out.println("wrapped in appointment{} -> " + wrapped.statusCode()
                + "  " + wrapped.asString().substring(0, Math.min(300, wrapped.asString().length())));

        Response flat = given().spec(Api.journey()).pathParam("id", id)
                .body(Map.of("date", LocalDate.now().toString(),
                             "start_time", slot.start(), "end_time", slot.end(),
                             "staff_id", slot.staffId()))
                .when().put("/appointments/{id}/reschedule");
        System.out.println("flat -> " + flat.statusCode()
                + "  " + flat.asString().substring(0, Math.min(300, flat.asString().length())));
    }

    @Test
    public void howManyWorkersAreBehindTheLoadBalancer() {
        System.out.println("\n===== ten reads in a row, same second =====");
        for (int i = 0; i < 10; i++) {
            System.out.println("  read " + i + ": total_appointments = "
                    + report("appointments").get("data.kpis.total_appointments.value"));
        }
        System.out.println("If these differ, the cache is per-process and reads "
                + "land on different app servers - which makes any delta a coin toss.");
    }
}

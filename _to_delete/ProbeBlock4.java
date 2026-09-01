package com.nearz.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** TEMPORARY. Answers the questions Block 4 depends on, before 25 tests are
 *  written on guesses. Delete once Block4AppointmentTest is written. */
public class ProbeBlock4 extends BaseJourneyTest {

    private static void show(String label, Response r) {
        String body = r.asString();
        System.out.println("\n### " + label + " -> " + r.statusCode());
        System.out.println(body.length() > 700 ? body.substring(0, 700) + " ..." : body);
    }

    private JsonPath apptReport() {
        return given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today")
                .when().get("/salons/{salonId}/reports/appointments/summary")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    public void q1_reportShapeAndCache() {
        System.out.println("\n===== Q1 appointments report KPIs, and the 60s cache =====");
        JsonPath before = apptReport();
        System.out.println("kpis: " + before.get("data.kpis"));

        long t0 = System.currentTimeMillis();
        NewCustomer customer = arriveAsEnquiry("QA PROBE Q1");
        int appointmentId = bookAppointmentFor(customer);

        JsonPath immediately = apptReport();
        System.out.println("total_appointments  before=" + before.get("data.kpis.total_appointments.value")
                + "  immediately after booking=" + immediately.get("data.kpis.total_appointments.value")
                + "   (elapsed " + (System.currentTimeMillis() - t0) / 1000 + "s)");

        try { Thread.sleep(65_000); } catch (InterruptedException ignored) { }
        JsonPath later = apptReport();
        System.out.println("after 65s (cache TTL is 60s): "
                + later.get("data.kpis.total_appointments.value"));
        System.out.println("VERDICT: if 'immediately' already moved, the cache is not biting.");
    }

    @Test
    public void q2_bookingVariants() {
        System.out.println("\n===== Q2 booking shapes Block 4 needs =====");
        NewCustomer customer = arriveAsEnquiry("QA PROBE Q2");

        // no stylist at all - AppointmentService says belongs_to :staff, optional
        Catalogue.Booking a = catalogue.nextFreeSlot();
        show("book with NO staff_id", given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().toString(),
                        "start_time", a.start(), "end_time", a.end(),
                        "on_behalf_of_mobile_no", customer.phone(),
                        "on_behalf_of_username", customer.name(),
                        "appointment_services_attributes", List.of(Map.of(
                                "salon_service_id", catalogue.serviceId)))))
                .when().post("/appointments"));

        // two service lines, two different stylists, one appointment
        Catalogue.Booking b = catalogue.nextFreeSlot();
        Catalogue.Booking c = catalogue.nextFreeSlot();
        show("book TWO services, TWO stylists", given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().toString(),
                        "start_time", b.start(), "end_time", b.end(),
                        "on_behalf_of_mobile_no", customer.phone(),
                        "on_behalf_of_username", customer.name(),
                        "appointment_services_attributes", List.of(
                                Map.of("salon_service_id", catalogue.serviceId,
                                       "staff_id", b.staffId()),
                                Map.of("salon_service_id", catalogue.serviceId,
                                       "staff_id", c.staffId())))))
                .when().post("/appointments"));
    }

    @Test
    public void q3_statusChainAndReschedule() {
        System.out.println("\n===== Q3 status chain, reschedule =====");
        NewCustomer customer = arriveAsEnquiry("QA PROBE Q3");
        int id = bookAppointmentFor(customer);

        for (String status : List.of("arrived", "started", "completed")) {
            show("PATCH status -> " + status, given().spec(Api.journey())
                    .pathParam("id", id).body(Map.of("status", status))
                    .when().patch("/appointments/{id}/status"));
        }

        int second = bookAppointmentFor(customer);
        Catalogue.Booking slot = catalogue.nextFreeSlot();
        show("PUT reschedule", given().spec(Api.journey())
                .pathParam("id", second)
                .body(Map.of("appointment", Map.of(
                        "date", LocalDate.now().toString(),
                        "start_time", slot.start(), "end_time", slot.end())))
                .when().put("/appointments/{id}/reschedule"));
    }

    @Test
    public void q4_invalidIds() {
        System.out.println("\n===== Q4 what the API says to nonsense =====");
        Catalogue.Booking slot = catalogue.nextFreeSlot();

        show("bad salon_service_id", given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().toString(),
                        "start_time", slot.start(), "end_time", slot.end(),
                        "on_behalf_of_mobile_no", Steps.newPhone(),
                        "on_behalf_of_username", "QA PROBE Q4",
                        "appointment_services_attributes", List.of(Map.of(
                                "salon_service_id", 99999999,
                                "staff_id", slot.staffId())))))
                .when().post("/appointments"));

        show("bad staff_id", given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().toString(),
                        "start_time", slot.start(), "end_time", slot.end(),
                        "on_behalf_of_mobile_no", Steps.newPhone(),
                        "on_behalf_of_username", "QA PROBE Q4",
                        "appointment_services_attributes", List.of(Map.of(
                                "salon_service_id", catalogue.serviceId,
                                "staff_id", 99999999)))))
                .when().post("/appointments"));

        show("bad mobile", given().spec(Api.journey())
                .body(Map.of("appointment", Map.of(
                        "salon_id", Integer.parseInt(SALON),
                        "date", LocalDate.now().toString(),
                        "start_time", slot.start(), "end_time", slot.end(),
                        "on_behalf_of_mobile_no", "not-a-phone",
                        "on_behalf_of_username", "QA PROBE Q4",
                        "appointment_services_attributes", List.of(Map.of(
                                "salon_service_id", catalogue.serviceId,
                                "staff_id", slot.staffId())))))
                .when().post("/appointments"));

        show("GET a nonexistent appointment", given().spec(Api.journey())
                .pathParam("id", 99999999).when().get("/appointments/{id}"));
    }
}

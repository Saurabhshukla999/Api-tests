package com.nearz.api;

import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * One method per business action. Each returns the id the next step needs.
 *
 * This is what makes a journey a journey rather than seven unrelated calls: the
 * enquiry id goes into convert, the customer id comes out, the phone goes into
 * the appointment, the customer id goes into the bill, the bill id goes into
 * the payment. If any link is broken, the next step fails and says which.
 */
public final class Steps {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Steps() { }

    /**
     * A 10-digit Indian mobile, unique per call.
     *
     * The API validates the format, and a repeat converts onto the EXISTING
     * customer instead of creating one - which silently breaks every
     * new-customer assertion downstream. Both learned the hard way.
     */
    public static String newPhone() {
        int n = COUNTER.incrementAndGet();
        long tail = (System.currentTimeMillis() % 1_000_00L) * 10 + (n % 10);
        return "9" + String.format("%09d", tail % 1_000_000_000L);
    }

    // -----------------------------------------------------------------------
    public static int createEnquiry(String salonId, String name, String phone) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.of("enquiry", Map.of(
                        "name", name, "phone", phone, "interest", "QA Haircut")))
                .when().post("/salons/{salonId}/enquiries")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    /**
     * Try to log an enquiry and return the status code rather than asserting it.
     *
     * The API de-duplicates: a second enquiry for the same phone within a
     * minute is refused with 409 and "an enquiry for this phone number was
     * already logged less than a minute ago". That is deliberate, and worth a
     * test of its own.
     */
    public static int attemptEnquiry(String salonId, String name, String phone) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.of("enquiry", Map.of(
                        "name", name, "phone", phone, "interest", "QA Haircut")))
                .when().post("/salons/{salonId}/enquiries")
                .then().extract().statusCode();
    }

    /** Everyone the salon knows by this name or number. */
    public static java.util.List<Integer> searchCustomers(String query) {
        return given().spec(Api.journey())
                .queryParam("q", query)
                .when().get("/api/v1/billing/customers/search")
                .then().statusCode(200)
                .extract().jsonPath().getList("data.id", Integer.class);
    }

    /** Returns the salon_customer_profile id the enquiry became. */
    public static int convertEnquiry(String salonId, int enquiryId) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .pathParam("id", enquiryId)
                .body(Map.of())
                .when().post("/salons/{salonId}/enquiries/{id}/convert")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.customer_profile.id");
    }

    /**
     * Book an appointment.
     *
     * POST /appointments carries NO customer_id. The only customer handle it
     * accepts is on_behalf_of_mobile_no - the salon books for a walk-in by
     * phone. So the enquiry -> customer -> appointment link is the phone
     * number, not an id, and that is exactly where an appointment-linking
     * regression hides.
     */
    public static int bookAppointment(String salonId, String customerName,
                                      String customerPhone, Catalogue catalogue) {
        // The calendar read can still miss a booking - a page appearing between
        // requests, or another session working on the same salon - so a refused
        // slot is retried on the next free one rather than failing the journey.
        for (int attempt = 1; attempt <= 25; attempt++) {
            Catalogue.Booking slot = catalogue.nextFreeSlot();
            io.restassured.response.Response response = given().spec(Api.journey())
                    .body(Map.of("appointment", Map.of(
                            "salon_id", Integer.parseInt(salonId),
                            "date", LocalDate.now().toString(),
                            "start_time", slot.start(),
                            "end_time", slot.end(),
                            "on_behalf_of_mobile_no", customerPhone,
                            "on_behalf_of_username", customerName,
                            "appointment_services_attributes", List.of(Map.of(
                                    "salon_service_id", catalogue.serviceId,
                                    "staff_id", slot.staffId())))))
                    .when().post("/appointments");

            if (response.statusCode() == 200) {
                return response.jsonPath().getInt("data.id");
            }
            String body = response.asString();
            if (body.contains("already has a booking")) {
                continue;                       // that slot went while we looked
            }
            if (body.contains("is not assigned to")) {
                catalogue.doNotUse(slot.staffId());   // someone changed their skills
                continue;
            }
            throw new AssertionError(
                "booking an appointment failed with " + response.statusCode()
              + " at " + slot.start() + " for stylist " + slot.staffId()
              + "\n  " + body);
        }
        throw new IllegalStateException(
            "could not find a free slot after 25 attempts on " + LocalDate.now()
          + ". Today's calendar is effectively full; wait for tomorrow or raise "
          + "ROSTER in Catalogue.java.");
    }

    /**
     * Book with a payload the caller controls completely. Returns the response
     * so the test can assert on a refusal as easily as on a success.
     *
     * bookAppointment() above is the happy path with a retry loop. Block 4 needs
     * to book with no stylist, with two services, with two stylists, and with
     * deliberately invalid ids - none of which should be retried.
     */
    public static io.restassured.response.Response attemptBooking(
            String salonId, String name, String phone,
            String start, String end, List<Map<String, Object>> lines) {
        Map<String, Object> appointment = new LinkedHashMap<>();
        appointment.put("salon_id", Integer.parseInt(salonId));
        appointment.put("date", LocalDate.now().toString());
        appointment.put("start_time", start);
        appointment.put("end_time", end);
        appointment.put("on_behalf_of_mobile_no", phone);
        appointment.put("on_behalf_of_username", name);
        appointment.put("appointment_services_attributes", lines);
        return given().spec(Api.journey())
                .body(Map.of("appointment", appointment))
                .when().post("/appointments");
    }

    /** One service line for attemptBooking. staffId null leaves it unassigned -
     *  AppointmentService says belongs_to :staff, optional: true. */
    public static Map<String, Object> line(int serviceId, Integer staffId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("salon_service_id", serviceId);
        if (staffId != null) {
            line.put("staff_id", staffId);
        }
        return line;
    }

    /** Every appointment on today's calendar, as raw maps. */
    public static List<Map<String, Object>> todaysAppointments(String salonId) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            List<Map<String, Object>> batch = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .queryParam("date", LocalDate.now().toString())
                    .queryParam("page", page)
                    .queryParam("per_page", 100)
                    .when().get("/salons/{salonId}/appointments")
                    .then().statusCode(200)
                    .extract().jsonPath().getList("data.appointments");
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
        }
        return all;
    }

    /** Read one appointment back. data.salon_services[] carries the line id,
     *  service_name, amount, staff_id and staff_name. */
    public static JsonPath readAppointment(int appointmentId) {
        return given().spec(Api.journey())
                .pathParam("id", appointmentId)
                .when().get("/appointments/{id}")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /**
     * Change an existing appointment. Returns the status code.
     *
     * PATCH /appointments/{id} -> AppointmentUpdateService. The permitted
     * fields are date, start_time, end_time, discount, comment,
     * on_behalf_of_username, on_behalf_of_mobile_no and
     * appointment_services_attributes[id, salon_service_id, staff_id,
     * _destroy].
     *
     * The nested hash needs the LINE id (data.salon_services[].id), not the
     * appointment id and not the salon_service_id. Omit it and Rails builds a
     * SECOND service line instead of editing the one that is there.
     *
     * The status code is returned rather than asserted because a refusal is a
     * legitimate answer here - moving a stylist onto a service they are not
     * assigned to comes back 422, and that is correct behaviour worth testing.
     */
    public static int attemptAppointmentChange(int appointmentId,
                                               Map<String, Object> changes) {
        return given().spec(Api.journey())
                .pathParam("id", appointmentId)
                .body(Map.of("appointment", changes))
                .when().patch("/appointments/{id}")
                .then().extract().statusCode();
    }

    /**
     * As above, but insists the change was accepted, and hands back the body.
     *
     * The body matters. AppointmentsController serialises the UPDATE response
     * with exclude_user: false but the SHOW response with exclude_user: true,
     * so GET /appointments/{id} carries no user block at all and a rename read
     * back that way always looks like null. The PATCH response is the only
     * place the new name is visible.
     */
    public static JsonPath changeAppointment(int appointmentId,
                                             Map<String, Object> changes) {
        io.restassured.response.Response response = given().spec(Api.journey())
                .pathParam("id", appointmentId)
                .body(Map.of("appointment", changes))
                .when().patch("/appointments/{id}");
        if (response.statusCode() != 200) {
            throw new AssertionError(
                "PATCH /appointments/" + appointmentId + " was refused with "
              + response.statusCode() + "\n  sent: " + changes
              + "\n  got:  " + response.asString());
        }
        return response.jsonPath();
    }

    /** status is one of: pending confirmed arrived started completed no_show cancelled */
    public static void setAppointmentStatus(int appointmentId, String status) {
        given().spec(Api.journey())
                .pathParam("id", appointmentId)
                .body(Map.of("status", status))
                .when().patch("/appointments/{id}/status")
                .then().statusCode(200);
    }

    /** Raise a draft bill. Returns the bill id. */
    public static int createBill(int customerId, Catalogue catalogue,
                                 int serviceLines, int productLines,
                                 int staffId, BigDecimal discountPct) {
        return createBill(customerId, catalogue, serviceLines, productLines,
                          staffId, discountPct, null);
    }

    /**
     * Raise a draft bill, optionally overriding tax for THIS bill only.
     *
     * gstEnabled = FALSE makes the bill untaxed no matter what the salon is
     * configured for: BillComposer copies the flag onto the bill and
     * TotalsCalculator reads it from there, never consulting the salon. That is
     * why a GST-off test needs no global settings change and stays safe to run
     * alongside everything else.
     */
    public static int createBill(int customerId, Catalogue catalogue,
                                 int serviceLines, int productLines,
                                 int staffId, BigDecimal discountPct,
                                 Boolean gstEnabled) {
        return createBill(customerId, catalogue, serviceLines, productLines,
                          staffId, discountPct, gstEnabled, null, null);
    }

    /**
     * As above, plus ONE line for a service that is not the catalogue's own.
     *
     * API-E2E-008 swaps the appointment onto QA Blow Dry and then has to bill
     * for it. Everything else in the suite sells QA Haircut, so rather than
     * teach Catalogue about arbitrary baskets, the caller names the service and
     * the price it expects to pay for it.
     */
    public static int createBill(int customerId, Catalogue catalogue,
                                 int serviceLines, int productLines,
                                 int staffId, BigDecimal discountPct,
                                 Boolean gstEnabled,
                                 Integer extraServiceId, BigDecimal extraPrice) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (extraServiceId != null) {
            items.add(Map.of("type", "service", "ref_id", extraServiceId,
                             "qty", 1, "unit_price", extraPrice,
                             "staff_id", staffId));
        }
        for (int i = 0; i < serviceLines; i++) {
            items.add(Map.of("type", "service", "ref_id", catalogue.serviceId,
                             "qty", 1, "unit_price", catalogue.servicePrice,
                             "staff_id", staffId));
        }
        for (int i = 0; i < productLines; i++) {
            items.add(Map.of("type", "product", "ref_id", catalogue.productId,
                             "qty", 1, "unit_price", catalogue.productPrice,
                             "staff_id", staffId));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId);
        body.put("status", "draft");
        body.put("items", items);
        if (discountPct.signum() > 0) {
            body.put("discount", Map.of("pct", discountPct, "flat", 0));
        }
        if (gstEnabled != null) {
            body.put("tax", Map.of("gst_enabled", gstEnabled));
        }

        return given().spec(Api.journey())
                .body(body)
                .when().post("/api/v1/billing/bills")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    /** What the API says this bill comes to. Read back so we can compare it. */
    public static BigDecimal netPayable(int billId) {
        return Money.of(readBill(billId).getDouble("data.net_payable"));
    }

    /**
     * Settle the bill in full.
     *
     * The payload is "payment" SINGULAR, and the API refuses a mismatched
     * amount with "Payment amount does not match net payable total" - so the
     * amount is read back from the bill rather than assumed. Salon 4550 charges
     * 18% GST where 4536 charges 5%; hardcoding either would break one of them.
     */
    public static void settleInFull(int billId, String mode) {
        BigDecimal due = netPayable(billId);
        given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("payment", Map.of("mode", mode, "amount_paid", due)))
                .when().post("/api/v1/billing/bills/{id}/finalize")
                .then().statusCode(201);
    }

    /** Refund everything still refundable. A reason is mandatory - it is the audit trail. */
    public static void refundInFull(int billId, String reason) {
        given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("reason", reason))
                .when().patch("/api/v1/billing/bills/{id}/refund")
                .then().statusCode(200);
    }

    /** Refund a named slice of the bill. A part refund is a price adjustment,
     *  not an unwind, so the sale stays counted and the status becomes
     *  partially_refunded. */
    public static void refundPart(int billId, BigDecimal amount, String reason) {
        given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("amount", amount, "reason", reason))
                .when().patch("/api/v1/billing/bills/{id}/refund")
                .then().statusCode(200);
    }

    /** Send the settlement call again, unchanged. Returns the status code.
     *  The API may refuse (any 4xx is a correct answer) or absorb it; what it
     *  must not do is collect twice, and only the reports can prove that. */
    public static int repeatPayment(int billId, BigDecimal amount, String mode) {
        return given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("mode", mode, "amount_paid", amount))
                .when().post("/api/v1/billing/bills/{id}/payments")
                .then().extract().statusCode();
    }

    /** A refund the API should refuse. Returns the status code so the test can
     *  assert it was refused rather than silently accepted. */
    public static int attemptRefund(int billId, BigDecimal amount, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (amount != null) {
            body.put("amount", amount);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return given().spec(Api.journey())
                .pathParam("id", billId)
                .body(body)
                .when().patch("/api/v1/billing/bills/{id}/refund")
                .then().extract().statusCode();
    }

    public static JsonPath readBill(int billId) {
        return given().spec(Api.journey())
                .pathParam("id", billId)
                .when().get("/api/v1/billing/bills/{id}")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /** Guard against a journey that straddles midnight, where "today" changes. */
    public static LocalDate today() {
        return LocalDate.now();
    }

    public static LocalTime now() {
        return LocalTime.now();
    }
}

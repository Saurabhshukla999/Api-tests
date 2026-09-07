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

    // -----------------------------------------------------------------------
    // Block 5 needs every knob the billing API has, so here they all are.
    // Confirmed against the live API 4 Sep 2026:
    //     discount: {pct, flat}     both apply, and they ADD
    //     tax:      {gst_enabled, mode}   mode is "exclusive" or "inclusive"
    // -----------------------------------------------------------------------
    /**
     * The full bill builder. Every other createBill above delegates here.
     *
     * discountFlat  a rupee amount off the whole bill, on top of any pct
     * taxMode       "inclusive" makes the listed price the FINAL price and
     *               backs the tax out of it; "exclusive" adds tax on top.
     *               null leaves the salon default (exclusive on 4550).
     * extraServiceId / extraPrice  one line for a service that is not the
     *               catalogue's own - used when billing QA Blow Dry.
     */
    public static int createBillFully(int customerId, Catalogue catalogue,
                                      int serviceLines, int productLines, int staffId,
                                      BigDecimal discountPct, BigDecimal discountFlat,
                                      Boolean gstEnabled, String taxMode,
                                      Integer extraServiceId, BigDecimal extraPrice) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (extraServiceId != null) {
            items.add(Map.of("type", "service", "ref_id", extraServiceId, "qty", 1,
                             "unit_price", extraPrice, "staff_id", staffId));
        }
        for (int i = 0; i < serviceLines; i++) {
            items.add(Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                             "unit_price", catalogue.servicePrice, "staff_id", staffId));
        }
        for (int i = 0; i < productLines; i++) {
            items.add(Map.of("type", "product", "ref_id", catalogue.productId, "qty", 1,
                             "unit_price", catalogue.productPrice, "staff_id", staffId));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId);
        body.put("status", "draft");
        body.put("items", items);
        if (discountPct.signum() > 0 || discountFlat.signum() > 0) {
            body.put("discount", Map.of("pct", discountPct, "flat", discountFlat));
        }
        if (gstEnabled != null || taxMode != null) {
            Map<String, Object> tax = new LinkedHashMap<>();
            if (gstEnabled != null) { tax.put("gst_enabled", gstEnabled); }
            if (taxMode != null)    { tax.put("mode", taxMode); }
            body.put("tax", tax);
        }

        return given().spec(Api.journey())
                .body(body)
                .when().post("/api/v1/billing/bills")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    /**
     * Replace the basket on a DRAFT bill. PUT /bills/{id}.
     *
     * The bill is re-priced from scratch by TotalsCalculator, so this is how
     * API-E2E-072 proves the reports use the FINAL basket and not the one the
     * bill was created with.
     */
    public static void updateBillItems(int billId, Catalogue catalogue,
                                       int serviceLines, int productLines, int staffId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < serviceLines; i++) {
            items.add(Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                             "unit_price", catalogue.servicePrice, "staff_id", staffId));
        }
        for (int i = 0; i < productLines; i++) {
            items.add(Map.of("type", "product", "ref_id", catalogue.productId, "qty", 1,
                             "unit_price", catalogue.productPrice, "staff_id", staffId));
        }
        given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("items", items))
                .when().put("/api/v1/billing/bills/{id}")
                .then().statusCode(200);
    }

    /** Try to settle with an amount that may be wrong. Returns the status code. */
    public static int attemptFinalize(int billId, BigDecimal amount, String mode) {
        return given().spec(Api.journey())
                .pathParam("id", billId)
                .body(Map.of("payment", Map.of("mode", mode, "amount_paid", amount)))
                .when().post("/api/v1/billing/bills/{id}/finalize")
                .then().extract().statusCode();
    }

    /** The salon's billing settings. round_off_enabled lives at
     *  data.setting.round_off_enabled. */
    public static JsonPath readSalonSetting(String salonId) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .when().get("/salons/{salonId}/settings")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /**
     * Flip the salon-wide round-off switch. Returns the status code.
     *
     * SALON-WIDE. There is no per-bill override - TotalsCalculator reads
     * bill.salon.salon_setting.round_off_enabled directly - so any test that
     * touches this MUST restore it in a finally block or every later journey
     * in the run computes the wrong expected total.
     */
    public static int setRoundOff(String salonId, boolean enabled) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.of("setting", Map.of("round_off_enabled", enabled)))
                .when().patch("/salons/{salonId}/settings")
                .then().extract().statusCode();
    }

    // -----------------------------------------------------------------------
    // Staff, attendance and commission - what Block 6 runs on.
    // Confirmed against the live API 7 Sep 2026.
    // -----------------------------------------------------------------------
    /**
     * Hire a stylist with a commission arrangement.
     *
     * commissionType is one of none / percentage / flat (Staff's own enum).
     * commissionValue is the percent or the rupee amount; null for "none".
     */
    public static int createStaff(String salonId, String name, String commissionType,
                                  Object commissionValue, List<Integer> serviceIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phone", newPhone());
        body.put("job_title", "Beautician");
        body.put("shift_start", "09:00");
        body.put("shift_end", "21:00");
        body.put("commission_type", commissionType);
        if (commissionValue != null) {
            body.put("commission_value", commissionValue);
        }
        body.put("salon_service_ids", serviceIds);

        return given().spec(Api.journey())
                .pathParam("salonId", salonId).body(body)
                .when().post("/salons/{salonId}/staff")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    /**
     * A bill from raw line maps, so a caller can put a DIFFERENT staff_id on
     * each line. Every other createBill puts one stylist on the whole basket,
     * which cannot express "the cut was Priya, the blow-dry was Sam".
     */
    public static int createBillLines(int customerId, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId);
        body.put("status", "draft");
        body.put("items", lines);
        return given().spec(Api.journey())
                .body(body)
                .when().post("/api/v1/billing/bills")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    // -----------------------------------------------------------------------
    // Inventory - what Block 7 runs on. Confirmed 7 Sep 2026.
    // -----------------------------------------------------------------------
    /** Stock a new product. Returns its id. */
    public static int createProduct(String salonId, String name, int costPrice,
                                    int sellingPrice, int openingStock, int reorderLevel) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.ofEntries(
                        Map.entry("name", name),
                        Map.entry("brand", "QA Brand"),
                        Map.entry("category", "Haircare"),
                        Map.entry("unit", "pcs"),
                        Map.entry("cost_price", costPrice),
                        Map.entry("selling_price", sellingPrice),
                        Map.entry("opening_stock", openingStock),
                        Map.entry("reorder_level", reorderLevel)))
                .when().post("/salons/{salonId}/products")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
    }

    /**
     * One product's row from the LIST.
     *
     * Deliberately not GET /salons/{id}/products/{id} - that answers HTTP 200
     * with a body of {"error":"not_found"} even for a product that exists
     * (defect D1), so a test reading it would silently see nothing and call it
     * a stock of zero.
     *
     * The row carries: stock_qty, reorder_level, stock_state, cost_price,
     * selling_price - all as STRINGS ("8.0"), not numbers.
     */
    public static Map<String, Object> productRow(String salonId, int productId) {
        for (int page = 1; page <= 20; page++) {
            List<Map<String, Object>> items = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .queryParam("page", page).queryParam("per_page", 100)
                    .when().get("/salons/{salonId}/products")
                    .then().statusCode(200)
                    .extract().jsonPath().getList("data.items");
            if (items == null || items.isEmpty()) {
                break;
            }
            for (Map<String, Object> item : items) {
                if (String.valueOf(item.get("id")).equals(String.valueOf(productId))) {
                    return item;
                }
            }
        }
        throw new AssertionError("product " + productId + " is not in salon "
                + salonId + "'s product list at all");
    }

    /** How many units the salon believes it holds. */
    public static BigDecimal stockOf(String salonId, int productId) {
        return new BigDecimal(String.valueOf(productRow(salonId, productId).get("stock_qty")));
    }

    /** in_stock / low_stock / out_of_stock, as the API labels it. */
    public static String stockState(String salonId, int productId) {
        return String.valueOf(productRow(salonId, productId).get("stock_state"));
    }

    /** The salon dashboard, which summarises what the reports say separately. */
    public static JsonPath dashboard(String salonId, String range) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("range", range)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/dashboard/overview")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /** active / on_leave / inactive. Returns the status code. */
    public static int setStaffStatus(String salonId, int staffId, String status) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId).pathParam("id", staffId)
                .body(Map.of("status", status))
                .when().put("/salons/{salonId}/staff/{id}")
                .then().extract().statusCode();
    }

    /**
     * Mark a day's attendance. PUT on the collection, keyed by (employee, date)
     * in the BODY rather than by id, so a repeated save corrects rather than
     * duplicates - the endpoint is deliberately idempotent.
     *
     * Measured: "absent" is accepted on its own, but present / late / half_day
     * are refused with "check_in is required". Pass the times for those.
     * Returns the status code so a test can assert a refusal too.
     */
    public static int markAttendance(String salonId, int staffId, LocalDate date,
                                     String status, String checkIn, String checkOut) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("employee_id", staffId);
        entry.put("date", date.toString());
        entry.put("status", status);
        if (checkIn != null)  { entry.put("check_in", checkIn); }
        if (checkOut != null) { entry.put("check_out", checkOut); }

        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.of("attendance", entry))
                .when().put("/salons/{salonId}/attendance/entries")
                .then().extract().statusCode();
    }

    /** Today's attendance board: data.summary counts plus a roster row each. */
    public static JsonPath attendanceDaily(String salonId, LocalDate date) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("date", date.toString())
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/attendance/daily")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    public static JsonPath attendanceSummary(String salonId, String range) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("range", range)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/attendance/summary")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /**
     * One stylist's row in the Staff Performance report.
     *
     * The ROWS endpoint, not the summary - the summary is a salon-wide total
     * that cannot tell one stylist from another. Note the payload nests the
     * page inside data, so the rows are at data.data; reading data[] instead
     * finds nothing and every figure silently looks like zero.
     *
     * Fields on a row: total_appointments, completed_appointments,
     * services_count, service_revenue, product_revenue, total_revenue,
     * commission_base, commission_amount, average_bill_value, average_rating.
     */
    public static Map<String, Object> staffRow(String salonId, int staffId) {
        JsonPath rows = given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("range", "today").queryParam("limit", 200)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/reports/staff_performance/rows")
                .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> list = rows.getList("data.data");
        if (list == null) { list = rows.getList("data"); }
        if (list != null) {
            for (Map<String, Object> row : list) {
                if (String.valueOf(row.get("staff_id")).equals(String.valueOf(staffId))) {
                    return row;
                }
            }
        }
        return Map.of();
    }

    /** One figure off a stylist's row, as money. Missing row or field is zero. */
    public static BigDecimal staffFigure(String salonId, int staffId, String field) {
        Object v = staffRow(salonId, staffId).get(field);
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
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

    // =====================================================================
    //  EXPENSES  (Block 8)
    // =====================================================================
    //
    // Not salon-scoped. `POST /salons/{id}/expenses` answers 404 - the write
    // route is the top-level `resources :expenses`, and the salon comes from
    // the TOKEN, not the path:
    //
    //     ExpensesController#get_salon
    //         @salon = Salon.find_by_user_id(@current_user.id)
    //
    // which is why these helpers take no salon id. The journey token belongs to
    // salon 4550's owner, so every expense written here lands on 4550.
    //
    // Two shapes are accepted - a flat body and one nested under "expense".
    // Flat is used here; Rails wrap_parameters folds it into the nested form
    // the controller's `params.require(:expense)` wants.
    //
    // expense_type is Credit or Debit, and it is NOT a category:
    //   Debit    a real cost. Counts toward Operating Expenses.
    //   Credit   income. Does NOT count. The application auto-writes one of
    //            these for the service price every time an appointment
    //            completes, so salon 4550 carries thousands of them.

    /** A DEBIT expense dated `date`. Returns its id. */
    public static int createExpense(String name, BigDecimal amount, LocalDate date) {
        return createExpense(name, "Debit", amount, date, null);
    }

    public static int createExpense(String name, String expenseType, BigDecimal amount,
                                    LocalDate date, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("expense_type", expenseType);
        body.put("amount", amount);
        body.put("date", date.toString());
        if (description != null) {
            body.put("description", description);
        }
        return given().spec(Api.journey())
                .body(body)
                .when().post("/expenses")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.expense.id");
    }

    /** Read an expense back off the salon's ledger. Null if it is not there. */
    public static Map<String, Object> expenseRow(String salonId, int expenseId) {
        List<Map<String, Object>> rows = given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .when().get("/salons/{salonId}/expenses")
                .then().statusCode(200)
                .extract().jsonPath().getList("data.expenses");
        for (Map<String, Object> row : rows) {
            if (row.get("id") != null
                    && Integer.parseInt(row.get("id").toString()) == expenseId) {
                return row;
            }
        }
        return null;
    }

    /** The ledger's own two totals, as the salon list returns them. */
    public static BigDecimal expenseLedgerTotal(String salonId, String which) {
        Object value = given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .when().get("/salons/{salonId}/expenses")
                .then().statusCode(200)
                .extract().jsonPath().get("data." + which);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /** PUT replaces the whole record, so every field is resent. */
    public static void updateExpense(int expenseId, String name, String expenseType,
                                     BigDecimal amount, LocalDate date) {
        given().spec(Api.journey())
                .pathParam("id", expenseId)
                .body(Map.of("name", name, "expense_type", expenseType,
                             "amount", amount, "date", date.toString()))
                .when().put("/expenses/{id}")
                .then().statusCode(200);
    }

    public static void deleteExpense(int expenseId) {
        given().spec(Api.journey())
                .pathParam("id", expenseId)
                .when().delete("/expenses/{id}")
                .then().statusCode(200);
    }

    /** The dashboard's own expense panel - a different query from the report. */
    public static JsonPath dashboardExpenses(String salonId, String range) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("range", range)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/dashboard/expenses")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    // =====================================================================
    //  DASHBOARD TABS  (Block 9)
    // =====================================================================
    //
    // The dashboard is not one endpoint. `/dashboard` alone answers nothing;
    // each tab is its own route under `/salons/{id}/dashboard/`:
    //
    //     overview  attendance  employees  packages  expenses
    //     inventory analytics   reminders  insights  meta
    //     action_center/low_stock, /membership_renewals, /complaints
    //
    // and each one is a SEPARATE query over the same data as the reports. That
    // is the whole reason Block 9 exists: two queries that should agree can
    // drift, and the dashboard is the surface the owner actually looks at.
    //
    // The overview announces its own basis in `data.revenue_basis`, which reads
    // `gross_incl_tax_net_of_refunds` - tax included, refunds already taken off.
    // That is the same basis as sales.total_revenue, which is why the two are
    // asserted equal rather than merely proportional.

    public static JsonPath dashboardTab(String salonId, String tab, String range) {
        return given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .queryParam("range", range)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/dashboard/" + tab)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /**
     * One entry out of the overview's revenue_sources array, by key:
     * services, products, memberships, combo_packs.
     *
     * Read by KEY rather than by position - the array order is not promised,
     * and a test that indexed [0] would silently start asserting about
     * memberships the day the order changed.
     */
    public static BigDecimal revenueSource(JsonPath overview, String key) {
        List<Map<String, Object>> sources = overview.getList("data.revenue_sources");
        if (sources == null) {
            return BigDecimal.ZERO;
        }
        for (Map<String, Object> source : sources) {
            if (key.equals(source.get("key"))) {
                Object value = source.get("value");
                return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
            }
        }
        return BigDecimal.ZERO;
    }

    /** Is this product on the dashboard's low-stock list, and how badly? */
    public static String lowStockSeverity(JsonPath inventory, int productId) {
        List<Map<String, Object>> rows = inventory.getList("data.low_stock");
        if (rows == null) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            Object id = row.get("product_id");
            if (id != null && Integer.parseInt(id.toString()) == productId) {
                return String.valueOf(row.get("severity"));
            }
        }
        return null;
    }

    /**
     * The overview's payment_summary, totalled across every mode.
     *
     * Two shapes in the wild: an ARRAY of {key,label,amount,pct} when the day
     * has more than one payment mode, and a bare object when it has exactly
     * one. Both are handled here rather than in the test, because a block that
     * happens to run on a cash-only day would otherwise pass and then break the
     * first time somebody pays by card.
     */
    public static BigDecimal paymentSummaryTotal(JsonPath overview) {
        Object node = overview.get("data.payment_summary");
        BigDecimal total = BigDecimal.ZERO;
        if (node instanceof List<?> list) {
            for (Object entry : list) {
                total = total.add(amountOf(entry));
            }
        } else if (node != null) {
            total = amountOf(node);
        }
        return total;
    }

    /** One payment mode's amount out of payment_summary, by key ("cash", "card"). */
    public static BigDecimal paymentMode(JsonPath overview, String key) {
        Object node = overview.get("data.payment_summary");
        List<?> list = node instanceof List<?> l ? l
                     : node == null ? List.of() : List.of(node);
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && key.equals(map.get("key"))) {
                return amountOf(entry);
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal amountOf(Object entry) {
        if (entry instanceof Map<?, ?> map) {
            Object amount = map.get("amount");
            if (amount != null) {
                return new BigDecimal(amount.toString());
            }
        }
        return BigDecimal.ZERO;
    }
}

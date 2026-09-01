package com.nearz.api;

import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * What the journey salon can sell, and where an appointment can go.
 *
 * Salon 4550 started empty - no services, products or staff - which is exactly
 * what makes it a good place to run journeys and exactly why this class exists.
 * Everything is find-or-create, so the first run seeds and every run after
 * makes three GETs and no writes.
 *
 * Every value below was learned from the API refusing the obvious one:
 *
 *     job_title  must be Admin, Manager, Receptionist or Beautician
 *     unit       must be ml, g, pcs or box
 *     phone      must be a 10-digit Indian mobile
 *     the salon must be open on the day you book
 *     the stylist's shift must cover the slot you book
 */
public final class Catalogue {

    /** 10:00-19:30 in half hours: the window every seeded stylist covers. */
    private static final int FIRST_HOUR = 10;
    private static final int SLOTS = 19;

    /** Stylists to seed. SLOTS x ROSTER is the salon's bookings-per-DAY limit,
     *  because today's appointments outlive the run that made them. */
    private static final int ROSTER = 20;

    /** The suite bills against these two by NAME, never by list position. */
    private static final String SERVICE_NAME = "QA Haircut";
    /** Seeded stylists are named "QA Stylist 1".."QA Stylist 20". Only these are
     *  used. Anything else on the salon - a stylist someone duplicated in the
     *  dashboard, say - may not be assigned to our service, and the API refuses
     *  the booking with "X is not assigned to QA Haircut". */
    private static final java.util.regex.Pattern OURS =
            java.util.regex.Pattern.compile("^QA Stylist \\d+$");
    private static final String PRODUCT_NAME = "QA Shampoo";
    private static final int SERVICE_PRICE = 1000;
    private static final int PRODUCT_PRICE = 200;

    /** A second service, so API-E2E-008 has something to swap TO. Priced
     *  differently from QA Haircut on purpose - a swap that changed nothing
     *  about the money would prove nothing about the money. */
    private static final String SECOND_SERVICE_NAME = "QA Blow Dry";
    private static final int SECOND_SERVICE_PRICE = 600;
    /** Assigned to BOTH services, so it can take the appointment either way. */
    private static final String DUAL_STAFF_NAME = "QA Dual Stylist";

    public final int serviceId;
    public final BigDecimal servicePrice;
    public final int productId;
    public final BigDecimal productPrice;
    public final List<Integer> staffIds;
    public final BigDecimal gstRate;      // fraction: 0.18 for 18%
    public final boolean roundOffEnabled; // bills settle in whole rupees

    private final Set<String> takenSlots = new HashSet<>();
    private final Set<Integer> unusableStaff = new HashSet<>();
    private final String salonId;

    private Catalogue(String salonId, int serviceId, BigDecimal servicePrice,
                      int productId, BigDecimal productPrice,
                      List<Integer> staffIds, BigDecimal gstRate,
                      boolean roundOffEnabled) {
        this.salonId = salonId;
        this.serviceId = serviceId;
        this.servicePrice = servicePrice;
        this.productId = productId;
        this.productPrice = productPrice;
        this.staffIds = staffIds;
        this.gstRate = gstRate;
        this.roundOffEnabled = roundOffEnabled;
    }

    // -----------------------------------------------------------------------
    // setup
    // -----------------------------------------------------------------------
    public static Catalogue prepare(String salonId) {
        openEveryDay(salonId);

        JsonPath tax = given().spec(Api.journey())
                .get("/api/v1/billing/tax_config")
                .then().statusCode(200).extract().jsonPath();
        boolean gstOn = Boolean.TRUE.equals(tax.getBoolean("data.gst_enabled"));
        BigDecimal rate = gstOn
                ? Money.of(tax.getDouble("data.gst_rate"))
                       .divide(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // Whether bills settle in whole rupees. The API returns nothing when
        // the salon has never opened the settings screen, and TotalsCalculator
        // treats anything other than an explicit false as ON.
        Object flag = given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .when().get("/salons/{salonId}/settings")
                .then().statusCode(200)
                .extract().jsonPath().get("data.round_off_enabled");
        boolean roundOff = !Boolean.FALSE.equals(flag);

        Item service = service(salonId);
        List<Integer> staff = roster(salonId, service.id());
        Item product = product(salonId);

        return new Catalogue(salonId, service.id(), service.price(),
                             product.id(), product.price(), staff, rate, roundOff);
    }

    /**
     * A closed day rejects every appointment with 422 ("the salon is closed on
     * Fridays"). Journeys book for today, because today is the range their
     * report deltas are read in, so today must always be an open day.
     */
    private static void openEveryDay(String salonId) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int weekday = 0; weekday < 7; weekday++) {
            days.add(Map.of("weekday", weekday, "closed", false,
                            "start_time", "09:00", "end_time", "21:00"));
        }
        given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.of("days", days))
                .when().put("/salons/{salonId}/working_days")
                .then().statusCode(200);
    }

    /** One catalogue entry: the id to bill against and the price to expect. */
    private record Item(int id, BigDecimal price) { }

    /**
     * The suite's own service, found by name.
     *
     * Deliberately NOT "whichever service comes back first". On 28 Aug 2026 a
     * leftover product from the CRUD tests sat at the top of the list, the
     * catalogue picked it, and four money assertions failed by twenty paise
     * against a price nobody expected. Naming the record makes the suite immune
     * to whatever else is lying around on the salon.
     */
    private static Item service(String salonId) {
        return findOrCreateService(salonId, SERVICE_NAME, SERVICE_PRICE);
    }

    /** Find a service by NAME, or create it. Never by list position - the
     *  first entry is whatever someone last added in the dashboard. */
    private static Item findOrCreateService(String salonId, String name, int price) {
        JsonPath body = given().spec(Api.journey())
                .queryParam("per_page", 200)
                .get("/api/v1/billing/services")
                .then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> services = body.getList("data.services");
        if (services != null) {
            for (Map<String, Object> s : services) {
                if (name.equals(s.get("name"))) {
                    return new Item(Integer.parseInt(s.get("id").toString()),
                                    Money.of((Number) s.get("price")));
                }
            }
        }
        int id = given().spec(Api.journey())
                .body(Map.of("salon_service", Map.of(
                        "salon_id", Integer.parseInt(salonId),
                        "custom_name", name,
                        "category", "Hair",
                        "price", price,
                        "duration_in_minutes", 30,
                        "gender", "unisex",
                        "active", true)))
                .when().post("/salon_services")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
        return new Item(id, new BigDecimal(price));
    }

    /** The suite's own product, found by name. Same reasoning as service(). */
    private static Item product(String salonId) {
        JsonPath body = given().spec(Api.journey())
                .queryParam("per_page", 200)
                .get("/api/v1/billing/products")
                .then().statusCode(200).extract().jsonPath();
        List<Map<String, Object>> products = body.getList("data.products");
        if (products != null) {
            for (Map<String, Object> p : products) {
                if (PRODUCT_NAME.equals(p.get("name"))) {
                    return new Item(Integer.parseInt(p.get("id").toString()),
                                    Money.of((Number) p.get("price")));
                }
            }
        }
        int id = given().spec(Api.journey())
                .pathParam("salonId", salonId)
                .body(Map.ofEntries(
                        Map.entry("name", PRODUCT_NAME),
                        Map.entry("brand", "QA Brand"),
                        Map.entry("category", "Haircare"),
                        Map.entry("unit", "pcs"),
                        Map.entry("cost_price", 100),
                        Map.entry("selling_price", PRODUCT_PRICE),
                        Map.entry("opening_stock", 5000),
                        Map.entry("reorder_level", 10)))
                .when().post("/salons/{salonId}/products")
                .then().statusCode(201)
                .extract().jsonPath().getInt("data.id");
        return new Item(id, new BigDecimal(PRODUCT_PRICE));
    }

    /**
     * A roster, not one stylist. The API refuses a double booking, so one
     * stylist caps the salon at 19 appointments a day.
     */
    private static List<Integer> roster(String salonId, int serviceId) {
        JsonPath body = given().spec(Api.journey())
                .get("/api/v1/billing/staff")
                .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> all = body.getList("data");
        List<Integer> ids = new ArrayList<>();
        if (all != null) {
            for (Map<String, Object> member : all) {
                String name = String.valueOf(member.get("name"));
                if (OURS.matcher(name).matches()) {
                    ids.add(Integer.parseInt(member.get("id").toString()));
                }
            }
        }

        while (ids.size() < ROSTER) {
            int n = ids.size() + 1;
            // A fresh phone every time. The fixed 90000000NN pattern collided
            // with the very first stylist ever seeded here and every creation
            // came back 422.
            int id = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .body(Map.of("name", "QA Stylist " + n,
                                 "phone", Steps.newPhone(),
                                 "job_title", "Beautician",
                                 "shift_start", "09:00",
                                 "shift_end", "21:00",
                                 "commission_type", "none",
                                 // Assign the service, or the API refuses every
                                 // booking with "not assigned to QA Haircut".
                                 "salon_service_ids", List.of(serviceId)))
                    .when().post("/salons/{salonId}/staff")
                    .then().statusCode(201)
                    .extract().jsonPath().getInt("data.id");
            ids.add(id);
        }
        return ids;
    }

    // -----------------------------------------------------------------------
    // booking slots
    // -----------------------------------------------------------------------
    /**
     * The next free (start, end, stylist) on today's calendar.
     *
     * Today's appointments outlive the run that made them, so a counter
     * starting at zero collides with this morning's bookings the second time
     * the suite runs in a day. Read the calendar, allocate only what is free.
     */
    /**
     * Stop offering a stylist the API will not accept.
     *
     * Salon 4550 is shared, and a stylist that someone assigns to a different
     * service - or duplicates in the dashboard - gets refused with "X is not
     * assigned to QA Haircut". Striking them off is better than failing the
     * journey over someone else's edit.
     */
    public void doNotUse(int staffId) {
        unusableStaff.add(staffId);
    }

    // -----------------------------------------------------------------------
    // a SECOND service, for the "service changed" journey only
    // -----------------------------------------------------------------------
    /**
     * API-E2E-008 swaps the service on an existing appointment, which needs a
     * second service to swap TO and a stylist assigned to it.
     *
     * Resolved lazily and cached: one journey out of seventy-five needs this,
     * and making every @BeforeClass pay two extra requests for it would be
     * waste. Everything here is find-or-create like the rest of the class.
     */
    private Item second;
    private Integer secondStaff;

    public int secondServiceId() {
        return secondService().id();
    }

    public BigDecimal secondServicePrice() {
        return secondService().price();
    }

    private Item secondService() {
        if (second == null) {
            second = findOrCreateService(salonId, SECOND_SERVICE_NAME, SECOND_SERVICE_PRICE);
        }
        return second;
    }

    /**
     * A stylist assigned to the SECOND service.
     *
     * The roster stylists are assigned to QA Haircut only, so re-pointing an
     * appointment at QA Blow Dry has to move the stylist in the same call or
     * the API refuses it with "X is not assigned to QA Blow Dry".
     */
    public int secondServiceStaffId() {
        if (secondStaff == null) {
            JsonPath body = given().spec(Api.journey())
                    .get("/api/v1/billing/staff")
                    .then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> all = body.getList("data");
            if (all != null) {
                for (Map<String, Object> member : all) {
                    if (DUAL_STAFF_NAME.equals(String.valueOf(member.get("name")))) {
                        secondStaff = Integer.parseInt(member.get("id").toString());
                        return secondStaff;
                    }
                }
            }
            secondStaff = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .body(Map.of("name", DUAL_STAFF_NAME,
                                 "phone", Steps.newPhone(),
                                 "job_title", "Beautician",
                                 "shift_start", "09:00",
                                 "shift_end", "21:00",
                                 "commission_type", "none",
                                 "salon_service_ids",
                                 List.of(serviceId, secondServiceId())))
                    .when().post("/salons/{salonId}/staff")
                    .then().statusCode(201)
                    .extract().jsonPath().getInt("data.id");
        }
        return secondStaff;
    }

    public Booking nextFreeSlot() {
        if (takenSlots.isEmpty()) {
            readTodaysCalendar();
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            String start = startOf(slot);
            for (int staffId : staffIds) {
                if (unusableStaff.contains(staffId)) {
                    continue;
                }
                String key = staffId + "@" + start;
                if (takenSlots.add(key)) {
                    return new Booking(start, endOf(slot), staffId);
                }
            }
        }
        throw new IllegalStateException(
            "today's calendar is full: all " + (SLOTS * staffIds.size())
          + " places (" + SLOTS + " slots x " + staffIds.size() + " stylists) "
          + "are taken on " + LocalDate.now() + ". Appointments live for the "
          + "whole day, so this is a limit per DAY, not per run. Raise ROSTER "
          + "in Catalogue.java, or wait for tomorrow.");
    }

    /**
     * The next free slot for ONE named stylist.
     *
     * nextFreeSlot() only ever offers roster stylists, so the dual-assigned
     * stylist API-E2E-008 needs is invisible to it and every run of 008 asked
     * for 10:00 - the second run of the day was refused with "QA Dual Stylist
     * already has a booking from 10:00 am". The calendar read behind
     * takenSlots covers every stylist on the salon, so it can answer this too.
     */
    public Booking nextFreeSlotFor(int staffId) {
        if (takenSlots.isEmpty()) {
            readTodaysCalendar();
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            String start = startOf(slot);
            if (takenSlots.add(staffId + "@" + start)) {
                return new Booking(start, endOf(slot), staffId);
            }
        }
        throw new IllegalStateException(
            "stylist " + staffId + " is booked for all " + SLOTS + " slots on "
          + LocalDate.now() + ".");
    }

    /**
     * Read every appointment on today's calendar, page by page.
     *
     * One request is not enough: Paginatable caps per_page at 100, so asking
     * for 200 silently returns 100 and the allocator thinks slots are free that
     * are not. That produced a run of "already has a booking" 422s on
     * 28 Aug 2026 with the calendar only 62/399 full.
     */
    private void readTodaysCalendar() {
        for (int page = 1; page <= 20; page++) {
            JsonPath body = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .queryParam("date", LocalDate.now().toString())
                    .queryParam("page", page)
                    .queryParam("per_page", 100)
                    .when().get("/salons/{salonId}/appointments")
                    .then().statusCode(200).extract().jsonPath();

            List<Map<String, Object>> appointments = body.getList("data.appointments");
            if (appointments == null || appointments.isEmpty()) {
                break;
            }
            for (Map<String, Object> appointment : appointments) {
                String status = String.valueOf(appointment.get("status")).toLowerCase();
                if (status.contains("cancel") || status.contains("declin")
                        || status.contains("show")) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items =
                    (List<Map<String, Object>>) appointment.get("items");
                if (items == null) {
                    continue;
                }
                for (Map<String, Object> item : items) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> staff = (Map<String, Object>) item.get("staff");
                    Object start = item.get("start_time");
                    if (staff != null && staff.get("id") != null && start != null) {
                        takenSlots.add(staff.get("id") + "@"
                                     + start.toString().substring(0, 5));
                    }
                }
            }
            if (appointments.size() < 100) {
                break;
            }
        }
        takenSlots.add("none@00:00");   // marks the calendar as read
    }

    private static String startOf(int slot) {
        return String.format("%02d:%02d", FIRST_HOUR + slot / 2, 30 * (slot % 2));
    }

    private static String endOf(int slot) {
        return slot % 2 == 0
                ? String.format("%02d:30", FIRST_HOUR + slot / 2)
                : String.format("%02d:00", FIRST_HOUR + slot / 2 + 1);
    }

    /** One free place on today's calendar. */
    public record Booking(String start, String end, int staffId) { }
}

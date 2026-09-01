package com.nearz.api;

import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Before-and-after snapshots of the reports a journey can move.
 *
 * A journey is only meaningful if you can say what the books looked like before
 * it ran and what they look like after. This class reads a report's KPI block
 * into a flat map of "report.kpi" -> number, and subtracts one snapshot from
 * another to say what moved.
 *
 * Only the reports a journey can affect are read. A journey that bills and pays
 * cannot change Inventory, so reading Inventory costs two HTTP calls and proves
 * nothing.
 *
 * Field names below were confirmed against the live API. Two traps worth
 * knowing: the KPI values are nested as kpis.<name>.value, not kpis.<name>; and
 * on the rows endpoints the pagination meta sits INSIDE data, not at the top.
 */
public final class Reports {

    public static final String SALES     = "sales";
    public static final String PAYMENTS  = "payments";
    public static final String CUSTOMERS = "customers";
    public static final String SERVICES  = "services";
    public static final String STAFF     = "staff_performance";
    public static final String PROFIT    = "profit";
    public static final String APPOINTMENTS = "appointments";

    /** The reports an enquiry-to-payment journey can move. */
    public static final List<String> MONEY_TRAIL =
        List.of(SALES, PAYMENTS, CUSTOMERS, SERVICES, STAFF, PROFIT);

    /**
     * The money trail plus the Appointments report.
     *
     * Block 4 is about the calendar rather than the till, so it needs the one
     * report the money journeys deliberately leave out. Kept as a separate list
     * so blocks 1-3 do not pay two extra requests per snapshot for a report
     * they never assert on.
     */
    public static final List<String> CALENDAR_TRAIL =
        List.of(SALES, PAYMENTS, CUSTOMERS, SERVICES, STAFF, PROFIT, APPOINTMENTS);

    /** Just the calendar, for the journeys that never raise a bill. */
    public static final List<String> CALENDAR_ONLY = List.of(APPOINTMENTS);

    private static final Map<String, List<String>> KPIS = Map.of(
        SALES,     List.of("total_revenue", "bills_count", "service_revenue",
                           "product_revenue", "total_discount"),
        PAYMENTS,  List.of("total_collected", "payments_count",
                           "failed_refunded_amount"),
        CUSTOMERS, List.of("new_customers", "total_spend_in_range"),
        SERVICES,  List.of("total_revenue", "services_sold"),
        STAFF,     List.of("total_service_revenue"),
        PROFIT,    List.of("gross_revenue", "net_profit", "tax_collected",
                           "total_discount"),
        // Reports::AppointmentsQuery#kpis. Worth knowing what each counts:
        //   total_appointments  every appointment dated in the range
        //   completed / no_show by status
        //   cancelled           CANCELLED *and* DECLINED together
        //   walk_ins            PAID bills with appointment_id nil - counted
        //                       from BILLS, not from appointments at all
        //   revenue_generated   sum(full_amount) of COMPLETED appointments,
        //                       which is the appointment's own figure and not
        //                       the bill's
        APPOINTMENTS, List.of("total_appointments", "completed", "cancelled",
                              "no_show", "walk_ins", "revenue_generated")
    );

    private Reports() { }

    /**
     * One request per report. Keys look like "sales.total_revenue".
     *
     * ---------------------------------------------------------------------
     * WHY EVERY READ CARRIES A UNIQUE _qa PARAMETER
     * ---------------------------------------------------------------------
     * Reports::BaseController caches every summary for SIXTY SECONDS when the
     * range includes today:
     *
     *     def compute_ttl
     *       if @range.includes_today? then 60.seconds
     *
     * and nothing invalidates it. EnquiriesController explicitly busts its own
     * cache after a write, with the comment "a write the user just made should
     * be visible immediately, not a minute later" - AppointmentsController and
     * the billing controllers do not.
     *
     * Measured on salon 4550, 30 Aug 2026, one appointment created at t=0:
     *
     *     t=0    plain=102   cache-busted=102
     *     t=20s  plain=102   cache-busted=103     <- the write is real,
     *                                                the plain read is stale
     *
     * A before/after snapshot taken inside one 60-second window can therefore
     * read the SAME cached payload twice and report a delta of zero for a
     * journey that really did move the books. Blocks 1-3 have been racing this
     * since they were written; they pass because most journeys take long enough
     * for the entry to expire, which is luck, not correctness.
     *
     * cache_key() MD5s the non-blank query parameters, so a parameter that is
     * different every time produces a different key and forces a real query.
     * That is what _qa is. It reads the database rather than the cache, which
     * is what a test of report CORRECTNESS wants; the staleness a real user
     * sees is a separate question, tested on its own in Block4AppointmentTest.
     */
    public static Map<String, BigDecimal> snapshot(String salonId, List<String> reports) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String report : reports) {
            JsonPath body = given().spec(Api.journey())
                    .pathParam("salonId", salonId)
                    .queryParam("range", "today")
                    .queryParam("_qa", System.nanoTime())
                    .when()
                    .get("/salons/{salonId}/reports/" + report + "/summary")
                    .then().statusCode(200)
                    .extract().jsonPath();

            for (String kpi : KPIS.getOrDefault(report, List.of())) {
                Object value = body.get("data.kpis." + kpi + ".value");
                out.put(report + "." + kpi, Money.of((Number) asNumber(value)));
            }
        }
        return out;
    }

    /** What moved between two snapshots. */
    public static BigDecimal moved(Map<String, BigDecimal> before,
                                   Map<String, BigDecimal> after,
                                   String key) {
        BigDecimal from = before.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal to   = after.getOrDefault(key, BigDecimal.ZERO);
        return Money.round(to.subtract(from));
    }

    /** Readable failure text naming the report line, both values and the gap. */
    public static String describe(Map<String, BigDecimal> before,
                                  Map<String, BigDecimal> after,
                                  String key, BigDecimal expected) {
        BigDecimal actual = moved(before, after, key);
        return String.format(
            "%n  %s%n      expected to move %12s%n      actually moved   %12s   (%s -> %s)",
            key, Money.round(expected), actual,
            Money.round(before.getOrDefault(key, BigDecimal.ZERO)),
            Money.round(after.getOrDefault(key, BigDecimal.ZERO)));
    }

    private static Number asNumber(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof Number number) {
            return number;
        }
        return new BigDecimal(value.toString());
    }
}

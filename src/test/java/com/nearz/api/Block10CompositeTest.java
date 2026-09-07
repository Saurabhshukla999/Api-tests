package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-186 .. 200 - the composite journeys.
 *
 * Everything before this block tested ONE thing at a time: a discount, a
 * refund, an expense, a stylist. This block runs several of them together in
 * one journey and asks whether the books still add up at the end.
 *
 * That is a genuinely different question. Every mechanism here has already
 * been proved correct on its own; what is unproved is whether they INTERFERE.
 * The faults this block is built to catch are the ones that only appear in
 * combination:
 *
 *   - a discount applied per-line and again on the cart
 *   - a refund on one bill that reverses another customer's revenue
 *   - two stylists on one bill where the commission is paid twice
 *   - an expense that lands inside a sale's rounding
 *   - a second purchase after a refund that resurrects the refunded amount
 *
 * Each test computes its expected figures from the price list in BigDecimal,
 * the same as every other block - never by reading the API's own total back.
 * The compound baskets here are exactly where that discipline earns its keep:
 * Block 5 found the round-off rule only because a mixed basket stopped landing
 * on a whole rupee by luck.
 *
 * These journeys are long. A failure names the report line, the expectation,
 * the reality and both raw values, so the failing step is identifiable without
 * re-running anything.
 */
public class Block10CompositeTest extends BaseJourneyTest {

    private static final BigDecimal TEN = new BigDecimal("10");
    private static final BigDecimal RENT = new BigDecimal("500.00");

    /** Enquiry -> customer -> booked -> completed. The standard opening. */
    private NewCustomer served(String label) {
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        return customer;
    }

    /** A settled bill for `services` services and `products` products. */
    private int soldTo(NewCustomer customer, int services, int products,
                       BigDecimal discountPct) {
        int billId = billFor(customer, services, products, discountPct);
        Steps.settleInFull(billId, "cash");
        return billId;
    }

    // =====================================================================
    // 186-190  one customer, an increasingly complicated visit
    // =====================================================================
    /**
     * API-E2E-186 the whole path, once, with attendance in it.
     *
     * Enquiry, conversion, booking, the stylist marked present, completion,
     * bill, payment - and all fifteen report figures checked at the end. The
     * canonical journey this suite exists to protect.
     */
    @Test(description = "API-E2E-186 enquiry to payment with attendance, every report checked")
    public void e2e186_theWholePath() {
        LocalDate day = Steps.today();
        int stylist = catalogue.staffIds.get(0);
        Steps.markAttendance(SALON, stylist, day, "present", "10:00", "19:00");

        var before = snapshot();
        NewCustomer customer = served("QA E2E-186");
        int billId = soldTo(customer, 1, 0, ZERO);
        var after = snapshot();
        assertSameDay(day);

        expectSettled(before, after, 1, 0, ZERO);
        assertBillIsPaidBy(billId, customer);
        expectMoved(before, after, "customers.new_customers", ONE);
    }

    /**
     * API-E2E-187 service and product, discounted, taxed, in one basket.
     *
     * The compound basket. Every arithmetic rule the system has applies at
     * once - line prices, a cart discount, GST on the discounted figure, the
     * whole-rupee round-off, and COGS on the product - and they have to
     * compose. This is the shape that exposed the round-off rule and the COGS
     * model, both of which the suite had wrong until a basket like this ran.
     */
    @Test(description = "API-E2E-187 a discounted, taxed basket of a service and a product")
    public void e2e187_compoundBasket() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-187");
        int billId = soldTo(customer, 1, 1, TEN);
        var after = snapshot();
        assertSameDay(day);

        expectSettled(before, after, 1, 1, TEN);
        assertBillIsPaidBy(billId, customer);
    }

    /**
     * API-E2E-188 two stylists on one bill, then the whole thing refunded.
     *
     * Attribution and reversal together. Each stylist must be credited with
     * their own line and neither with the other's, and after the refund both
     * must be back at zero - a reversal that credited the refund to only one
     * of them would leave the salon's staff report permanently wrong while the
     * salon-wide total looked correct.
     */
    @Test(description = "API-E2E-188 two stylists on one bill, then a full refund, both reverse")
    public void e2e188_twoStylistsThenRefund() {
        LocalDate day = Steps.today();
        int first = catalogue.staffIds.get(0);
        int second = catalogue.staffIds.get(1);

        BigDecimal firstBefore = Steps.staffFigure(SALON, first, "service_revenue");
        BigDecimal secondBefore = Steps.staffFigure(SALON, second, "service_revenue");
        var before = snapshot();

        NewCustomer customer = served("QA E2E-188");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                       "unit_price", catalogue.servicePrice, "staff_id", first),
                Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                       "unit_price", catalogue.servicePrice, "staff_id", second)));
        Steps.settleInFull(billId, "cash");

        // Each stylist carries their own line and only their own.
        //
        // The expected figure is the TAX-INCLUSIVE one. The Staff Performance
        // report counts what the customer paid, not the ex-GST price - a 1,000
        // service on an 18% salon shows as 1,180 on the stylist's row. Block 6
        // established this; it is repeated here because the ex-tax figure is the
        // intuitive one to reach for and it is wrong.
        BigDecimal perStylist = gross(1, 0, ZERO);
        assertMovedBy("stylist " + first + " service_revenue", firstBefore,
                Steps.staffFigure(SALON, first, "service_revenue"), perStylist);
        assertMovedBy("stylist " + second + " service_revenue", secondBefore,
                Steps.staffFigure(SALON, second, "service_revenue"), perStylist);

        Steps.refundInFull(billId, "QA E2E-188 refund");
        var after = snapshot();
        assertSameDay(day);

        assertMovedBy("stylist " + first + " after the refund", firstBefore,
                Steps.staffFigure(SALON, first, "service_revenue"), ZERO);
        assertMovedBy("stylist " + second + " after the refund", secondBefore,
                Steps.staffFigure(SALON, second, "service_revenue"), ZERO);

        expectFullyReversed(before, after, gross(2, 0, ZERO));
    }

    /**
     * API-E2E-189 cancelled, rebooked, then completed and paid.
     *
     * A cancellation must leave nothing behind. The customer changed their
     * mind, came back, and the day's takings should read as one visit - not
     * two appointments, and certainly not two bills.
     */
    @Test(description = "API-E2E-189 a cancelled booking, rebooked, completed and paid once")
    public void e2e189_cancelledThenRebooked() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-189");
        int abandoned = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(abandoned, "cancelled");

        int rebooked = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(rebooked, "completed");
        int billId = soldTo(customer, 1, 0, ZERO);

        var after = snapshot();
        assertSameDay(day);

        expectSettled(before, after, 1, 0, ZERO);
        assertEquals(Steps.readBill(billId).getString("data.status"), "paid",
            "the rebooked visit did not settle");
        // assertAppointmentIs, not a raw string compare: the API answers
        // "Cancelled" with a capital C, and it reads the CALENDAR rather than
        // appointments#show, which omits fields (Finding D).
        assertAppointmentIs(abandoned, "cancelled");
    }

    /**
     * API-E2E-190 paid, refunded, then bought again on the same day.
     *
     * The state machine's hardest corner. After a refund the customer buys
     * again; the day must end showing exactly ONE sale, not two, and not zero.
     * A reversal that under- or over-corrects shows up here and nowhere else.
     */
    @Test(description = "API-E2E-190 sale, refund, then a second sale - one visit's revenue remains")
    public void e2e190_refundThenBuyAgain() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-190");
        int firstBill = soldTo(customer, 1, 0, ZERO);
        Steps.refundInFull(firstBill, "QA E2E-190 changed mind");
        int secondBill = soldTo(customer, 1, 0, ZERO);

        var after = snapshot();
        assertSameDay(day);

        BigDecimal charged = gross(1, 0, ZERO);
        expectMoved(before, after, "sales.total_revenue", charged);
        expectMoved(before, after, "payments.total_collected", charged);
        expectMoved(before, after, "profit.gross_revenue",
                    Money.round(charged.subtract(tax(1, 0, ZERO))));
        assertEquals(Steps.readBill(firstBill).getString("data.status"), "refunded",
            "the first bill is not marked refunded");
        assertEquals(Steps.readBill(secondBill).getString("data.status"), "paid",
            "the second bill did not settle");
    }

    // =====================================================================
    // 191-195  several customers at once
    // =====================================================================
    /**
     * API-E2E-191 two customers, two stylists, two bills, one left as a draft.
     *
     * A draft is not a sale. The unsettled bill must contribute nothing to any
     * money figure while sitting in the same salon on the same day as one that
     * did settle.
     */
    @Test(description = "API-E2E-191 two bills, one settled and one left as a draft")
    public void e2e191_oneSettledOneDraft() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer paid = served("QA E2E-191 paid");
        soldTo(paid, 1, 0, ZERO);

        NewCustomer unpaid = served("QA E2E-191 draft");
        int draft = billFor(unpaid, 1, 0, ZERO);       // raised, never settled

        var after = snapshot();
        assertSameDay(day);

        expectSettled(before, after, 1, 0, ZERO);       // exactly one sale
        assertEquals(Steps.readBill(draft).getString("data.status"), "draft",
            "the second bill settled itself");
    }

    /**
     * API-E2E-192 three customers, three stylists, three sales, one refunded.
     *
     * Isolation between concurrent journeys. The refund on customer three must
     * take back exactly its own sale - not a share of the day, and not
     * somebody else's.
     */
    @Test(description = "API-E2E-192 three sales across three stylists, one refunded")
    public void e2e192_threeSalesOneRefund() {
        LocalDate day = Steps.today();
        var before = snapshot();

        List<Integer> bills = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NewCustomer customer = served("QA E2E-192 " + (char) ('a' + i));
            int billId = Steps.createBillLines(customer.id(), List.of(
                    Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                           "unit_price", catalogue.servicePrice,
                           "staff_id", catalogue.staffIds.get(i))));
            Steps.settleInFull(billId, "cash");
            bills.add(billId);
        }
        Steps.refundInFull(bills.get(2), "QA E2E-192 refund");

        var after = snapshot();
        assertSameDay(day);

        // two sales survive
        BigDecimal one = gross(1, 0, ZERO);
        BigDecimal two = Money.round(one.multiply(new BigDecimal("2")));
        expectMoved(before, after, "sales.total_revenue", two);
        expectMoved(before, after, "sales.bills_count", new BigDecimal("2"));
        expectMoved(before, after, "payments.total_collected", two);
        // tax-inclusive, same as the rows
        expectMoved(before, after, "staff_performance.total_service_revenue",
                    Money.round(one.multiply(new BigDecimal("2"))));
    }

    /**
     * API-E2E-193 a discounted mixed basket, refunded, then bought again.
     *
     * 187 and 190 at the same time. The discount and the round-off have to
     * unwind together and then reapply cleanly to the second purchase - the
     * place where a reversal that forgets the discount leaves the day's
     * discount total permanently overstated (Finding C's shape, on a compound
     * basket).
     */
    @Test(description = "API-E2E-193 a discounted mixed basket refunded, then repurchased")
    public void e2e193_discountedBasketRefundedAndRebought() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-193");
        int refunded = soldTo(customer, 1, 1, TEN);
        Steps.refundInFull(refunded, "QA E2E-193 refund");
        soldTo(customer, 1, 1, TEN);

        var after = snapshot();
        assertSameDay(day);

        // one basket's worth survives, arithmetic and all
        expectMoved(before, after, "sales.total_revenue", gross(1, 1, TEN));
        expectMoved(before, after, "sales.bills_count", ONE);
        expectMoved(before, after, "payments.total_collected", gross(1, 1, TEN));
        expectMoved(before, after, "profit.tax_collected", tax(1, 1, TEN));
    }

    /**
     * API-E2E-194 four customers in four different states at once.
     *
     * Paid, paid, cancelled, refunded - the mix a real salon is in at any
     * moment. Two sales must remain, and the dashboard has to agree with the
     * reports about which two.
     */
    @Test(description = "API-E2E-194 four customers in four states, dashboard and reports agree")
    public void e2e194_fourStatesReconcile() {
        LocalDate day = Steps.today();
        var before = snapshot();
        JsonPath dashBefore = Steps.dashboard(SALON, "today");

        soldTo(served("QA E2E-194 a"), 1, 0, ZERO);
        soldTo(served("QA E2E-194 b"), 1, 0, ZERO);

        NewCustomer cancelled = arriveAsEnquiry("QA E2E-194 c");
        Steps.setAppointmentStatus(bookAppointmentFor(cancelled), "cancelled");

        int refundedBill = soldTo(served("QA E2E-194 d"), 1, 0, ZERO);
        Steps.refundInFull(refundedBill, "QA E2E-194 refund");

        var after = snapshot();
        JsonPath dashAfter = Steps.dashboard(SALON, "today");
        assertSameDay(day);

        BigDecimal two = Money.round(gross(1, 0, ZERO).multiply(new BigDecimal("2")));
        expectMoved(before, after, "sales.total_revenue", two);
        expectMoved(before, after, "sales.bills_count", new BigDecimal("2"));

        assertMovedBy("the dashboard revenue KPI", Money.at(dashBefore, "data.kpis.revenue.value"),
                Money.at(dashAfter, "data.kpis.revenue.value"), two);
    }

    /**
     * API-E2E-195 five customers end to end, with attendance marked.
     *
     * Volume. Five complete journeys in one test, and every figure is expected
     * to be exactly five times one journey's. Anything that leaks per-journey -
     * a counter incremented twice, a customer counted on both create and pay -
     * shows up multiplied by five and is impossible to miss.
     */
    @Test(description = "API-E2E-195 five complete journeys, every figure exactly five times one")
    public void e2e195_fiveJourneys() {
        LocalDate day = Steps.today();
        int stylist = catalogue.staffIds.get(0);
        Steps.markAttendance(SALON, stylist, day, "present", "10:00", "19:00");
        var before = snapshot();

        for (int i = 0; i < 5; i++) {
            soldTo(served("QA E2E-195 " + (char) ('a' + i)), 1, 0, ZERO);
        }
        var after = snapshot();
        assertSameDay(day);

        BigDecimal five = new BigDecimal("5");
        expectMoved(before, after, "sales.total_revenue",
                    Money.round(gross(1, 0, ZERO).multiply(five)));
        expectMoved(before, after, "sales.bills_count", five);
        expectMoved(before, after, "payments.total_collected",
                    Money.round(gross(1, 0, ZERO).multiply(five)));
        expectMoved(before, after, "payments.payments_count", five);
        expectMoved(before, after, "customers.new_customers", five);
        expectMoved(before, after, "services.services_sold", five);
        expectMoved(before, after, "staff_performance.total_service_revenue",
                    Money.round(gross(1, 0, ZERO).multiply(five)));
    }

    // =====================================================================
    // 196-200  both sides of the ledger, at volume
    // =====================================================================
    /**
     * API-E2E-196 five mixed baskets, an expense, and a refund - profit
     * reconciled.
     *
     * The first composite that touches the cost side. Revenue, COGS and
     * operating expenses all move in one journey and the P&L has to hold.
     */
    @Test(description = "API-E2E-196 five mixed baskets, an expense and a refund, profit reconciled")
    public void e2e196_volumeWithCosts() {
        LocalDate day = Steps.today();
        var before = Reports.snapshot(SALON, Reports.COST_TRAIL);
        int expenseId = Steps.createExpense("QA E2E-196 rent", RENT, day);
        try {
            List<Integer> bills = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                bills.add(soldTo(served("QA E2E-196 " + (char) ('a' + i)), 1, 1, ZERO));
            }
            Steps.refundInFull(bills.get(4), "QA E2E-196 refund");

            var after = Reports.snapshot(SALON, Reports.COST_TRAIL);
            assertSameDay(day);

            BigDecimal four = new BigDecimal("4");
            BigDecimal revenue = Money.round(
                    gross(1, 1, ZERO).subtract(tax(1, 1, ZERO)).multiply(four));

            expectMoved(before, after, "profit.operating_expenses", RENT);
            expectMoved(before, after, "profit.gross_revenue", revenue);

            // NOTE: service_cogs is deliberately NOT asserted here. D19 leaves
            // the refunded bottle's cost booked, so the expected figure would
            // be four bottles' cost while the API reports five. That defect is
            // pinned on its own in Block7InventoryTest#e2e168; asserting it
            // again here would make one bug fail two tests and tell nobody
            // anything new.
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-197 three stylists on commission, sales split between them, one
     * refunded.
     *
     * Commission is the figure a stylist is paid on, so an error here costs
     * somebody real money. Each stylist's commission must follow their own
     * revenue and reverse with their own refund.
     */
    @Test(description = "API-E2E-197 three stylists on commission, one sale refunded")
    public void e2e197_commissionAcrossStylists() {
        LocalDate day = Steps.today();
        List<Integer> stylists = List.of(catalogue.staffIds.get(0),
                                         catalogue.staffIds.get(1),
                                         catalogue.staffIds.get(2));
        List<BigDecimal> before = new ArrayList<>();
        for (int staffId : stylists) {
            before.add(Steps.staffFigure(SALON, staffId, "service_revenue"));
        }

        List<Integer> bills = new ArrayList<>();
        for (int i = 0; i < stylists.size(); i++) {
            NewCustomer customer = served("QA E2E-197 " + (char) ('a' + i));
            int billId = Steps.createBillLines(customer.id(), List.of(
                    Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                           "unit_price", catalogue.servicePrice,
                           "staff_id", stylists.get(i))));
            Steps.settleInFull(billId, "cash");
            bills.add(billId);
        }
        Steps.refundInFull(bills.get(2), "QA E2E-197 refund");
        assertSameDay(day);

        // the first two keep their sale, the third's is reversed
        BigDecimal each = gross(1, 0, ZERO);          // tax-inclusive
        assertMovedBy("stylist " + stylists.get(0), before.get(0),
                Steps.staffFigure(SALON, stylists.get(0), "service_revenue"), each);
        assertMovedBy("stylist " + stylists.get(1), before.get(1),
                Steps.staffFigure(SALON, stylists.get(1), "service_revenue"), each);
        assertMovedBy("stylist " + stylists.get(2) + " after their sale was refunded",
                before.get(2),
                Steps.staffFigure(SALON, stylists.get(2), "service_revenue"), ZERO);
    }

    /**
     * API-E2E-198 ten transactions in one journey, reconciled against the
     * dashboard.
     *
     * The volume test with a cross-surface check on the end. Ten bills of
     * varying shape, then the dashboard and the Sales report have to agree on
     * what the day came to.
     */
    @Test(description = "API-E2E-198 ten transactions, dashboard and sales report agree")
    public void e2e198_tenTransactions() {
        LocalDate day = Steps.today();
        var before = snapshot();
        JsonPath dashBefore = Steps.dashboard(SALON, "today");

        BigDecimal expected = ZERO;
        for (int i = 0; i < 10; i++) {
            int products = i % 2;                     // alternate the basket
            soldTo(served("QA E2E-198 #" + i), 1, products, ZERO);
            expected = expected.add(gross(1, products, ZERO));
        }
        expected = Money.round(expected);

        var after = snapshot();
        JsonPath dashAfter = Steps.dashboard(SALON, "today");
        assertSameDay(day);

        expectMoved(before, after, "sales.total_revenue", expected);
        expectMoved(before, after, "sales.bills_count", new BigDecimal("10"));
        assertMovedBy("the dashboard revenue KPI across ten sales",
                Money.at(dashBefore, "data.kpis.revenue.value"),
                Money.at(dashAfter, "data.kpis.revenue.value"), expected);
    }

    /**
     * API-E2E-199 the regression case: the two defects this suite found, still
     * measured on every run.
     *
     * Not a journey so much as a standing check that the two most consequential
     * findings have not silently changed shape. Both are ASSERTED AS FOUND, so
     * this test goes red when they are FIXED - which is the signal to come back
     * and turn it into a proper assertion of the fixed behaviour.
     *
     *   D17  a report summary is cached for 60 seconds with no invalidation on
     *        write. This checks the cache-busted read still sees a write the
     *        plain read misses.
     *   D18  a bill raised through the billing module carries no appointment
     *        id, so booked customers count as walk-ins.
     *
     * Kept OUT of the known-defect group on purpose: these assertions pass
     * today, and the day one of them fails is a day somebody wants to know.
     */
    @Test(description = "API-E2E-199 regression: D17 and D18 are still what we recorded")
    public void e2e199_theFindingsStillHold() {
        LocalDate day = Steps.today();

        // --- D18: the bill knows nothing about the appointment ------------
        NewCustomer customer = arriveAsEnquiry("QA E2E-199");
        int appointmentId = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = soldTo(customer, 1, 0, ZERO);
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertNotNull(bill.get("data.id"), "the bill was not readable at all");
        assertTrue(bill.get("data.appointment_id") == null,
            "D18 has changed: the bill now carries appointment_id "
          + bill.get("data.appointment_id") + ". If the billing module has "
          + "learned to link appointments, walk_ins should now be correct - "
          + "re-measure it and rewrite this assertion.");

        // --- D17: the summary cache is still 60 seconds, uninvalidated ----
        //
        // Measured the same way it was originally found, and deterministically:
        // the FIRST read uses a nonce nobody has used before, so it creates the
        // cache entry rather than landing on one of unknown age. The second read
        // reuses that same nonce - same params, same cache_key MD5, same entry -
        // and a third read uses a fresh nonce to see the database.
        //
        //     read(nonce)  -> creates the entry, value V
        //     book an appointment
        //     read(nonce)  -> still V if the cache is uninvalidated
        //     read(fresh)  -> V + 1, the truth
        String nonce = "e2e199-" + System.nanoTime();
        BigDecimal cachedBefore = appointmentsTotal(nonce);

        Steps.setAppointmentStatus(bookAppointmentFor(arriveAsEnquiry("QA E2E-199 cache")),
                                   "completed");

        BigDecimal cachedAfter = appointmentsTotal(nonce);
        BigDecimal truth = appointmentsTotal("fresh-" + System.nanoTime());

        assertTrue(truth.compareTo(cachedBefore) > 0,
            "the appointment this test just booked did not reach the "
          + "Appointments report even on a cache-busted read (" + cachedBefore
          + " -> " + truth + "), so the cache measurement below means nothing");

        assertEquals(cachedAfter.compareTo(cachedBefore), 0,
            "D17 appears to be FIXED: the plain read moved from " + cachedBefore
          + " to " + cachedAfter + " straight after a write, where it used to "
          + "serve a minute-old copy. If report caches are now invalidated on "
          + "write, the _qa cache-buster in Reports.snapshot is no longer "
          + "load-bearing and this assertion should be rewritten to check the "
          + "fixed behaviour.");
    }

    /** total_appointments, read with a caller-chosen cache key. */
    private BigDecimal appointmentsTotal(String nonce) {
        Object value = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("range", "today")
                .queryParam("_qa", nonce)
                .when().get("/salons/{salonId}/reports/appointments/summary")
                .then().statusCode(200)
                .extract().jsonPath().get("data.kpis.total_appointments.value");
        return value == null ? ZERO : new BigDecimal(value.toString());
    }

    /**
     * API-E2E-200 the closing journey. Everything the API does, once, in order.
     *
     * Enquiry, conversion, attendance, booking, completion, a mixed discounted
     * basket, payment, an expense, a refund, a second purchase - then the
     * books, the shelf, the staff report, the dashboard and the P&L all
     * reconciled against figures computed here.
     *
     * If this one test passes, the salon can trade for a day and its books will
     * be right.
     */
    @Test(description = "API-E2E-200 the full day: every mechanism in one journey")
    public void e2e200_theWholeSystem() {
        LocalDate day = Steps.today();
        int stylist = catalogue.staffIds.get(0);
        Steps.markAttendance(SALON, stylist, day, "present", "10:00", "19:00");

        int productId = Steps.createProduct(SALON, "QA E2E-200 " + System.nanoTime() % 1000000,
                                            100, 200, 10, 2);

        var before = snapshot();
        var costsBefore = Reports.snapshot(SALON, Reports.COST_TRAIL);
        JsonPath dashBefore = Steps.dashboard(SALON, "today");
        BigDecimal staffBefore = Steps.staffFigure(SALON, stylist, "service_revenue");

        int expenseId = Steps.createExpense("QA E2E-200 rent", RENT, day);
        try {
            NewCustomer customer = served("QA E2E-200");

            // the visit: a discounted service, refunded when they changed their mind
            int refunded = soldTo(customer, 1, 0, TEN);
            Steps.refundInFull(refunded, "QA E2E-200 changed mind");

            // and what they actually left with: a service and a bottle
            int kept = Steps.createBillLines(customer.id(), List.of(
                    Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                           "unit_price", catalogue.servicePrice, "staff_id", stylist),
                    Map.of("type", "product", "ref_id", productId, "qty", 1,
                           "unit_price", 200, "staff_id", stylist)));
            Steps.settleInFull(kept, "cash");

            var after = snapshot();
            var costsAfter = Reports.snapshot(SALON, Reports.COST_TRAIL);
            JsonPath dashAfter = Steps.dashboard(SALON, "today");
            assertSameDay(day);

            // ---- the money, computed here from the price list --------------
            BigDecimal basket = catalogue.servicePrice.add(new BigDecimal("200"));
            BigDecimal charged = Money.netPayable(basket, catalogue.gstRate,
                                                  catalogue.roundOffEnabled);
            BigDecimal taxOn = Money.tax(basket, catalogue.gstRate);

            expectMoved(before, after, "sales.total_revenue", charged);
            expectMoved(before, after, "sales.bills_count", ONE);
            expectMoved(before, after, "payments.total_collected", charged);
            expectMoved(before, after, "customers.new_customers", ONE);

            // ---- the shelf --------------------------------------------------
            assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("9")), 0,
                "one bottle was sold from a shelf of 10 and the salon now holds "
              + Steps.stockOf(SALON, productId));

            // ---- the stylist -------------------------------------------------
            // The stylist's SERVICE revenue only - the bottle is product
            // revenue and sits in its own column. Tax-inclusive, so the
            // service line alone grosses up at the salon's rate.
            assertMovedBy("stylist " + stylist + " service revenue", staffBefore,
                    Steps.staffFigure(SALON, stylist, "service_revenue"),
                    gross(1, 0, ZERO));

            // ---- the cost side ------------------------------------------------
            expectMoved(costsBefore, costsAfter, "profit.operating_expenses", RENT);
            expectMoved(costsBefore, costsAfter, "profit.gross_revenue",
                        Money.round(charged.subtract(taxOn)));

            // ---- the dashboard, against the reports ----------------------------
            assertMovedBy("the dashboard revenue KPI",
                    Money.at(dashBefore, "data.kpis.revenue.value"),
                    Money.at(dashAfter, "data.kpis.revenue.value"), charged);
            assertTrue(Money.closeEnough(Money.at(dashAfter, "data.kpis.revenue.value"),
                                         after.get("sales.total_revenue")),
                "at the end of the day the dashboard says the salon took "
              + Money.at(dashAfter, "data.kpis.revenue.value") + " and the Sales "
              + "report says " + after.get("sales.total_revenue"));
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================
    private void assertMovedBy(String what, BigDecimal before, BigDecimal after,
                               BigDecimal expected) {
        BigDecimal actual = Money.round(after.subtract(before));
        assertTrue(Money.closeEnough(actual, expected),
            String.format("%n  %s%n      expected to move %12s%n"
                        + "      actually moved   %12s   (%s -> %s)",
                what, Money.round(expected), actual,
                Money.round(before), Money.round(after)));
    }
}

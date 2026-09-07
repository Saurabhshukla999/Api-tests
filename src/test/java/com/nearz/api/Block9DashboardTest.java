package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-181 .. 185 - the dashboard against the reports.
 *
 * Every other block asks whether ONE surface is right. This block asks whether
 * TWO surfaces agree, which is a different question and a more valuable one.
 *
 * The dashboard is not a view of the reports. `Dashboard::OverviewQuery` and
 * `Reports::SalesQuery` are separate objects running separate SQL over the same
 * tables. They can drift apart without either one being wrong on its own terms
 * - and when they do, the owner sees one revenue figure on the screen they open
 * every morning and a different one in the report they send their accountant.
 * Nothing in the system would flag that. These five tests would.
 *
 * The basis is stated by the API itself, in `data.revenue_basis`:
 *
 *     gross_incl_tax_net_of_refunds
 *
 * tax included, refunds already deducted - the same basis as
 * `sales.total_revenue`, which is why these are asserted EQUAL rather than
 * merely moving together.
 *
 * Measured on salon 4550, 7 Sep 2026, mid-run with 65 bills on the day:
 *
 *     revenue        dashboard 56,592.00   sales.total_revenue   56,592.00
 *     services       sources   44,721.33   sales.service_revenue 44,721.33
 *     products       sources   11,870.67   sales.product_revenue 11,870.67
 *     collected      payments  56,592.00   payments.total_collected 56,592.00
 *     appointments   79 / 77 completed     report 79 / 77 completed
 *
 * All five agreed to the paisa. This block is what keeps them agreeing.
 */
public class Block9DashboardTest extends BaseJourneyTest {

    private JsonPath overview() {
        return Steps.dashboard(SALON, "today");
    }

    private BigDecimal revenueKpi(JsonPath overview) {
        return Money.at(overview, "data.kpis.revenue.value");
    }

    /** Both surfaces at one instant, so a comparison is not racing a write. */
    private record Both(JsonPath dash, Map<String, BigDecimal> reports) { }

    private Both readBoth() {
        return new Both(overview(), snapshot());
    }

    // =====================================================================
    // 181-182  a sale reaches the dashboard, and reaches the right half of it
    // =====================================================================
    /**
     * API-E2E-181 a service sale moves the dashboard's revenue KPI by the same
     * amount it moved the Sales report - and lands under Services.
     *
     * The revenue_sources split is the part worth having. A sale that reached
     * the total but was filed under Products would leave the headline figure
     * correct and the breakdown wrong, which is exactly the kind of fault that
     * survives a smoke test.
     */
    @Test(description = "API-E2E-181 a service sale moves the dashboard and the sales report by the same amount")
    public void e2e181_serviceSaleReachesTheDashboard() {
        LocalDate day = Steps.today();
        Both before = readBoth();

        NewCustomer customer = arriveAsEnquiry("QA E2E-181");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        Both after = readBoth();
        assertSameDay(day);

        BigDecimal charged = gross(1, 0, ZERO);

        expectMoved(before.reports(), after.reports(), "sales.total_revenue", charged);
        assertMoved("the dashboard revenue KPI",
                revenueKpi(before.dash()), revenueKpi(after.dash()), charged);
        assertMoved("revenue_sources[services]",
                Steps.revenueSource(before.dash(), "services"),
                Steps.revenueSource(after.dash(), "services"), charged);
        assertMoved("revenue_sources[products] on a service-only sale",
                Steps.revenueSource(before.dash(), "products"),
                Steps.revenueSource(after.dash(), "products"), ZERO);

        assertSurfacesAgree(after);
    }

    /**
     * API-E2E-182 a product sale reaches the Products half of the dashboard,
     * and the shelf change reaches the inventory tab.
     *
     * Two surfaces, two different things to get wrong. The inventory tab is the
     * one that tells the owner to reorder, so a stock movement that never
     * reaches it is a bottle that runs out with no warning.
     */
    @Test(description = "API-E2E-182 a product sale reaches both the revenue split and the inventory tab")
    public void e2e182_productSaleReachesInventory() {
        LocalDate day = Steps.today();
        // reorder level 9 of an opening 10: one sale takes it under.
        int productId = Steps.createProduct(SALON, "QA E2E-182 " + System.nanoTime() % 1000000,
                                            100, 200, 10, 9);

        Both before = readBoth();
        JsonPath stockBefore = Steps.dashboardTab(SALON, "inventory", "today");
        assertNull(Steps.lowStockSeverity(stockBefore, productId),
            "the product is already on the low-stock list before anything was "
          + "sold - it opened with 10 against a reorder level of 9");

        NewCustomer customer = arriveAsEnquiry("QA E2E-182");
        completeAppointmentFor(customer);
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "product", "ref_id", productId, "qty", 1,
                       "unit_price", 200, "staff_id", catalogue.staffIds.get(0))));
        Steps.settleInFull(billId, "cash");

        Both after = readBoth();
        JsonPath stockAfter = Steps.dashboardTab(SALON, "inventory", "today");
        assertSameDay(day);

        BigDecimal charged = Money.netPayable(new BigDecimal("200"), catalogue.gstRate,
                                              catalogue.roundOffEnabled);

        assertMoved("revenue_sources[products]",
                Steps.revenueSource(before.dash(), "products"),
                Steps.revenueSource(after.dash(), "products"), charged);
        assertMoved("revenue_sources[services] on a product-only sale",
                Steps.revenueSource(before.dash(), "services"),
                Steps.revenueSource(after.dash(), "services"), ZERO);

        assertMoved("the inventory tab's units-sold counter",
                Money.at(stockBefore, "data.summary.sold.value"),
                Money.at(stockAfter, "data.summary.sold.value"), ONE);

        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("9")), 0,
            "the sale did not leave the shelf");
        assertNotNull(Steps.lowStockSeverity(stockAfter, productId),
            "stock fell to 9 against a reorder level of 9 and the product is "
          + "still not on the dashboard's low-stock list - the salon gets no "
          + "warning to reorder");
    }

    // =====================================================================
    // 183-184  the split adds up, and a refund unwinds everywhere at once
    // =====================================================================
    /**
     * API-E2E-183 the revenue breakdown adds up to the revenue headline, and
     * each half matches the Sales report's own split.
     *
     * Three figures that are computed separately and must reconcile:
     * services + products (+ memberships + combo packs) has to equal the
     * dashboard's own revenue KPI, and each half has to equal the equivalent
     * line on the Sales report.
     */
    @Test(description = "API-E2E-183 the dashboard's revenue split adds up and matches the sales report")
    public void e2e183_theSplitAddsUp() {
        LocalDate day = Steps.today();

        NewCustomer customer = arriveAsEnquiry("QA E2E-183");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        Both now = readBoth();
        assertSameDay(day);

        BigDecimal services = Steps.revenueSource(now.dash(), "services");
        BigDecimal products = Steps.revenueSource(now.dash(), "products");
        BigDecimal memberships = Steps.revenueSource(now.dash(), "memberships");
        BigDecimal combos = Steps.revenueSource(now.dash(), "combo_packs");
        BigDecimal sum = Money.round(services.add(products).add(memberships).add(combos));
        BigDecimal headline = revenueKpi(now.dash());

        assertTrue(Money.closeEnough(sum, headline),
            "the dashboard's revenue sources add up to " + sum + " but its own "
          + "revenue KPI says " + headline + " - the pie chart and the number "
          + "above it disagree");

        assertTrue(Money.closeEnough(services, now.reports().get("sales.service_revenue")),
            "dashboard services " + services + " vs sales.service_revenue "
          + now.reports().get("sales.service_revenue"));
        assertTrue(Money.closeEnough(products, now.reports().get("sales.product_revenue")),
            "dashboard products " + products + " vs sales.product_revenue "
          + now.reports().get("sales.product_revenue"));
    }

    /**
     * API-E2E-184 a refund unwinds on the dashboard in step with every report.
     *
     * The dashboard says its basis is `net_of_refunds`, so it MUST come back
     * down. This is the test that would catch a dashboard that shows the day's
     * takings without deducting what was handed back - the version of this bug
     * that makes a salon think it had a better day than it did.
     */
    @Test(description = "API-E2E-184 a refund unwinds on the dashboard and the reports together")
    public void e2e184_refundUnwindsEverywhere() {
        LocalDate day = Steps.today();
        Both before = readBoth();

        NewCustomer customer = arriveAsEnquiry("QA E2E-184");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        Both paid = readBoth();
        BigDecimal charged = gross(1, 0, ZERO);
        assertMoved("the dashboard revenue KPI on payment",
                revenueKpi(before.dash()), revenueKpi(paid.dash()), charged);

        Steps.refundInFull(billId, "QA E2E-184 refund");
        Both after = readBoth();
        assertSameDay(day);

        assertMoved("the dashboard revenue KPI after the refund",
                revenueKpi(before.dash()), revenueKpi(after.dash()), ZERO);
        assertMoved("revenue_sources[services] after the refund",
                Steps.revenueSource(before.dash(), "services"),
                Steps.revenueSource(after.dash(), "services"), ZERO);

        expectMoved(before.reports(), after.reports(), "sales.total_revenue", ZERO);
        expectMoved(before.reports(), after.reports(), "payments.total_collected", ZERO);
        expectMoved(before.reports(), after.reports(), "profit.gross_revenue", ZERO);

        assertSurfacesAgree(after);
    }

    // =====================================================================
    // 185  the standing reconciliation
    // =====================================================================
    /**
     * API-E2E-185 after a real sale, every figure the dashboard and the reports
     * share is equal.
     *
     * Not deltas - absolute figures, on a salon carrying a full day of
     * trading. Four independent pairs of queries forced to agree:
     *
     *     dashboard revenue          == sales.total_revenue
     *     revenue_sources[services]  == sales.service_revenue
     *     revenue_sources[products]  == sales.product_revenue
     *     payment_summary summed      == payments.total_collected
     *     appointments completed     == appointments.completed
     *
     * If a future change moves one query's window, its rounding or its refund
     * handling and not the other's, this is the test that says so - and it says
     * WHICH pair stopped agreeing.
     */
    @Test(description = "API-E2E-185 dashboard and reports reconcile on absolute figures")
    public void e2e185_fullReconciliation() {
        LocalDate day = Steps.today();

        NewCustomer customer = arriveAsEnquiry("QA E2E-185");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        Both now = readBoth();
        assertSameDay(day);

        assertEquals(now.dash().getString("data.revenue_basis"),
                     "gross_incl_tax_net_of_refunds",
            "the dashboard has changed the basis it reports revenue on, so it "
          + "can no longer be compared to sales.total_revenue directly - the "
          + "assertions below need rewriting before they mean anything");

        assertSurfacesAgree(now);

        // appointments: a count, not money, and its own query again
        BigDecimal dashCompleted = Money.at(now.dash(), "data.kpis.appointments.completed");
        Map<String, BigDecimal> appointments =
                Reports.snapshot(SALON, List.of(Reports.APPOINTMENTS));
        assertTrue(Money.closeEnough(dashCompleted, appointments.get("appointments.completed")),
            "the dashboard counts " + dashCompleted + " completed appointments "
          + "and the Appointments report counts "
          + appointments.get("appointments.completed"));
    }

    // =====================================================================
    // helpers
    // =====================================================================
    /** The four money figures both surfaces carry, compared absolutely. */
    private void assertSurfacesAgree(Both now) {
        agree("total revenue",
              revenueKpi(now.dash()), "dashboard revenue",
              now.reports().get("sales.total_revenue"), "sales.total_revenue");

        agree("service revenue",
              Steps.revenueSource(now.dash(), "services"), "revenue_sources[services]",
              now.reports().get("sales.service_revenue"), "sales.service_revenue");

        agree("product revenue",
              Steps.revenueSource(now.dash(), "products"), "revenue_sources[products]",
              now.reports().get("sales.product_revenue"), "sales.product_revenue");

        // payment_summary is a LIST of modes (and a bare object on a
        // single-mode day). Totalling it also proves the mode split adds up to
        // what was actually collected.
        agree("cash collected",
              Steps.paymentSummaryTotal(now.dash()), "payment_summary (all modes)",
              now.reports().get("payments.total_collected"), "payments.total_collected");
    }

    private void agree(String what, BigDecimal left, String leftName,
                       BigDecimal right, String rightName) {
        assertTrue(Money.closeEnough(left, right),
            String.format("%n  the two surfaces disagree about %s%n"
                        + "      %-28s %12s%n"
                        + "      %-28s %12s%n"
                        + "      difference                   %12s",
                what, leftName, left, rightName, right,
                Money.round(left.subtract(right))));
    }

    /** Same failure shape as expectMoved, for figures that are not in a snapshot. */
    private void assertMoved(String what, BigDecimal before, BigDecimal after,
                             BigDecimal expected) {
        BigDecimal actual = Money.round(after.subtract(before));
        assertTrue(Money.closeEnough(actual, expected),
            String.format("%n  %s%n      expected to move %12s%n"
                        + "      actually moved   %12s   (%s -> %s)",
                what, Money.round(expected), actual,
                Money.round(before), Money.round(after)));
    }
}

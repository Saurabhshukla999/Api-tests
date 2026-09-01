package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-101 .. 125 - refunds and reversal.
 *
 * The assertions here are deliberately two-sided. A refund must take the money
 * back out of every report it put money into, AND it must appear as a refund
 * rather than simply vanishing. A reversal that quietly deleted the sale from
 * both sides would satisfy a naive "revenue went back to zero" check, and that
 * is a worse bug than the one such a check is looking for.
 *
 * Partial REFUNDS are supported by the API and used here. Only partial
 * PAYMENTS are switched off in the frontend.
 */
public class Block3RefundTest extends BaseJourneyTest {

    private static final String PARTIAL =
        "needs partial payments, which are switched off in the frontend.";
    private static final BigDecimal TEN_PERCENT = new BigDecimal("10");

    // =======================================================================
    // 101-108: the lead's list asks eight questions of one reversal
    // =======================================================================
    @Test(description = "API-E2E-101 a full refund reverses the Sales report")
    public void e2e101_sales() {
        Reversed r = refundOnce("QA E2E-101");
        expectMoved(r.before(), r.after(), "sales.total_revenue", ZERO);
        expectMoved(r.before(), r.after(), "sales.service_revenue", ZERO);
    }

    @Test(description = "API-E2E-102 a full refund reverses Payments")
    public void e2e102_payments() {
        Reversed r = refundOnce("QA E2E-102");
        expectMoved(r.before(), r.after(), "payments.total_collected", ZERO);
        expectMoved(r.before(), r.after(), "payments.failed_refunded_amount", r.collected());
    }

    @Test(description = "API-E2E-103 a full refund reverses Customer spend")
    public void e2e103_customerSpend() {
        Reversed r = refundOnce("QA E2E-103");
        expectMoved(r.before(), r.after(), "customers.total_spend_in_range", ZERO);
    }

    @Test(description = "API-E2E-104 a full refund reverses Services")
    public void e2e104_services() {
        Reversed r = refundOnce("QA E2E-104");
        expectMoved(r.before(), r.after(), "services.total_revenue", ZERO);
    }

    @Test(description = "API-E2E-105 a full refund reverses Staff revenue")
    public void e2e105_staff() {
        Reversed r = refundOnce("QA E2E-105");
        expectMoved(r.before(), r.after(), "staff_performance.total_service_revenue", ZERO);
    }

    @Test(description = "API-E2E-106 a full refund reverses Profit")
    public void e2e106_profit() {
        Reversed r = refundOnce("QA E2E-106");
        expectMoved(r.before(), r.after(), "profit.gross_revenue", ZERO);
        expectMoved(r.before(), r.after(), "profit.net_profit", ZERO);
        expectMoved(r.before(), r.after(), "profit.tax_collected", ZERO);
    }

    @Test(description = "API-E2E-107 a full refund reverses the Dashboard too")
    public void e2e107_dashboard() {
        Reversed r = refundOnce("QA E2E-107");
        expectMoved(r.before(), r.after(), "sales.total_revenue", ZERO);
        assertDashboardAgreesWithSales();
    }

    @Test(description = "API-E2E-108 a full refund leaves a refund on the record")
    public void e2e108_leavesARecord() {
        Reversed r = refundOnce("QA E2E-108");
        expectMoved(r.before(), r.after(), "sales.total_revenue", ZERO);
        assertBillIsRefunded(r.billId());
    }

    // =======================================================================
    // 109-113: need partial payments
    // =======================================================================
    @Test(description = "API-E2E-109 refund only the portion collected")
    public void e2e109() { throw new SkipException(PARTIAL); }

    @Test(description = "API-E2E-110 advance, balance, then refund it all")
    public void e2e110() { throw new SkipException(PARTIAL); }

    @Test(description = "API-E2E-111 two payments, first refunded")
    public void e2e111() { throw new SkipException(PARTIAL); }

    @Test(description = "API-E2E-112 two payments, second refunded")
    public void e2e112() { throw new SkipException(PARTIAL); }

    @Test(description = "API-E2E-113 two payments, both refunded")
    public void e2e113() { throw new SkipException(PARTIAL); }

    // =======================================================================
    // 114-116: a bill with both a service and a product
    // =======================================================================
    @Test(description = "API-E2E-114 service and product billed, the service refunded")
    public void e2e114_refundServiceOnly() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-114");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        BigDecimal whole = gross(1, 1, ZERO);
        BigDecimal serviceShare = Money.gross(catalogue.servicePrice, catalogue.gstRate);
        Steps.refundPart(billId, serviceShare, "service not delivered");

        var after = snapshot();
        assertSameDay(day);

        // A part refund is a price adjustment, not an unwind: the sale stands.
        expectMoved(before, after, "sales.total_revenue",
                    Money.round(whole.subtract(serviceShare)));
        expectMoved(before, after, "payments.total_collected",
                    Money.round(whole.subtract(serviceShare)));
        expectMoved(before, after, "payments.failed_refunded_amount", serviceShare);
        assertPartiallyRefunded(billId);
    }

    @Test(description = "API-E2E-115 service and product billed, the product refunded")
    public void e2e115_refundProductOnly() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-115");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        BigDecimal whole = gross(1, 1, ZERO);
        BigDecimal productShare = Money.gross(catalogue.productPrice, catalogue.gstRate);
        Steps.refundPart(billId, productShare, "product returned");

        var after = snapshot();
        assertSameDay(day);
        expectMoved(before, after, "sales.total_revenue",
                    Money.round(whole.subtract(productShare)));
        expectMoved(before, after, "payments.total_collected",
                    Money.round(whole.subtract(productShare)));
        expectMoved(before, after, "payments.failed_refunded_amount", productShare);
        assertPartiallyRefunded(billId);
    }

    @Test(description = "API-E2E-116 service and product billed, the whole bill refunded")
    public void e2e116_refundWholeMixedBill() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-116");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");
        BigDecimal whole = gross(1, 1, ZERO);
        Steps.refundInFull(billId, "QA journey refund");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);
        expectMoved(before, after, "sales.total_revenue", ZERO);
        expectMoved(before, after, "payments.total_collected", ZERO);
        expectMoved(before, after, "customers.total_spend_in_range", ZERO);
        expectMoved(before, after, "payments.failed_refunded_amount", whole);
    }

    // =======================================================================
    // 117-119: discount and GST variations
    // =======================================================================
    @Test(description = "API-E2E-117 a discounted bill, refunded")
    public void e2e117_discountedRefund() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-117");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, TEN_PERCENT);
        Steps.settleInFull(billId, "cash");
        BigDecimal collected = gross(1, 0, TEN_PERCENT);
        Steps.refundInFull(billId, "QA journey refund");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);
        expectFullyReversed(before, after, collected);

        // FINDING C: a fully refunded bill reverses its revenue to zero but
        // LEAVES its discount on the books, so Profit reports 0 revenue
        // alongside a discount given. Asserted as observed so the number is
        // locked while the backend team decides whether that is intended.
        BigDecimal discount = Money.discount(subtotal(1, 0), TEN_PERCENT, ZERO);
        expectMoved(before, after, "sales.total_discount", discount);
        expectMoved(before, after, "profit.total_discount", discount);
    }

    @Test(description = "API-E2E-118 a GST bill, refunded")
    public void e2e118_gstBillRefunded() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-118");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO, Boolean.TRUE);
        Steps.settleInFull(billId, "cash");
        BigDecimal collected = gross(1, 0, ZERO);
        Steps.refundInFull(billId, "QA journey refund");
        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);
        expectFullyReversed(before, after, collected);
    }

    @Test(description = "API-E2E-119 a GST-disabled bill, refunded")
    public void e2e119_gstDisabledRefunded() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-119");
        completeAppointmentFor(customer);

        // Tax off for THIS bill only - no salon setting is touched.
        int billId = billFor(customer, 1, 0, ZERO, Boolean.FALSE);
        BigDecimal untaxed = taxable(1, 0, ZERO);
        assertTrue(Money.closeEnough(Steps.netPayable(billId), untaxed),
            Money.difference("a GST-disabled bill should charge no tax",
                             untaxed, Steps.netPayable(billId)));

        Steps.settleInFull(billId, "cash");
        Steps.refundInFull(billId, "QA journey refund");
        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);

        // The refund must return exactly what was collected - the untaxed
        // amount, not the salon's usual taxed one.
        expectFullyReversed(before, after, untaxed);
    }

    // =======================================================================
    // 120-125
    // =======================================================================
    @Test(description = "API-E2E-120 refunded, then the same customer buys again")
    public void e2e120_refundThenBuyAgain() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-120");

        completeAppointmentFor(customer);
        int refunded = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(refunded, "cash");
        BigDecimal reversed = gross(1, 0, ZERO);
        Steps.refundInFull(refunded, "QA journey refund");

        completeAppointmentFor(customer);
        int kept = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(kept, "cash");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsPaidBy(kept, customer);

        BigDecimal survives = gross(1, 0, ZERO);
        expectMoved(before, after, "sales.bills_count", ONE);
        expectMoved(before, after, "sales.total_revenue", survives);
        expectMoved(before, after, "payments.total_collected", survives);
        expectMoved(before, after, "customers.total_spend_in_range", survives);
        expectMoved(before, after, "payments.failed_refunded_amount", reversed);
    }

    @Test(description = "API-E2E-121 a second bill and a second refund")
    public void e2e121_twoBillsTwoRefunds() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-121");

        completeAppointmentFor(customer);
        int first = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(first, "cash");
        Steps.refundInFull(first, "QA journey refund");

        completeAppointmentFor(customer);
        int second = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(second, "cash");
        Steps.refundInFull(second, "QA journey refund");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(second);

        BigDecimal both = Money.round(gross(1, 0, ZERO).multiply(new BigDecimal("2")));
        expectMoved(before, after, "sales.total_revenue", ZERO);
        expectMoved(before, after, "payments.total_collected", ZERO);
        expectMoved(before, after, "customers.total_spend_in_range", ZERO);
        expectMoved(before, after, "payments.failed_refunded_amount", both);
    }

    @Test(description = "API-E2E-122 the refund call, sent twice, must not reverse twice")
    public void e2e122_refundRetried() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-122");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        BigDecimal collected = gross(1, 0, ZERO);

        Steps.refundInFull(billId, "QA journey refund");
        int retry = Steps.attemptRefund(billId, null, "retry");
        assertTrue(retry >= 400,
            "the refund call was sent twice and the API answered " + retry
          + " the second time - a fully refunded bill must not be refundable again");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);
        // Refunded once, not twice.
        expectFullyReversed(before, after, collected);
    }

    @Test(description = "API-E2E-123 a refund larger than the bill is refused")
    public void e2e123_overRefundRefused() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-123");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        int status = Steps.attemptRefund(billId, new BigDecimal("999999"), "over-refund");
        assertTrue(status >= 400,
            "the API ACCEPTED a refund of 999999 on a bill worth "
          + Money.round(gross(1, 0, ZERO)) + " (answered " + status + ")");

        var after = snapshot();
        assertSameDay(day);

        // The refusal must leave everything exactly as a normal settlement.
        expectSettled(before, after, 1, 0, ZERO);
        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "paid",
            "an invalid refund was refused but bill " + billId + " is no longer 'paid'");
        assertEquals(Money.at(bill, "data.refund_amount").compareTo(ZERO), 0,
            "an invalid refund was refused but bill " + billId
          + " now carries a refund_amount");
    }

    @Test(description = "API-E2E-124 before and after, across every report the refund touches")
    public void e2e124_everyReportChecked() {
        Reversed r = refundOnce("QA E2E-124");
        expectFullyReversed(r.before(), r.after(), r.collected());
        assertBillIsRefunded(r.billId());
        assertDashboardAgreesWithSales();
    }

    @Test(description = "API-E2E-125 settle, refund, sell again, reconcile")
    public void e2e125_settleRefundSellAgain() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-125");

        completeAppointmentFor(customer);
        int refunded = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(refunded, "cash");
        BigDecimal reversed = gross(1, 0, ZERO);
        Steps.refundInFull(refunded, "QA journey refund");

        completeAppointmentFor(customer);
        int kept = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(kept, "cash");

        var after = snapshot();
        assertSameDay(day);

        BigDecimal survives = gross(1, 0, ZERO);
        expectMoved(before, after, "sales.bills_count", ONE);
        expectMoved(before, after, "sales.total_revenue", survives);
        expectMoved(before, after, "payments.total_collected", survives);
        expectMoved(before, after, "payments.payments_count", ONE);
        expectMoved(before, after, "customers.new_customers", ONE);
        expectMoved(before, after, "customers.total_spend_in_range", survives);
        expectMoved(before, after, "profit.gross_revenue", taxable(1, 0, ZERO));
        expectMoved(before, after, "payments.failed_refunded_amount", reversed);
        assertDashboardAgreesWithSales();
    }

    // -----------------------------------------------------------------------
    private record Reversed(Map<String, BigDecimal> before,
                            Map<String, BigDecimal> after,
                            int billId, BigDecimal collected) { }

    private Reversed refundOnce(String label) {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        BigDecimal collected = gross(1, 0, ZERO);
        Steps.refundInFull(billId, "QA journey refund");
        var after = snapshot();
        assertSameDay(day);
        return new Reversed(before, after, billId, collected);
    }

    private void assertPartiallyRefunded(int billId) {
        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "partially_refunded",
            "a part refund on bill " + billId + " left status '"
          + bill.getString("data.status") + "'; RefundProcessor sets "
          + "'partially_refunded' because a part refund is a price adjustment, "
          + "not an unwind of the sale");
    }

    private void assertDashboardAgreesWithSales() {
        BigDecimal card = Money.of(io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("range", "today").queryParam("refresh", "true")
                .when().get("/salons/{salonId}/dashboard/overview")
                .then().statusCode(200)
                .extract().jsonPath().getDouble("data.kpis.revenue.value"));

        BigDecimal report = Money.of(io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today")
                .when().get("/salons/{salonId}/reports/sales/summary")
                .then().statusCode(200)
                .extract().jsonPath().getDouble("data.kpis.total_revenue.value"));

        assertTrue(Money.closeEnough(card, report),
            "the Dashboard revenue card says " + Money.round(card)
          + " and the Sales report says " + Money.round(report)
          + " - the reversal reached one and not the other");
    }
}

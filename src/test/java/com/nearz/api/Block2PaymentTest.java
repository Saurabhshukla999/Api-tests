package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-079 .. 100 - payments and settlement.
 *
 * Partial payments (two or more payments against one bill) are out of future
 * scope. Their parked cases - 076-078, 086-088, 090 and 092-096 - were removed
 * on 22 Oct 2026 rather than left as permanent skips. The settlement journeys
 * that use one full payment remain.
 */
public class Block2PaymentTest extends BaseJourneyTest {

    // =======================================================================
    // 079-085: the lead's list asks seven questions of one settlement
    // =======================================================================
    @Test(description = "API-E2E-079 settlement reaches the Sales report")
    public void e2e079_sales() {
        Settled s = settleOnce("QA E2E-079");
        expectMoved(s.before(), s.after(), "sales.total_revenue", gross(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "sales.bills_count", ONE);
    }

    @Test(description = "API-E2E-080 settlement reaches Payments collected")
    public void e2e080_paymentsCollected() {
        Settled s = settleOnce("QA E2E-080");
        expectMoved(s.before(), s.after(), "payments.total_collected", gross(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "payments.payments_count", ONE);
    }

    @Test(description = "API-E2E-081 settlement reaches Customer spend")
    public void e2e081_customerSpend() {
        Settled s = settleOnce("QA E2E-081");
        expectMoved(s.before(), s.after(), "customers.total_spend_in_range",
                    gross(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "customers.new_customers", ONE);
    }

    @Test(description = "API-E2E-082 settlement reaches Service revenue")
    public void e2e082_serviceRevenue() {
        Settled s = settleOnce("QA E2E-082");
        expectMoved(s.before(), s.after(), "services.total_revenue", gross(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "services.services_sold", ONE);
    }

    @Test(description = "API-E2E-083 settlement reaches Staff revenue")
    public void e2e083_staffRevenue() {
        Settled s = settleOnce("QA E2E-083");
        expectMoved(s.before(), s.after(), "staff_performance.total_service_revenue",
                    gross(1, 0, ZERO));
    }

    @Test(description = "API-E2E-084 settlement reaches Profit, on the ex-tax base")
    public void e2e084_profit() {
        Settled s = settleOnce("QA E2E-084");
        // Sales counts the tax-inclusive figure, Profit the tax-exclusive one.
        // Both correct, easy to confuse, so both are asserted.
        expectMoved(s.before(), s.after(), "profit.gross_revenue", taxable(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "profit.net_profit", taxable(1, 0, ZERO));
        expectMoved(s.before(), s.after(), "profit.tax_collected", tax(1, 0, ZERO));
    }

    @Test(description = "API-E2E-085 one payment settles one bill, counted once")
    public void e2e085_countedOnce() {
        Settled s = settleOnce("QA E2E-085");
        expectMoved(s.before(), s.after(), "sales.bills_count", ONE);
        expectMoved(s.before(), s.after(), "payments.payments_count", ONE);
    }

    // =======================================================================
    // 086-096: settlement and refund cases that do not need part-payment
    // =======================================================================
    @Test(description = "API-E2E-089 settle, then refund what was collected")
    public void e2e089_refundWhatWasCollected() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-089");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        Steps.refundInFull(billId, "QA journey refund");
        var after = snapshot();
        assertSameDay(day);
        assertBillIsRefunded(billId);
        expectFullyReversed(before, after, gross(1, 0, ZERO));
    }

    @Test(description = "API-E2E-091 the refund is visible in every report it touched")
    public void e2e091_refundVisibleEverywhere() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-091");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        Steps.refundInFull(billId, "QA journey refund");
        var after = snapshot();
        assertSameDay(day);
        expectFullyReversed(before, after, gross(1, 0, ZERO));
    }

    // =======================================================================
    // 097-100
    // =======================================================================
    @Test(description = "API-E2E-097 a retried payment must not collect twice")
    public void e2e097_retryDoesNotDoubleCollect() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-097");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);
        Steps.settleInFull(billId, "cash");

        // Send it again. Refusing is a correct answer; collecting twice is not.
        Steps.repeatPayment(billId, due, "cash");

        var after = snapshot();
        assertSameDay(day);
        expectMoved(before, after, "payments.total_collected", gross(1, 0, ZERO));
        expectMoved(before, after, "payments.payments_count", ONE);
        assertNoSecondCollection(billId, due);
    }

    @Test(description = "API-E2E-098 settlement retried twice after a timeout")
    public void e2e098_retryTwice() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry("QA E2E-098");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);
        Steps.settleInFull(billId, "cash");

        Steps.repeatPayment(billId, due, "cash");
        Steps.repeatPayment(billId, due, "cash");

        var after = snapshot();
        assertSameDay(day);
        expectMoved(before, after, "payments.total_collected", gross(1, 0, ZERO));
        expectMoved(before, after, "payments.payments_count", ONE);
        assertNoSecondCollection(billId, due);
    }

    @Test(description = "API-E2E-099 the payment ledger matches the bill it came from")
    public void e2e099_ledgerMatchesBill() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-099");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        JsonPath bill = Steps.readBill(billId);
        String billNumber = bill.getString("data.bill_number");
        BigDecimal paid = Money.at(bill, "data.amount_paid");

        JsonPath rows = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("range", "today")
                .queryParam("page", 1).queryParam("per_page", 200)
                .when().get("/salons/{salonId}/reports/payments/rows")
                .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> all = rows.getList("data.rows");
        assertTrue(all != null && !all.isEmpty(),
            "bill " + billId + " was settled but today's Payments report has no rows");

        BigDecimal reported = ZERO;
        boolean found = false;
        for (Map<String, Object> row : all) {
            String ref = String.valueOf(row.get("bill_id")) + "|"
                       + String.valueOf(row.get("bill_number"));
            if (ref.contains(String.valueOf(billId)) || ref.contains(String.valueOf(billNumber))) {
                found = true;
                Object amount = row.get("amount") != null ? row.get("amount")
                                                          : row.get("amount_paid");
                reported = reported.add(Money.of((Number) amount));
            }
        }
        assertTrue(found,
            "bill " + billId + " (" + billNumber + ") was settled for " + paid
          + " but has no row in today's Payments report");
        assertTrue(Money.closeEnough(reported, paid),
            Money.difference("the Payments report row for bill " + billId, paid, reported));
    }

    @Test(description = "API-E2E-100 the Dashboard agrees with the Sales report")
    public void e2e100_dashboardAgreesWithSales() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-100");
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

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
          + " for today and the Sales report says " + Money.round(report)
          + " - the owner is shown two different numbers for one day");
    }

    // -----------------------------------------------------------------------
    private record Settled(Map<String, BigDecimal> before, Map<String, BigDecimal> after) { }

    private Settled settleOnce(String label) {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);
        assertBillIsPaidBy(billId, customer);
        return new Settled(before, after);
    }

    private void assertNoSecondCollection(int billId, BigDecimal due) {
        JsonPath bill = Steps.readBill(billId);
        BigDecimal paid = Money.at(bill, "data.amount_paid");
        assertTrue(Money.closeEnough(paid, due),
            "the settlement was retried and bill " + billId + " now shows "
          + Money.round(paid) + " paid against " + Money.round(due) + " due");
        assertEquals(Money.at(bill, "data.amount_due").compareTo(ZERO), 0,
            "bill " + billId + " shows an amount still due after full settlement");
    }
}

package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.annotations.BeforeClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * What every journey test needs: the catalogue, the snapshots, and the three
 * things a journey checks.
 *
 * The three layers, in the order a test applies them:
 *
 *   1. the call worked          -> statusCode(200/201) inside Steps
 *   2. the RECORD is right      -> read the bill back, check customer_id,
 *                                  status, amount_paid, refund_amount
 *   3. the REPORTS are right    -> before/after snapshot, and each figure moved
 *                                  by an amount computed here in BigDecimal
 *
 * Layer 3 is the one that matters. Layers 1 and 2 tell you the API is
 * internally consistent; only layer 3 tells you the salon owner's revenue
 * report is correct.
 */
public abstract class BaseJourneyTest {

    protected static final String SALON = Env.JOURNEY_SALON;
    protected static final BigDecimal ZERO = BigDecimal.ZERO;
    protected static final BigDecimal ONE = BigDecimal.ONE;

    protected Catalogue catalogue;

    @BeforeClass(alwaysRun = true)
    public void prepareSalon() {
        Api.configure();
        catalogue = Catalogue.prepare(SALON);
    }

    // -----------------------------------------------------------------------
    // the journey, in pieces
    // -----------------------------------------------------------------------
    /** A customer that came in as an enquiry and was converted. */
    public record NewCustomer(String name, String phone, int enquiryId, int id) { }

    /**
     * Enquiry -> convert. The phone is generated fresh every time: reusing one
     * converts onto the EXISTING customer instead of creating a new one, which
     * silently breaks every new-customer assertion downstream.
     */
    protected NewCustomer arriveAsEnquiry(String label) {
        String phone = Steps.newPhone();
        int enquiryId = Steps.createEnquiry(SALON, label, phone);
        int customerId = Steps.convertEnquiry(SALON, enquiryId);
        assertTrue(customerId > 0, "the enquiry did not convert to a customer");
        return new NewCustomer(label, phone, enquiryId, customerId);
    }

    /** Book today, mark it completed, return the appointment id. */
    protected int completeAppointmentFor(NewCustomer customer) {
        int appointmentId = Steps.bookAppointment(
                SALON, customer.name(), customer.phone(), catalogue);
        Steps.setAppointmentStatus(appointmentId, "completed");
        return appointmentId;
    }

    protected int bookAppointmentFor(NewCustomer customer) {
        return Steps.bookAppointment(SALON, customer.name(), customer.phone(), catalogue);
    }

    protected int billFor(NewCustomer customer, int services, int products,
                          BigDecimal discountPct) {
        return Steps.createBill(customer.id(), catalogue, services, products,
                                catalogue.staffIds.get(0), discountPct);
    }

    protected int billFor(NewCustomer customer, int services, int products,
                          BigDecimal discountPct, Boolean gstEnabled) {
        return Steps.createBill(customer.id(), catalogue, services, products,
                                catalogue.staffIds.get(0), discountPct, gstEnabled);
    }

    // -----------------------------------------------------------------------
    // the arithmetic, worked out here rather than read back
    // -----------------------------------------------------------------------
    protected BigDecimal subtotal(int services, int products) {
        return Money.round(catalogue.servicePrice.multiply(BigDecimal.valueOf(services))
                    .add(catalogue.productPrice.multiply(BigDecimal.valueOf(products))));
    }

    protected BigDecimal taxable(int services, int products, BigDecimal discountPct) {
        BigDecimal sub = subtotal(services, products);
        return Money.round(sub.subtract(Money.discount(sub, discountPct, ZERO)));
    }

    /** What the customer pays: tax added, then rounded to whole rupees if the
     *  salon has round-off on (it does by default). */
    protected BigDecimal gross(int services, int products, BigDecimal discountPct) {
        return Money.netPayable(taxable(services, products, discountPct),
                                catalogue.gstRate, catalogue.roundOffEnabled);
    }

    protected BigDecimal gross(int services, int products, BigDecimal discountPct,
                               BigDecimal rate) {
        return Money.gross(taxable(services, products, discountPct), rate);
    }

    protected BigDecimal tax(int services, int products, BigDecimal discountPct) {
        return Money.tax(taxable(services, products, discountPct), catalogue.gstRate);
    }

    // -----------------------------------------------------------------------
    // assertions
    // -----------------------------------------------------------------------
    protected Map<String, BigDecimal> snapshot() {
        return Reports.snapshot(SALON, Reports.MONEY_TRAIL);
    }

    /** The money trail plus the Appointments report, for Block 4. */
    protected Map<String, BigDecimal> calendarSnapshot() {
        return Reports.snapshot(SALON, Reports.CALENDAR_TRAIL);
    }

    /** Only the Appointments report - for journeys that never raise a bill,
     *  where reading six money reports twice would prove nothing. */
    protected Map<String, BigDecimal> appointmentsSnapshot() {
        return Reports.snapshot(SALON, Reports.CALENDAR_ONLY);
    }

    protected void expectMoved(Map<String, BigDecimal> before,
                               Map<String, BigDecimal> after,
                               String key, BigDecimal expected) {
        BigDecimal actual = Reports.moved(before, after, key);
        assertTrue(Money.closeEnough(actual, expected),
                   Reports.describe(before, after, key, expected));
    }

    /**
     * The full money trail for one bill raised and settled inside the window.
     *
     * Note the two bases: Sales and Payments count the tax-INCLUSIVE figure,
     * Profit counts the tax-EXCLUSIVE one. Both are correct and they are easy
     * to confuse, which is exactly why both are asserted.
     */
    protected void expectSettled(Map<String, BigDecimal> before,
                                 Map<String, BigDecimal> after,
                                 int services, int products, BigDecimal discountPct) {
        BigDecimal gross = gross(services, products, discountPct);
        BigDecimal taxable = taxable(services, products, discountPct);
        BigDecimal tax = tax(services, products, discountPct);
        BigDecimal discount = Money.discount(subtotal(services, products), discountPct, ZERO);

        expectMoved(before, after, "sales.total_revenue", gross);
        expectMoved(before, after, "sales.bills_count", ONE);
        expectMoved(before, after, "sales.total_discount", discount);
        expectMoved(before, after, "payments.total_collected", gross);
        expectMoved(before, after, "payments.payments_count", ONE);
        expectMoved(before, after, "customers.new_customers", ONE);
        expectMoved(before, after, "customers.total_spend_in_range", gross);
        expectMoved(before, after, "profit.gross_revenue", taxable);
        expectMoved(before, after, "profit.net_profit", taxable);
        expectMoved(before, after, "profit.tax_collected", tax);
        expectMoved(before, after, "profit.total_discount", discount);
        expectMoved(before, after, "staff_performance.total_service_revenue", gross);
        if (products == 0) {
            expectMoved(before, after, "sales.service_revenue", gross);
            expectMoved(before, after, "services.total_revenue", gross);
            expectMoved(before, after, "services.services_sold",
                        BigDecimal.valueOf(services));
        }
    }

    /** Nothing was sold, so no money may have moved anywhere. */
    protected void expectNothingSold(Map<String, BigDecimal> before,
                                     Map<String, BigDecimal> after) {
        expectMoved(before, after, "sales.total_revenue", ZERO);
        expectMoved(before, after, "sales.bills_count", ZERO);
        expectMoved(before, after, "payments.total_collected", ZERO);
        expectMoved(before, after, "payments.payments_count", ZERO);
        expectMoved(before, after, "customers.total_spend_in_range", ZERO);
        expectMoved(before, after, "profit.gross_revenue", ZERO);
    }

    /**
     * A full refund nets every money figure back to zero AND records itself.
     * A sale that was silently deleted also nets to zero - that is a different,
     * worse bug, and this is what tells the two apart.
     */
    protected void expectFullyReversed(Map<String, BigDecimal> before,
                                       Map<String, BigDecimal> after,
                                       BigDecimal collected) {
        expectMoved(before, after, "sales.total_revenue", ZERO);
        expectMoved(before, after, "sales.service_revenue", ZERO);
        expectMoved(before, after, "payments.total_collected", ZERO);
        expectMoved(before, after, "customers.total_spend_in_range", ZERO);
        expectMoved(before, after, "profit.gross_revenue", ZERO);
        expectMoved(before, after, "profit.net_profit", ZERO);
        expectMoved(before, after, "profit.tax_collected", ZERO);
        expectMoved(before, after, "services.total_revenue", ZERO);
        expectMoved(before, after, "staff_performance.total_service_revenue", ZERO);
        expectMoved(before, after, "payments.failed_refunded_amount", collected);
    }

    // -- record-level checks (layer 2) --------------------------------------
    protected void assertBillIsPaidBy(int billId, NewCustomer customer) {
        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getInt("data.customer_id"), customer.id(),
            "bill " + billId + " was raised against a different customer than the "
          + "enquiry converted to (enquiry " + customer.enquiryId() + ")");
        assertEquals(bill.getString("data.status"), "paid",
            "bill " + billId + " was settled in full but is not marked paid");
        assertEquals(Money.at(bill, "data.amount_due").compareTo(ZERO), 0,
            "bill " + billId + " still shows an amount due after full settlement");
    }

    protected void assertBillIsRefunded(int billId) {
        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "refunded",
            "bill " + billId + " was refunded in full but its status is not 'refunded'");
        assertTrue(Money.at(bill, "data.refund_amount").signum() > 0,
            "bill " + billId + " nets to zero revenue but carries no refund_amount - "
          + "the sale was erased rather than reversed");
        assertTrue(bill.getString("data.refunded_at") != null,
            "bill " + billId + " was refunded but has no refunded_at timestamp - "
          + "there is no audit trail for the money going back");
    }

    protected void assertAppointmentIs(int appointmentId, String expected) {
        JsonPath calendar = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("date", LocalDate.now().toString())
                .queryParam("per_page", 200)
                .when().get("/salons/{salonId}/appointments")
                .then().statusCode(200).extract().jsonPath();

        String status = calendar.getString(
                "data.appointments.find { it.id == " + appointmentId + " }.status");
        assertTrue(status != null,
            "appointment " + appointmentId + " is not on today's calendar at all");
        assertEquals(status.toLowerCase().replace("_", ""),
                     expected.toLowerCase().replace("_", ""),
            "appointment " + appointmentId + " should be '" + expected
          + "' but the calendar says '" + status + "'");
    }

    /**
     * Both snapshots read range=today, and "today" is not a constant. A journey
     * that opens at 23:59:58 and closes at 00:00:03 compares one day's totals
     * against the next day's, and every delta it reports is fiction.
     */
    protected void assertSameDay(LocalDate openedOn) {
        assertEquals(Steps.today(), openedOn,
            "this journey ran across midnight. Both snapshots read range=today, "
          + "so they cover different days and the deltas are meaningless. "
          + "Re-run; nothing is wrong with the API.");
    }
}

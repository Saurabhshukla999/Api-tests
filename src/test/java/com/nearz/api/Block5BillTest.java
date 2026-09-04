package com.nearz.api;

import io.restassured.path.json.JsonPath;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-051 .. 075 - bill construction.
 *
 * Blocks 1 and 4 ask "did the journey happen?". This block asks "is the
 * INVOICE right?" - every knob the billing API has, one at a time, with the
 * expected figure worked out in Java from the price list.
 *
 * The knobs, all confirmed against the live API on 4 Sep 2026:
 *
 *     discount { pct, flat }        both apply, and they ADD together
 *     tax { gst_enabled, mode }     mode is "exclusive" or "inclusive"
 *     salon setting round_off_enabled   whole-rupee settlement, SALON-WIDE
 *
 * Two rules this block follows, both learned from the duplicates that had to be
 * deleted out of Block 1:
 *
 *   1. Every case checks something no other case checks. Where the source list
 *      repeats a journey (051/052/053 are the same sentence three times), the
 *      journey is the same but the LAYER under test is different - the reports,
 *      then the persisted invoice, then the payment record.
 *   2. Where the API cannot do the thing, the case is parked with the measured
 *      reason, not quietly rewritten into something easier.
 */
public class Block5BillTest extends BaseJourneyTest {

    private static final BigDecimal TEN_PERCENT = new BigDecimal("10");
    private static final BigDecimal FLAT_250    = new BigDecimal("250");
    private static final BigDecimal TWO         = new BigDecimal("2");
    private static final BigDecimal THREE       = new BigDecimal("3");

    /** A customer who has been through a completed appointment, ready to bill. */
    private NewCustomer served(String label) {
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        return customer;
    }

    private int staff() {
        return catalogue.staffIds.get(0);
    }

    // =====================================================================
    // 051-055  the basket
    // =====================================================================
    /**
     * API-E2E-051 the baseline: one service, billed and paid, all six reports.
     *
     * The other twenty-four cases are this one with a single thing changed, so
     * if this fails, read its failure before reading any of theirs.
     */
    @Test(description = "API-E2E-051 completed appointment, service bill, full payment, every report")
    public void e2e051_serviceBillBaseline() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-051");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);
        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
    }

    /**
     * API-E2E-052 the same journey, but checking the INVOICE rather than the
     * reports.
     *
     * 051 proves the salon's reports moved. This proves the bill the customer
     * is handed adds up on its own terms, which is a different claim: the
     * persisted breakdown must reconcile.
     *
     *     service_total + product_total - discount_total = taxable
     *     taxable + tax + round_off                      = net_payable
     *     cgst + sgst                                    = tax
     *
     * TotalsCalculator persists these rather than deriving them on read,
     * specifically so reprinting an old invoice cannot pick up today's tax
     * rates. That makes them worth checking - a persisted figure can rot.
     */
    @Test(description = "API-E2E-052 the persisted invoice breakdown reconciles with itself")
    public void e2e052_invoiceBreakdownReconciles() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-052");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        BigDecimal serviceTotal = Money.at(bill, "data.totals.service_total");
        BigDecimal productTotal = Money.at(bill, "data.totals.product_total");
        BigDecimal discount     = Money.at(bill, "data.totals.discount_total");
        BigDecimal taxable      = Money.at(bill, "data.totals.taxable");
        BigDecimal tax          = Money.at(bill, "data.totals.tax");
        BigDecimal roundOff     = Money.at(bill, "data.round_off");
        BigDecimal netPayable   = Money.at(bill, "data.net_payable");
        BigDecimal cgst         = Money.at(bill, "data.taxes.cgst");
        BigDecimal sgst         = Money.at(bill, "data.taxes.sgst");

        // 1. the lines add up to the taxable base
        assertEquals(serviceTotal.add(productTotal).subtract(discount).compareTo(taxable), 0,
            "the invoice does not add up: service " + serviceTotal + " + product "
          + productTotal + " - discount " + discount + " should be the taxable "
          + taxable);

        // 2. taxable + tax + round_off is what the customer pays
        assertTrue(Money.closeEnough(taxable.add(tax).add(roundOff), netPayable),
            "taxable " + taxable + " + tax " + tax + " + round_off " + roundOff
          + " should equal net_payable " + netPayable + ", but does not - the "
          + "invoice cannot be reconciled by the person holding it");

        // 3. the GST halves add up, and are halves
        assertTrue(Money.closeEnough(cgst.add(sgst), tax),
            "cgst " + cgst + " + sgst " + sgst + " should equal the tax " + tax);
        assertEquals(cgst.compareTo(sgst), 0,
            "CGST and SGST should be equal halves but are " + cgst + " and " + sgst);

        // 4. and it agrees with what we computed independently
        assertTrue(Money.closeEnough(netPayable, gross(1, 0, ZERO)),
            Money.difference("net payable", gross(1, 0, ZERO), netPayable));
    }

    /**
     * API-E2E-053 the same journey again, checking the PAYMENT record.
     *
     * The third distinct layer: 051 the reports, 052 the invoice, 053 the money
     * actually received. A bill can show the right total, the reports can move,
     * and the payment row can still be missing or for the wrong amount.
     */
    @Test(description = "API-E2E-053 the settlement is recorded on the bill with the right amount and mode")
    public void e2e053_paymentRecorded() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-053");
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "paid", "the bill is not marked paid");
        assertEquals(Money.at(bill, "data.amount_paid").compareTo(due), 0,
            "amount_paid is " + bill.get("data.amount_paid") + " but " + due
          + " was collected");
        assertEquals(Money.at(bill, "data.amount_due").compareTo(ZERO), 0,
            "the bill still shows an amount due after settlement");
        assertTrue(bill.getString("data.billed_at") != null,
            "the bill was settled but has no billed_at timestamp - there is no "
          + "record of WHEN the money was taken");

        BigDecimal recorded = Money.at(bill, "data.payments[0].amount");
        assertEquals(recorded.compareTo(due), 0,
            "the payment row says " + recorded + " but " + due + " was collected");
        assertEquals(bill.getString("data.payments[0].mode"), "cash",
            "the payment was taken in cash but is recorded as "
          + bill.getString("data.payments[0].mode"));
    }

    @Test(description = "API-E2E-054 a bill carrying both a service and a product")
    public void e2e054_serviceAndProduct() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-054");
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        // The two totals must be kept apart on the invoice. Finding A in
        // docs/DEFECTS.md is exactly this going wrong one level up, in the
        // Staff Performance report.
        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.totals.service_total")
                          .compareTo(catalogue.servicePrice), 0,
            "the service total on the invoice is wrong");
        assertEquals(Money.at(bill, "data.totals.product_total")
                          .compareTo(catalogue.productPrice), 0,
            "the product total on the invoice is wrong");

        expectSettled(before, after, 1, 1, ZERO);
    }

    @Test(description = "API-E2E-055 three services on one bill")
    public void e2e055_multipleServices() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-055");
        int billId = billFor(customer, 3, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        assertEquals(Steps.readBill(billId).getList("data.items").size(), 3,
            "three service lines were sent but the bill does not carry three");
        expectSettled(before, after, 3, 0, ZERO);
        expectMoved(before, after, "sales.bills_count", ONE);   // 3 lines, ONE bill
    }

    // =====================================================================
    // 056-057  discounts
    // =====================================================================
    @Test(description = "API-E2E-056 a 10% discount comes off before tax")
    public void e2e056_percentageDiscount() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-056");
        int billId = billFor(customer, 1, 0, TEN_PERCENT);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        // The ORDER matters and is the whole point of this test. Tax is charged
        // on 900, not on 1,000 - discounting after tax would overcharge the
        // customer 18 rupees and over-report the GST owed to the government.
        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.totals.taxable")
                          .compareTo(taxable(1, 0, TEN_PERCENT)), 0,
            "tax was charged on the wrong base: the invoice says "
          + bill.get("data.totals.taxable") + ", it should be "
          + taxable(1, 0, TEN_PERCENT) + " (1,000 less 10%)");

        expectSettled(before, after, 1, 0, TEN_PERCENT);
    }

    /**
     * API-E2E-057 a flat rupee discount.
     *
     * A different code path from the percentage: BillComposer stores pct and
     * flat separately and TotalsCalculator ADDS them. So a flat discount is not
     * just a percentage in disguise, and it gets its own test.
     */
    @Test(description = "API-E2E-057 a flat 250 discount")
    public void e2e057_flatDiscount() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-057");
        int billId = Steps.createBillFully(customer.id(), catalogue, 1, 0, staff(),
                                           ZERO, FLAT_250, null, null, null, null);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        BigDecimal taxable = Money.round(catalogue.servicePrice.subtract(FLAT_250));
        BigDecimal gross   = Money.netPayable(taxable, catalogue.gstRate,
                                              catalogue.roundOffEnabled);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.discount_amount").compareTo(FLAT_250), 0,
            "the flat discount was recorded as " + bill.get("data.discount_amount")
          + " instead of " + FLAT_250);
        assertEquals(Money.at(bill, "data.net_payable").compareTo(gross), 0,
            Money.difference("net payable after a flat 250 off",
                             gross, Money.at(bill, "data.net_payable")));

        expectMoved(before, after, "sales.total_revenue", gross);
        expectMoved(before, after, "sales.total_discount", FLAT_250);
        expectMoved(before, after, "payments.total_collected", gross);
        expectMoved(before, after, "profit.gross_revenue", taxable);
    }

    // =====================================================================
    // 058-063  tax
    // =====================================================================
    @Test(description = "API-E2E-058 GST at 18% is split evenly into CGST and SGST")
    public void e2e058_gstEighteenPercent() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-058");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        BigDecimal expectedTax = tax(1, 0, ZERO);
        JsonPath bill = Steps.readBill(billId);
        BigDecimal cgst = Money.at(bill, "data.taxes.cgst");
        BigDecimal sgst = Money.at(bill, "data.taxes.sgst");

        assertTrue(Money.closeEnough(cgst.add(sgst), expectedTax),
            Money.difference("CGST + SGST", expectedTax, cgst.add(sgst)));
        assertEquals(cgst.compareTo(sgst), 0,
            "an intra-state bill splits GST into two equal halves, but this one "
          + "has CGST " + cgst + " and SGST " + sgst);
        assertEquals(bill.getString("data.taxes.mode"), "exclusive",
            "salon " + SALON + " bills tax-exclusive by default; this bill says "
          + bill.getString("data.taxes.mode"));

        expectMoved(before, after, "profit.tax_collected", expectedTax);
    }

    @Test(description = "API-E2E-059 a GST-disabled bill charges no tax at all")
    public void e2e059_gstDisabled() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-059");
        // gst_enabled=false applies to THIS BILL only - BillComposer copies the
        // flag onto the bill and TotalsCalculator reads it from there, never
        // consulting the salon. So this stays safe to run beside everything else.
        int billId = billFor(customer, 1, 0, ZERO, Boolean.FALSE);
        BigDecimal untaxed = taxable(1, 0, ZERO);

        assertTrue(Money.closeEnough(Steps.netPayable(billId), untaxed),
            Money.difference("a GST-disabled bill", untaxed, Steps.netPayable(billId)));

        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        assertEquals(Steps.readBill(billId).getBoolean("data.taxes.gst_enabled"), false,
            "gst_enabled was sent as false but the bill does not say so");
        expectMoved(before, after, "sales.total_revenue", untaxed);
        expectMoved(before, after, "payments.total_collected", untaxed);
        expectMoved(before, after, "profit.tax_collected", ZERO);
        expectMoved(before, after, "profit.gross_revenue", untaxed);
    }

    /**
     * API-E2E-060 tax INCLUSIVE - the listed price is the final price.
     *
     * The opposite direction from every other test in the suite, and the one
     * most likely to be implemented backwards:
     *
     *     exclusive   1,000 listed  ->  customer pays 1,180
     *     inclusive   1,000 listed  ->  customer pays 1,000, of which
     *                                   152.54 was tax all along
     *
     * Measured on the live API: net_payable 1000.00, taxable 847.46, tax 152.54.
     */
    @Test(description = "API-E2E-060 a tax-inclusive bill charges the listed price and backs the tax out")
    public void e2e060_gstInclusive() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-060");
        int billId = Steps.createBillFully(customer.id(), catalogue, 1, 0, staff(),
                                           ZERO, ZERO, null, "inclusive", null, null);

        BigDecimal listed = catalogue.servicePrice;                      // 1000.00
        BigDecimal tax    = Money.taxWithin(listed, catalogue.gstRate);  //  152.54
        BigDecimal base   = Money.baseWithin(listed, catalogue.gstRate); //  847.46

        JsonPath draft = Steps.readBill(billId);
        assertEquals(draft.getString("data.taxes.mode"), "inclusive",
            "the bill was created with mode=inclusive but reports "
          + draft.getString("data.taxes.mode"));
        assertEquals(Money.at(draft, "data.net_payable").compareTo(listed), 0,
            "a tax-inclusive bill must charge the LISTED price " + listed
          + ", but this one asks for " + draft.get("data.net_payable")
          + " - the tax has been added on top instead of taken out");
        assertTrue(Money.closeEnough(Money.at(draft, "data.tax_amount"), tax),
            Money.difference("tax inside an inclusive price",
                             tax, Money.at(draft, "data.tax_amount")));
        assertTrue(Money.closeEnough(Money.at(draft, "data.totals.taxable"), base),
            Money.difference("the pre-tax base of an inclusive price",
                             base, Money.at(draft, "data.totals.taxable")));

        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        // Sales counts what the customer paid; Profit counts the pre-tax base.
        expectMoved(before, after, "sales.total_revenue", listed);
        expectMoved(before, after, "payments.total_collected", listed);
        expectMoved(before, after, "profit.tax_collected", tax);
        expectMoved(before, after, "profit.gross_revenue", base);
    }

    /**
     * API-E2E-061 tax EXCLUSIVE, stated explicitly.
     *
     * The pair to 060: the same basket, the same price list, the other mode,
     * and a customer who pays 180 rupees more. Run them together and the
     * difference between the two modes is one number.
     */
    @Test(description = "API-E2E-061 a tax-exclusive bill adds the tax on top")
    public void e2e061_gstExclusive() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-061");
        int billId = Steps.createBillFully(customer.id(), catalogue, 1, 0, staff(),
                                           ZERO, ZERO, null, "exclusive", null, null);

        BigDecimal listed = catalogue.servicePrice;
        BigDecimal gross  = gross(1, 0, ZERO);

        JsonPath draft = Steps.readBill(billId);
        assertEquals(draft.getString("data.taxes.mode"), "exclusive",
            "mode=exclusive was sent but the bill says "
          + draft.getString("data.taxes.mode"));
        assertEquals(Money.at(draft, "data.net_payable").compareTo(gross), 0,
            "a tax-exclusive bill adds tax on top: " + listed + " should become "
          + gross + ", not " + draft.get("data.net_payable"));

        // And the contrast with 060, asserted rather than left implied.
        assertTrue(gross.compareTo(listed) > 0,
            "exclusive tax must cost the customer MORE than the listed price, "
          + "or the two modes are the same thing");

        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);
        expectSettled(before, after, 1, 0, ZERO);
    }

    @Test(description = "API-E2E-062 a discount and GST together, in the right order")
    public void e2e062_discountAndGst() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-062");
        int billId = billFor(customer, 1, 0, TEN_PERCENT);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        // discount first, then tax. 1000 -> 900 -> 1062.
        BigDecimal taxable = taxable(1, 0, TEN_PERCENT);
        BigDecimal tax     = tax(1, 0, TEN_PERCENT);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.totals.taxable").compareTo(taxable), 0,
            "the discount was not applied before tax");
        assertTrue(Money.closeEnough(Money.at(bill, "data.tax_amount"), tax),
            Money.difference("tax on a discounted bill",
                             tax, Money.at(bill, "data.tax_amount")));

        expectSettled(before, after, 1, 0, TEN_PERCENT);
    }

    /**
     * API-E2E-063 everything at once: service, product, discount and GST.
     *
     * This is the basket that caught the twenty-paise bug on 28 Aug 2026. Up to
     * that day every test basket landed on a whole rupee by luck; a mixed
     * basket does not, so this is the case that exercises the round-off path
     * for real.
     */
    @Test(description = "API-E2E-063 service plus product plus discount plus GST")
    public void e2e063_theCompoundBasket() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-063");
        int billId = billFor(customer, 1, 1, TEN_PERCENT);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        BigDecimal subtotal = subtotal(1, 1);                      // 1200.00
        BigDecimal discount = Money.discount(subtotal, TEN_PERCENT, ZERO);
        BigDecimal taxable  = taxable(1, 1, TEN_PERCENT);          // 1080.00
        BigDecimal gross    = gross(1, 1, TEN_PERCENT);            // 1274.40 -> 1274

        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.subtotal").compareTo(subtotal), 0,
            Money.difference("subtotal", subtotal, Money.at(bill, "data.subtotal")));
        assertEquals(Money.at(bill, "data.discount_amount").compareTo(discount), 0,
            Money.difference("discount", discount, Money.at(bill, "data.discount_amount")));
        assertEquals(Money.at(bill, "data.net_payable").compareTo(gross), 0,
            Money.difference("net payable on the compound basket",
                             gross, Money.at(bill, "data.net_payable")));

        expectSettled(before, after, 1, 1, TEN_PERCENT);

        // The round-off lands HERE, and this is the assertion that found it.
        // The basket is taxable 1,080 + 194.40 tax = 1,274.40, settled at
        // 1,274. So the salon received 1,274, of which 194.40 is tax it is
        // holding, leaving 1,079.60 of revenue - NOT the 1,080 the pre-round
        // arithmetic suggests. Forty paise, and it is the same forty paise
        // that broke four tests on 28 Aug.
        BigDecimal received = Money.round(gross.subtract(tax(1, 1, TEN_PERCENT)));
        expectMoved(before, after, "profit.gross_revenue", received);
        assertTrue(received.compareTo(taxable) < 0,
            "on a basket that rounds down, the salon keeps LESS revenue than "
          + "the pre-round taxable figure. Here " + received + " should be "
          + "below " + taxable + " - if they are equal, the round-off is not "
          + "reaching the Profit report at all");
    }

    // =====================================================================
    // 064-065  round-off
    // =====================================================================
    /**
     * API-E2E-064 round-off ON (the salon default): bills settle in whole rupees.
     *
     * The basket is chosen so the arithmetic lands on paise. 1,200 less 10% is
     * 1,080; at 18% that is 1,274.40, which the salon rounds to 1,274 and
     * records the 40 paise in round_off. TotalsCalculator promises
     * gross_payable + round_off == net_payable, always - so that identity is
     * what gets asserted.
     */
    @Test(description = "API-E2E-064 with round-off on, the paise go into round_off")
    public void e2e064_roundOffEnabled() {
        LocalDate day = Steps.today();
        if (!catalogue.roundOffEnabled) {
            throw new SkipException(
                "salon " + SALON + " has round_off_enabled=false right now, so "
              + "this case cannot be observed. Turn it back on in Settings.");
        }

        NewCustomer customer = served("QA E2E-064");
        int billId = billFor(customer, 1, 1, TEN_PERCENT);
        assertSameDay(day);

        BigDecimal taxable    = taxable(1, 1, TEN_PERCENT);                     // 1080.00
        BigDecimal grossExact = Money.gross(taxable, catalogue.gstRate);        // 1274.40
        BigDecimal settled    = gross(1, 1, TEN_PERCENT);                       // 1274.00

        JsonPath bill = Steps.readBill(billId);
        BigDecimal net      = Money.at(bill, "data.net_payable");
        BigDecimal roundOff = Money.at(bill, "data.round_off");

        assertEquals(net.compareTo(settled), 0,
            Money.difference("net payable with round-off on", settled, net));
        assertEquals(net.stripTrailingZeros().scale() <= 0, true,
            "round-off is on, so the bill must settle in whole rupees, but it "
          + "asks for " + net);
        assertTrue(Money.closeEnough(grossExact.add(roundOff), net),
            "TotalsCalculator promises gross_payable + round_off == net_payable. "
          + "Here " + grossExact + " + " + roundOff + " is not " + net);

        Steps.settleInFull(billId, "cash");
    }

    /**
     * API-E2E-065 round-off OFF: the paise survive to the customer.
     *
     * THE ONLY TEST IN THE SUITE THAT CHANGES A SALON-WIDE SETTING. There is no
     * per-bill override - TotalsCalculator reads
     * bill.salon.salon_setting.round_off_enabled directly - so the switch has to
     * be flipped for real.
     *
     * That makes the finally block the most important part of this method. If
     * the setting were left off, every later journey in the run would compute a
     * whole-rupee total the API no longer produces, and the failures would look
     * like an API bug rather than a test that did not clean up after itself.
     * The restore is verified, not assumed.
     */
    @Test(description = "API-E2E-065 with round-off off, the bill keeps its paise")
    public void e2e065_roundOffDisabled() {
        LocalDate day = Steps.today();
        boolean wasEnabled = Steps.readSalonSetting(SALON)
                                  .getBoolean("data.setting.round_off_enabled");

        int flipped = Steps.setRoundOff(SALON, false);
        if (flipped != 200) {
            throw new SkipException(
                "PATCH /salons/" + SALON + "/settings answered " + flipped
              + " so round-off could not be turned off. Round-off is salon-wide "
              + "with no per-bill override, so this case cannot be tested "
              + "without that endpoint.");
        }

        try {
            assertEquals(Steps.readSalonSetting(SALON)
                              .getBoolean("data.setting.round_off_enabled"), false,
                "the settings PATCH answered 200 but round-off is still on");

            NewCustomer customer = served("QA E2E-065");
            int billId = billFor(customer, 1, 1, TEN_PERCENT);

            // No rounding now, so the exact arithmetic is what must be charged.
            BigDecimal exact = Money.gross(taxable(1, 1, TEN_PERCENT), catalogue.gstRate);
            JsonPath bill = Steps.readBill(billId);

            assertEquals(Money.at(bill, "data.net_payable").compareTo(exact), 0,
                Money.difference("net payable with round-off OFF",
                                 exact, Money.at(bill, "data.net_payable")));
            assertEquals(Money.at(bill, "data.round_off").compareTo(ZERO), 0,
                "round-off is disabled, so round_off must be zero, not "
              + bill.get("data.round_off"));

            Steps.settleInFull(billId, "cash");
            assertSameDay(day);

        } finally {
            // Put the salon back however this test ends, including on failure.
            Steps.setRoundOff(SALON, wasEnabled);
            boolean restored = Steps.readSalonSetting(SALON)
                                    .getBoolean("data.setting.round_off_enabled");
            assertEquals(restored, wasEnabled,
                "COULD NOT RESTORE round_off_enabled on salon " + SALON + ". It is "
              + "now " + restored + " and should be " + wasEnabled + ". Fix this in "
              + "Settings before running anything else - every money assertion in "
              + "the suite depends on it.");
        }
    }

    // =====================================================================
    // 066-069  taking the money
    // =====================================================================
    @Test(description = "API-E2E-066 a cash settlement reaches the Payments report")
    public void e2e066_cashPayment() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-066");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);
        assertEquals(Steps.readBill(billId).getString("data.payments[0].mode"), "cash",
            "the mode was not recorded as cash");
        expectMoved(before, after, "payments.total_collected", gross(1, 0, ZERO));
        expectMoved(before, after, "payments.payments_count", ONE);
    }

    /**
     * API-E2E-067 the same bill settled by CARD.
     *
     * The salon's enabled_payment_modes are cash, upi and card. The money must
     * reach the reports identically whichever one is used - a mode that records
     * the sale but not the collection would make the day's takings look short.
     */
    @Test(description = "API-E2E-067 a card settlement reaches the reports the same as cash")
    public void e2e067_cardPayment() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-067");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "card");

        var after = snapshot();
        assertSameDay(day);

        assertEquals(Steps.readBill(billId).getString("data.payments[0].mode"), "card",
            "the bill was settled by card but the payment row says "
          + Steps.readBill(billId).getString("data.payments[0].mode"));
        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
    }

    @Test(description = "API-E2E-068 a bill split across cash and card")
    public void e2e068_splitPayment() {
        throw new SkipException(
            "A split settlement is a PARTIAL payment, and partial payment is "
          + "switched off in the frontend, so the flow is not reachable. This is "
          + "the same reason the other partial-payment cases are parked - see "
          + "docs/coverage_gaps.md. Turn the feature on and delete this skip.");
    }

    /**
     * API-E2E-069 the bill is settled, then a second full payment is sent.
     *
     * Checked at the BILL level: whatever the API answers, the bill must not
     * end up holding two payments or a negative amount due. 073 asks the same
     * question of the REPORTS.
     */
    @Test(description = "API-E2E-069 a second payment on a settled bill does not collect twice")
    public void e2e069_secondPaymentOnASettledBill() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-069");
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);
        Steps.settleInFull(billId, "cash");

        int answer = Steps.repeatPayment(billId, due, "cash");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        BigDecimal paid = Money.at(bill, "data.amount_paid");
        assertEquals(paid.compareTo(due), 0,
            "a second payment of " + due + " was sent and the API answered "
          + answer + ". The bill now shows " + paid + " collected against a "
          + due + " bill - the customer has been charged twice.");
        assertEquals(Money.at(bill, "data.amount_due").compareTo(ZERO), 0,
            "amount_due went to " + bill.get("data.amount_due")
          + " after a repeat payment; it must stay at zero");
        assertEquals(bill.getString("data.status"), "paid",
            "the bill left 'paid' after a repeat payment and is now "
          + bill.getString("data.status"));
    }

    // =====================================================================
    // 070-075  reading it back, editing it, and abusing it
    // =====================================================================
    @Test(description = "API-E2E-070 the bill reads back with a complete payment history")
    public void e2e070_paymentHistoryReadsBack() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-070");
        int billId = billFor(customer, 1, 1, ZERO);
        BigDecimal due = Steps.netPayable(billId);
        Steps.settleInFull(billId, "card");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getInt("data.id"), billId, "a different bill came back");
        assertEquals(bill.getInt("data.customer_id"), customer.id(),
            "the bill belongs to a different customer");
        assertEquals(bill.getList("data.items").size(), 2,
            "the basket had two lines; the bill reads back with "
          + bill.getList("data.items").size());
        assertTrue(bill.getList("data.payments").size() >= 1,
            "the bill was settled but carries no payment history at all");
        assertEquals(Money.at(bill, "data.payments[0].amount").compareTo(due), 0,
            "the payment history says " + bill.get("data.payments[0].amount")
          + " but " + due + " was collected");
        assertTrue(bill.getString("data.bill_number") != null,
            "the bill has no invoice number, so the customer has nothing to quote");
    }

    /**
     * API-E2E-071 subtotal, tax and total on a DRAFT, before any money moves.
     *
     * The draft is the quote the receptionist reads out. If it disagrees with
     * what is charged a minute later, the customer is told one price and billed
     * another - so the draft is checked against the same independent arithmetic
     * as the settled bill.
     */
    @Test(description = "API-E2E-071 a draft bill quotes the same total it will charge")
    public void e2e071_draftQuoteIsCorrect() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-071");
        int billId = billFor(customer, 2, 1, TEN_PERCENT);

        BigDecimal subtotal = subtotal(2, 1);
        BigDecimal taxable  = taxable(2, 1, TEN_PERCENT);
        BigDecimal tax      = tax(2, 1, TEN_PERCENT);
        BigDecimal gross    = gross(2, 1, TEN_PERCENT);

        JsonPath draft = Steps.readBill(billId);
        assertEquals(draft.getString("data.status"), "draft",
            "this bill should still be a draft");
        assertEquals(Money.at(draft, "data.subtotal").compareTo(subtotal), 0,
            Money.difference("draft subtotal", subtotal, Money.at(draft, "data.subtotal")));
        assertTrue(Money.closeEnough(Money.at(draft, "data.tax_amount"), tax),
            Money.difference("draft tax", tax, Money.at(draft, "data.tax_amount")));
        assertEquals(Money.at(draft, "data.totals.taxable").compareTo(taxable), 0,
            Money.difference("draft taxable", taxable, Money.at(draft, "data.totals.taxable")));

        // and the quote is what actually gets charged
        BigDecimal quoted = Money.at(draft, "data.net_payable");
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);
        assertEquals(Money.at(Steps.readBill(billId), "data.amount_paid").compareTo(quoted), 0,
            "the draft quoted " + quoted + " but a different amount was charged");
        assertEquals(quoted.compareTo(gross), 0,
            Money.difference("the quote", gross, quoted));
    }

    /**
     * API-E2E-072 the basket is edited before payment.
     *
     * The customer adds a shampoo at the till. The reports must reflect the
     * FINAL basket - a report built from the bill as first created would
     * under-report the sale by the price of the product, and nobody would ever
     * notice because the invoice itself would look right.
     */
    @Test(description = "API-E2E-072 a bill edited before payment is reported at its final value")
    public void e2e072_editedBeforePayment() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-072");
        int billId = billFor(customer, 1, 0, ZERO);              // service only
        BigDecimal firstQuote = Steps.netPayable(billId);

        Steps.updateBillItems(billId, catalogue, 1, 1, staff()); // + a product
        BigDecimal finalQuote = Steps.netPayable(billId);

        assertTrue(finalQuote.compareTo(firstQuote) > 0,
            "a product was added but the total did not go up: " + firstQuote
          + " -> " + finalQuote + ". The edit was accepted and ignored.");
        assertEquals(finalQuote.compareTo(gross(1, 1, ZERO)), 0,
            Money.difference("the re-priced bill", gross(1, 1, ZERO), finalQuote));

        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        // The reports must show the EDITED basket, not the original one.
        expectSettled(before, after, 1, 1, ZERO);
    }

    /**
     * API-E2E-073 the identical settlement request is sent twice.
     *
     * 069 asked whether the BILL survives it. This asks whether the REPORTS do,
     * which is the question the salon owner actually cares about: a double
     * collection that the bill hides but Payments counts would inflate the
     * day's takings.
     */
    @Test(description = "API-E2E-073 a duplicate payment request does not double the reported takings")
    public void e2e073_duplicatePaymentRequest() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-073");
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);

        Steps.settleInFull(billId, "cash");
        int answer = Steps.attemptFinalize(billId, due, "cash");   // the same call again

        var after = snapshot();
        assertSameDay(day);

        BigDecimal gross = gross(1, 0, ZERO);
        // The API refusing the second call is a perfectly good answer. What it
        // must not do is collect twice, and only the reports can prove that.
        expectMoved(before, after, "payments.total_collected", gross);
        expectMoved(before, after, "payments.payments_count", ONE);
        expectMoved(before, after, "sales.total_revenue", gross);
        expectMoved(before, after, "sales.bills_count", ONE);
        assertTrue(true, "the repeat answered " + answer);
    }

    /**
     * API-E2E-074 settlement is attempted with the wrong amount.
     *
     * Measured: the API answers 422 "Payment amount does not match net payable
     * total" and leaves the bill alone. Both halves matter - refusing but
     * half-writing the payment would leave a bill nobody can settle.
     */
    @Test(description = "API-E2E-074 a wrong payment amount is refused and changes nothing")
    public void e2e074_wrongPaymentAmount() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-074");
        int billId = billFor(customer, 1, 0, ZERO);
        BigDecimal due = Steps.netPayable(billId);

        int answer = Steps.attemptFinalize(billId, ONE, "cash");   // 1 rupee
        assertTrue(answer >= 400,
            "the API ACCEPTED a 1 rupee payment against a " + due + " bill "
          + "(status " + answer + ")");

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "draft",
            "the payment was refused but the bill is no longer a draft - it is "
          + bill.getString("data.status"));
        assertEquals(Money.at(bill, "data.amount_paid").compareTo(ZERO), 0,
            "the payment was refused but " + bill.get("data.amount_paid")
          + " was recorded as collected");
        assertEquals(Money.at(bill, "data.net_payable").compareTo(due), 0,
            "the refused payment changed what the bill asks for");

        // and nothing reached the books
        var after = snapshot();
        assertSameDay(day);
        expectNothingSold(before, after);

        // the bill is still settleable afterwards, which is the real test of
        // "no corrupt state"
        Steps.settleInFull(billId, "cash");
        assertEquals(Steps.readBill(billId).getString("data.status"), "paid",
            "the bill could not be settled after a refused payment attempt");
    }

    /**
     * API-E2E-075 the closing case: one bill, and every report agrees about it.
     *
     * Block 5 ends where Block 1 began, but from the invoice side: the same
     * sale is looked up in Sales, Payments, Customers, Services, Staff
     * Performance and Profit, and all six must tell the same story about it.
     * The cross-check is the pair of bases - Sales counts 1,180 and Profit
     * counts 1,000 plus 180 of tax, so two independently built queries are
     * forced to reconcile.
     */
    @Test(description = "API-E2E-075 one sale, and all six reports agree about it")
    public void e2e075_everyReportAgrees() {
        LocalDate day = Steps.today();
        var before = snapshot();

        NewCustomer customer = served("QA E2E-075");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = snapshot();
        assertSameDay(day);

        BigDecimal gross   = gross(1, 0, ZERO);
        BigDecimal taxable = taxable(1, 0, ZERO);
        BigDecimal tax     = tax(1, 0, ZERO);

        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);

        // The cross-check, stated as its own assertion rather than left to the
        // reader: the tax-inclusive and tax-exclusive views must reconcile.
        assertTrue(Money.closeEnough(taxable.add(tax), gross),
            "Sales counts " + gross + " and Profit counts " + taxable + " + "
          + tax + ". Those must be the same money seen two ways, and they are "
          + "not - one of the two reports is wrong.");

        BigDecimal salesMoved  = Reports.moved(before, after, "sales.total_revenue");
        BigDecimal profitMoved = Reports.moved(before, after, "profit.gross_revenue");
        BigDecimal taxMoved    = Reports.moved(before, after, "profit.tax_collected");
        assertTrue(Money.closeEnough(profitMoved.add(taxMoved), salesMoved),
            "Sales moved " + salesMoved + " but Profit moved " + profitMoved
          + " with " + taxMoved + " of tax, which adds to "
          + profitMoved.add(taxMoved) + ". Two reports built by two different "
          + "queries disagree about the same sale.");
    }
}

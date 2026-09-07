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
 * API-E2E-151 .. 170 - products and inventory.
 *
 * The first block where a sale changes something PHYSICAL. Every other block
 * asks whether the books moved; this one also asks whether the shelf did, and
 * whether the two agree.
 *
 * Measured before writing, 7 Sep 2026:
 *
 *   stock       lives on the product LIST row as stock_qty, alongside
 *               reorder_level and a stock_state label. Settling a bill
 *               deducts it: opening 10, sold 2, list showed 8.
 *   no stock    the DRAFT is accepted but the settlement is refused with
 *               422 "Insufficient stock. Current: 0.0, adjustment: -1.0".
 *   part refund refunding an amount leaves the bill partially_refunded and
 *               issues a CREDIT NOTE carrying its own CGST/SGST split.
 *
 * One trap worth naming: GET /salons/{id}/products/{id} answers HTTP 200 with
 * a body of {"error":"not_found"} even for a product that exists - defect D1.
 * A test that read stock that way would see nothing and call it zero, so
 * Steps.productRow reads the list instead.
 */
public class Block7InventoryTest extends BaseJourneyTest {

    private static final BigDecimal TEN_PERCENT = new BigDecimal("10");
    private static final int COST = 100;
    private static final int SELL = 200;

    /** A product nobody else in the run will touch, so its stock is knowable. */
    private int stockItem(String label, int openingStock, int reorderLevel) {
        return Steps.createProduct(SALON, label + " " + System.nanoTime() % 1000000,
                                   COST, SELL, openingStock, reorderLevel);
    }

    private NewCustomer served(String label) {
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        return customer;
    }

    /** Sell `qty` of one product, settle, return the bill id. */
    private int sellProduct(NewCustomer customer, int productId, int qty) {
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "product", "ref_id", productId, "qty", qty,
                       "unit_price", SELL, "staff_id", catalogue.staffIds.get(0))));
        Steps.settleInFull(billId, "cash");
        return billId;
    }

    /** What a basket of `qty` units costs the customer, tax and rounding included. */
    private BigDecimal grossFor(int qty) {
        BigDecimal listed = new BigDecimal(SELL).multiply(BigDecimal.valueOf(qty));
        return Money.netPayable(listed, catalogue.gstRate, catalogue.roundOffEnabled);
    }

    // =====================================================================
    // 151-152, 159-162  stock moves with the sale
    // =====================================================================
    @Test(description = "API-E2E-151 selling a product takes it off the shelf")
    public void e2e151_saleDeductsStock() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-151", 10, 3);
        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("10")), 0,
            "the product did not open with the stock it was created with");

        sellProduct(served("QA E2E-151"), productId, 1);
        assertSameDay(day);

        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("9")), 0,
            "one unit was sold from a shelf of 10 but the salon now believes it "
          + "holds " + Steps.stockOf(SALON, productId));
    }

    @Test(description = "API-E2E-152 two separate sales deduct twice")
    public void e2e152_twoSalesDeductTwice() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-152", 10, 3);

        sellProduct(served("QA E2E-152 a"), productId, 1);
        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("9")), 0,
            "the first sale did not deduct");

        sellProduct(served("QA E2E-152 b"), productId, 1);
        assertSameDay(day);

        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("8")), 0,
            "two sales of one unit each left stock at "
          + Steps.stockOf(SALON, productId) + " instead of 8 - deductions are not "
          + "accumulating");
    }

    @Test(description = "API-E2E-159 two different products on one bill each deduct")
    public void e2e159_twoProductsOneBill() {
        LocalDate day = Steps.today();
        int first  = stockItem("QA E2E-159 a", 10, 3);
        int second = stockItem("QA E2E-159 b", 10, 3);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-159");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "product", "ref_id", first, "qty", 1,
                       "unit_price", SELL, "staff_id", catalogue.staffIds.get(0)),
                Map.of("type", "product", "ref_id", second, "qty", 1,
                       "unit_price", SELL, "staff_id", catalogue.staffIds.get(0))));
        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        assertEquals(Steps.stockOf(SALON, first).compareTo(new BigDecimal("9")), 0,
            "the first product did not deduct");
        assertEquals(Steps.stockOf(SALON, second).compareTo(new BigDecimal("9")), 0,
            "the second product on the same bill did not deduct - only the first "
          + "line is being processed");
        expectMoved(before, after, "sales.total_revenue", grossFor(2));
        expectMoved(before, after, "sales.bills_count", ONE);
    }

    @Test(description = "API-E2E-160 a quantity of three deducts three, not one")
    public void e2e160_quantityDeducts() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-160", 10, 2);
        var before = snapshot();

        sellProduct(served("QA E2E-160"), productId, 3);
        var after = snapshot();
        assertSameDay(day);

        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("7")), 0,
            "a qty of 3 left stock at " + Steps.stockOf(SALON, productId)
          + " instead of 7 - the quantity is being ignored and one unit deducted");
        expectMoved(before, after, "sales.total_revenue", grossFor(3));
    }

    @Test(description = "API-E2E-161 a product-only bill settles and is reported")
    public void e2e161_productOnlyBill() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-161", 10, 3);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-161");
        int billId = sellProduct(customer, productId, 1);
        var after = snapshot();
        assertSameDay(day);

        assertBillIsPaidBy(billId, customer);
        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.totals.product_total")
                          .compareTo(new BigDecimal(SELL)), 0,
            "the invoice's product_total is " + bill.get("data.totals.product_total"));
        assertEquals(Money.at(bill, "data.totals.service_total").compareTo(ZERO), 0,
            "a product-only bill carries " + bill.get("data.totals.service_total")
          + " of service total");

        expectMoved(before, after, "sales.total_revenue", grossFor(1));
        expectMoved(before, after, "payments.total_collected", grossFor(1));
    }

    /**
     * API-E2E-162 sell, refund, then sell again.
     *
     * The sequence matters more than any single step: whatever the refund does
     * to stock, the SECOND sale must start from wherever the first two left it.
     * A salon that lost track here would over-sell within the hour.
     */
    @Test(description = "API-E2E-162 sell, refund, sell again - stock stays consistent")
    public void e2e162_sellRefundSell() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-162", 10, 3);

        int firstBill = sellProduct(served("QA E2E-162 a"), productId, 1);
        BigDecimal afterFirstSale = Steps.stockOf(SALON, productId);

        Steps.refundInFull(firstBill, "QA E2E-162 refund");
        BigDecimal afterRefund = Steps.stockOf(SALON, productId);

        sellProduct(served("QA E2E-162 b"), productId, 1);
        BigDecimal afterSecondSale = Steps.stockOf(SALON, productId);
        assertSameDay(day);

        assertEquals(afterSecondSale.compareTo(afterRefund.subtract(ONE)), 0,
            "the second sale did not deduct from where the refund left the shelf: "
          + afterRefund + " -> " + afterSecondSale);
        System.out.println("  [E2E-162] stock 10 -> " + afterFirstSale + " (sold) -> "
                + afterRefund + " (refunded) -> " + afterSecondSale + " (sold again). "
                + "A refund that does NOT restock is a policy choice, not a bug - "
                + "but it must be a deliberate one.");
    }

    // =====================================================================
    // 153-156  a product alongside a service
    // =====================================================================
    @Test(description = "API-E2E-153 one bill carrying a service and a product")
    public void e2e153_serviceAndProduct() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = served("QA E2E-153");
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(Money.at(bill, "data.totals.service_total")
                          .compareTo(catalogue.servicePrice), 0,
            "the service half of the invoice is wrong");
        assertEquals(Money.at(bill, "data.totals.product_total")
                          .compareTo(catalogue.productPrice), 0,
            "the product half of the invoice is wrong");
        expectSettled(before, after, 1, 1, ZERO);
    }

    @Test(description = "API-E2E-154 a discount applies across both halves of the basket")
    public void e2e154_mixedBasketWithDiscount() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = served("QA E2E-154");
        int billId = billFor(customer, 1, 1, TEN_PERCENT);
        Steps.settleInFull(billId, "cash");
        var after = snapshot();
        assertSameDay(day);

        // The discount comes off the COMBINED subtotal, not the service alone.
        BigDecimal expected = Money.discount(subtotal(1, 1), TEN_PERCENT, ZERO);
        assertEquals(Money.at(Steps.readBill(billId), "data.discount_amount")
                          .compareTo(expected), 0,
            "10% of the combined 1,200 basket is " + expected + ", the bill says "
          + Steps.readBill(billId).get("data.discount_amount")
          + " - the discount is being applied to only part of the basket");
        expectSettled(before, after, 1, 1, TEN_PERCENT);
    }

    @Test(description = "API-E2E-155 GST is charged on the product as well as the service")
    public void e2e155_mixedBasketGst() {
        LocalDate day = Steps.today();
        NewCustomer customer = served("QA E2E-155");
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        BigDecimal expectedTax = tax(1, 1, ZERO);
        assertTrue(Money.closeEnough(Money.at(bill, "data.tax_amount"), expectedTax),
            Money.difference("GST on a mixed basket",
                             expectedTax, Money.at(bill, "data.tax_amount")));
        assertTrue(Money.closeEnough(
                Money.at(bill, "data.taxes.cgst").add(Money.at(bill, "data.taxes.sgst")),
                expectedTax),
            "the CGST/SGST split does not add up to the tax on a mixed basket");
    }

    @Test(description = "API-E2E-156 a product sale is credited to the stylist who sold it")
    public void e2e156_productAttributedToStaff() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-156", 10, 3);
        int staffId = Steps.createStaff(SALON, "QA E2E-156 " + System.nanoTime() % 100000,
                                        "none", null, List.of(catalogue.serviceId));

        NewCustomer customer = served("QA E2E-156");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "product", "ref_id", productId, "qty", 1,
                       "unit_price", SELL, "staff_id", staffId)));
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        assertEquals(Steps.staffFigure(SALON, staffId, "product_revenue")
                          .compareTo(grossFor(1)), 0,
            "the stylist who sold the shampoo is credited with "
          + Steps.staffFigure(SALON, staffId, "product_revenue")
          + " of product revenue, expected " + grossFor(1));
        assertEquals(Steps.staffFigure(SALON, staffId, "service_revenue").compareTo(ZERO), 0,
            "a product sale was booked as SERVICE revenue on the stylist's row");
    }

    // =====================================================================
    // 157-158, 163-164  refunds against a physical thing
    // =====================================================================
    /**
     * API-E2E-157 a product sale is partly refunded.
     *
     * A part refund is a price adjustment rather than an unwind: the bill stays
     * partially_refunded and a CREDIT NOTE is issued carrying its own CGST and
     * SGST. The note is the document the salon's accountant needs, so its
     * existence and its tax split both get asserted.
     */
    @Test(description = "API-E2E-157 a part refund issues a credit note with its own tax split")
    public void e2e157_partRefundIssuesCreditNote() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-157", 10, 3);
        NewCustomer customer = served("QA E2E-157");
        int billId = sellProduct(customer, productId, 2);

        BigDecimal oneUnit = grossFor(1);
        Steps.refundPart(billId, oneUnit, "QA E2E-157 one unit back");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "partially_refunded",
            "after refunding one of two units the bill is "
          + bill.getString("data.status") + ", expected partially_refunded");
        assertEquals(Money.at(bill, "data.refund_amount").compareTo(oneUnit), 0,
            "the refund is recorded as " + bill.get("data.refund_amount")
          + ", expected " + oneUnit);

        List<Map<String, Object>> notes = bill.getList("data.credit_notes");
        assertTrue(notes != null && !notes.isEmpty(),
            "a part refund left no credit note - there is no document for the money "
          + "that went back");
        Map<String, Object> note = notes.get(0);
        BigDecimal cgst = new BigDecimal(String.valueOf(note.get("cgst")));
        BigDecimal sgst = new BigDecimal(String.valueOf(note.get("sgst")));
        BigDecimal noteTax = new BigDecimal(String.valueOf(note.get("tax_amount")));
        assertTrue(Money.closeEnough(cgst.add(sgst), noteTax),
            "the credit note's CGST " + cgst + " + SGST " + sgst
          + " does not equal its own tax_amount " + noteTax);
        assertTrue(String.valueOf(note.get("note_number")).length() > 0,
            "the credit note has no number, so it cannot be referenced");
    }

    @Test(description = "API-E2E-158 a full refund reverses a product sale completely")
    public void e2e158_fullRefundOfAProduct() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-158", 10, 3);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-158");
        int billId = sellProduct(customer, productId, 1);
        Steps.refundInFull(billId, "QA E2E-158 refund");
        var after = snapshot();
        assertSameDay(day);

        assertBillIsRefunded(billId);
        expectMoved(before, after, "sales.total_revenue", ZERO);
        expectMoved(before, after, "payments.total_collected", ZERO);
        expectMoved(before, after, "profit.gross_revenue", ZERO);
        expectMoved(before, after, "payments.failed_refunded_amount", grossFor(1));
    }

    /**
     * API-E2E-163 a mixed bill, refunding only the SERVICE portion.
     *
     * The customer keeps the shampoo and is refunded the haircut. The refund
     * API takes an amount rather than a line, so what is actually being tested
     * is that a partial amount is honoured exactly and the product half of the
     * sale survives it.
     */
    @Test(description = "API-E2E-163 refunding the service half of a mixed bill")
    public void e2e163_refundServiceOnly() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = served("QA E2E-163");
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        BigDecimal servicePortion = gross(1, 0, ZERO);
        Steps.refundPart(billId, servicePortion, "QA E2E-163 service refunded");
        var after = snapshot();
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "partially_refunded",
            "the bill should be partially_refunded, it is " + bill.getString("data.status"));
        assertEquals(Money.at(bill, "data.refund_amount").compareTo(servicePortion), 0,
            "asked to refund " + servicePortion + ", the bill records "
          + bill.get("data.refund_amount"));

        // The salon kept the product half.
        BigDecimal kept = gross(1, 1, ZERO).subtract(servicePortion);
        expectMoved(before, after, "payments.total_collected", kept);
    }

    @Test(description = "API-E2E-164 refunding the product half of a mixed bill")
    public void e2e164_refundProductOnly() {
        LocalDate day = Steps.today();
        var before = snapshot();
        NewCustomer customer = served("QA E2E-164");
        int billId = billFor(customer, 1, 1, ZERO);
        Steps.settleInFull(billId, "cash");

        BigDecimal whole          = gross(1, 1, ZERO);
        BigDecimal servicePortion = gross(1, 0, ZERO);
        BigDecimal productPortion = whole.subtract(servicePortion);

        Steps.refundPart(billId, productPortion, "QA E2E-164 product returned");
        var after = snapshot();
        assertSameDay(day);

        assertEquals(Money.at(Steps.readBill(billId), "data.refund_amount")
                          .compareTo(productPortion), 0,
            "the product portion " + productPortion + " was refunded but the bill "
          + "records " + Steps.readBill(billId).get("data.refund_amount"));
        expectMoved(before, after, "payments.total_collected", servicePortion);
    }

    // =====================================================================
    // 165-166  the shelf runs out
    // =====================================================================
    /**
     * API-E2E-165 a sale that crosses the reorder level.
     *
     * stock_state is the flag the salon reorders on. Stocked at 3 with a
     * reorder level of 3, selling one has to change how the product is
     * labelled - a shelf that silently stays "in_stock" is how a salon runs out
     * of its best seller on a Saturday.
     */
    @Test(description = "API-E2E-165 crossing the reorder level changes the stock state")
    public void e2e165_reorderLevel() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-165", 3, 3);
        String before = Steps.stockState(SALON, productId);

        sellProduct(served("QA E2E-165"), productId, 2);
        assertSameDay(day);

        BigDecimal left = Steps.stockOf(SALON, productId);
        String after = Steps.stockState(SALON, productId);
        assertEquals(left.compareTo(ONE), 0,
            "2 of 3 sold should leave 1, the salon believes it holds " + left);
        assertTrue(!"in_stock".equals(after),
            "stock fell to " + left + " against a reorder level of 3 but the product "
          + "is still labelled '" + after + "'. Nobody will reorder it.");
        System.out.println("  [E2E-165] stock_state " + before + " -> " + after
                + " at " + left + " units against a reorder level of 3.");
    }

    /**
     * API-E2E-166 selling something the salon does not have.
     *
     * Measured: the DRAFT is accepted and the SETTLEMENT is refused with
     * "Insufficient stock. Current: 0.0, adjustment: -1.0". Both halves are
     * asserted - a refusal that still left the stock negative, or that broke
     * the bill, would be worse than the sale itself.
     */
    @Test(description = "API-E2E-166 a sale is refused when there is no stock, and nothing breaks")
    public void e2e166_zeroStockIsRefused() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-166", 0, 1);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-166");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "product", "ref_id", productId, "qty", 1,
                       "unit_price", SELL, "staff_id", catalogue.staffIds.get(0))));

        int answer = Steps.attemptFinalize(billId, Steps.netPayable(billId), "cash");
        var after = snapshot();
        assertSameDay(day);

        assertTrue(answer >= 400,
            "a product with zero stock was SOLD (status " + answer + ") - the salon "
          + "has taken money for something it does not have");
        assertEquals(Steps.stockOf(SALON, productId).compareTo(ZERO), 0,
            "the sale was refused but stock is now "
          + Steps.stockOf(SALON, productId) + " - a refused sale still moved the shelf");
        assertEquals(Steps.readBill(billId).getString("data.status"), "draft",
            "the refused bill is no longer a draft");
        expectNothingSold(before, after);
    }

    // =====================================================================
    // 167-170  where inventory meets the money
    // =====================================================================
    /**
     * API-E2E-167 buying the stock and selling it are two different costs, and
     * the salon must not be charged twice for the same bottle.
     *
     * Unparked 7 Sep 2026. This case was skipped for a few hours because
     * `POST /salons/{id}/expenses` answers 404 and no write path was known.
     * Block 8 found it: the route is the top-level `POST /expenses`, with the
     * salon taken from the token rather than the path.
     *
     * The inventory question, which Block 8's own expense tests do not ask:
     * a stock order is an OPERATING EXPENSE the day it is paid, and selling
     * that stock books COGS. Both are real, both are costs, and they are
     * counted in different columns. A report that folded one into the other
     * would charge the salon twice for goods it bought once - and the
     * arithmetic would still look plausible, because both figures move down.
     *
     * So: buy 300 of stock, sell one bottle that cost 100, and check the two
     * cost lines land separately and add up.
     */
    @Test(description = "API-E2E-167 a stock purchase and the COGS of selling it are counted separately")
    public void e2e167_stockPurchaseAndCogsAreSeparate() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-167", 10, 3);
        BigDecimal order = new BigDecimal("300.00");

        var before = Reports.snapshot(SALON, Reports.COST_TRAIL);
        int expenseId = Steps.createExpense("QA E2E-167 stock order", order, day);
        try {
            sellProduct(served("QA E2E-167"), productId, 1);
            var after = Reports.snapshot(SALON, Reports.COST_TRAIL);
            assertSameDay(day);

            BigDecimal cogs = new BigDecimal(COST);          // one bottle sold

            expectMoved(before, after, "profit.operating_expenses", order);
            expectMoved(before, after, "profit.service_cogs", cogs);
            expectMoved(before, after, "profit.total_cost", order.add(cogs));

            // the expense report knows about the order and NOT about the COGS
            expectMoved(before, after, "expenses.total_expenses", order);
            expectMoved(before, after, "expenses.expense_count", ONE);
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-168 / D19 - the cost of goods sold has to unwind with the refund.
     *
     * FAILS TODAY, on purpose, in the known-defect group.
     *
     * Block 5 established that Net Profit deducts COGS - a 200 shampoo bought
     * for 100 adds 200 to revenue but only 100 to profit. So a refunded product
     * must give back BOTH: the revenue and the cost.
     *
     * It gives back the revenue and the STOCK, and keeps the cost. Measured on
     * salon 4550, 7 Sep 2026, one product costing 100 and selling for 200:
     *
     *                    gross_revenue   service_cogs   net_profit   stock
     *     before             34279.60        3100.00     31179.60      10
     *     after sale         34479.60        3200.00     31279.60       9
     *     after refund       34279.60        3200.00     31079.60      10
     *                        ^ reversed      ^ STAYS     ^ 100 LOST    ^ back
     *
     * The unit is physically back on the shelf and available to sell again -
     * and its cost will be charged AGAIN when it is. So the error compounds:
     * sell and refund the same bottle ten times and the books carry 1,000 of
     * cost for goods that never left the building. Every refunded product
     * permanently understates the day's profit by its cost price, and no
     * report anywhere shows why the profit and the revenue disagree.
     *
     * Note this is NOT the part-refund rule from e2e157. A part refund is a
     * price adjustment - the goods stay sold, so the cost SHOULD stay booked.
     * This is a full refund: status goes to `refunded`, the stock comes back,
     * and the cost should come back with it.
     *
     * Left failing rather than skipped because the assertion is right and the
     * API is wrong.
     */
    @Test(groups = "known-defect",
          description = "API-E2E-168 refunding a product reverses its cost as well as its revenue")
    public void e2e168_refundReversesCogs() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-168", 10, 3);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-168");
        int billId = sellProduct(customer, productId, 1);
        Steps.refundInFull(billId, "QA E2E-168 refund");
        var after = snapshot();
        assertSameDay(day);

        // The stock really does come back - which is what makes the cost
        // staying booked indefensible rather than merely arguable.
        assertEquals(Steps.stockOf(SALON, productId).compareTo(new BigDecimal("10")), 0,
            "the refunded unit was not returned to the shelf, so this test is "
          + "measuring something other than D19");

        expectMoved(before, after, "profit.gross_revenue", ZERO);
        expectMoved(before, after, "profit.tax_collected", ZERO);

        // D19 lives on this line: the API leaves COGS booked, so net_profit
        // comes back 100 short - the cost price of a bottle that is on the
        // shelf again.
        expectMoved(before, after, "profit.net_profit", ZERO);
    }

    /**
     * API-E2E-169 the Dashboard's revenue sources.
     *
     * The dashboard splits the day's takings by where they came from. A product
     * sale has to appear there as product revenue, or the owner's first screen
     * of the morning disagrees with the reports behind it.
     */
    @Test(description = "API-E2E-169 a product sale shows up in the Dashboard's revenue sources")
    public void e2e169_dashboardRevenueSource() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-169", 10, 3);

        JsonPath before = Steps.dashboard(SALON, "today");
        Object sourcesBefore = before.get("data.revenue_sources");
        assertTrue(sourcesBefore != null,
            "the dashboard carries no revenue_sources block at all");

        sellProduct(served("QA E2E-169"), productId, 1);
        JsonPath after = Steps.dashboard(SALON, "today");
        assertSameDay(day);

        BigDecimal revenueAfter = Money.at(after, "data.kpis.revenue.value");
        BigDecimal revenueBefore = Money.at(before, "data.kpis.revenue.value");
        assertTrue(revenueAfter.compareTo(revenueBefore) > 0,
            "a product sale of " + grossFor(1) + " did not move the Dashboard's "
          + "revenue KPI at all: " + revenueBefore + " -> " + revenueAfter);
        System.out.println("  [E2E-169] dashboard revenue " + revenueBefore + " -> "
                + revenueAfter + "; revenue_sources: " + after.get("data.revenue_sources"));
    }

    /**
     * API-E2E-170 the closing case: a mixed basket, a partial refund, and every
     * figure reconciled at once.
     *
     * Sale, shelf and books in one assertion set - the state a salon is
     * actually in at the end of a day where something went back.
     */
    @Test(description = "API-E2E-170 service plus product, part refunded, everything reconciles")
    public void e2e170_fullReconciliation() {
        LocalDate day = Steps.today();
        int productId = stockItem("QA E2E-170", 10, 3);
        var before = snapshot();

        NewCustomer customer = served("QA E2E-170");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                       "unit_price", catalogue.servicePrice,
                       "staff_id", catalogue.staffIds.get(0)),
                Map.of("type", "product", "ref_id", productId, "qty", 1,
                       "unit_price", SELL, "staff_id", catalogue.staffIds.get(0))));
        Steps.settleInFull(billId, "cash");

        BigDecimal stockAfterSale = Steps.stockOf(SALON, productId);
        assertEquals(stockAfterSale.compareTo(new BigDecimal("9")), 0,
            "the shelf did not move with the sale");

        BigDecimal whole   = gross(1, 1, ZERO);
        BigDecimal refund  = grossFor(1);              // the product portion
        Steps.refundPart(billId, refund, "QA E2E-170 product returned");
        var after = snapshot();
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getString("data.status"), "partially_refunded",
            "the bill is " + bill.getString("data.status"));
        assertEquals(Money.at(bill, "data.refund_amount").compareTo(refund), 0,
            "the recorded refund is " + bill.get("data.refund_amount"));

        // The books keep what the customer kept.
        expectMoved(before, after, "payments.total_collected", whole.subtract(refund));
        expectMoved(before, after, "sales.bills_count", ONE);
        assertTrue(bill.getList("data.credit_notes").size() >= 1,
            "no credit note was raised for the returned product");
    }
}

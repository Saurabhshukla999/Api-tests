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
 * API-E2E-171 .. 180 - expenses and the profit they eat.
 *
 * Every block before this one asked what a SALE does to the books. This one
 * asks about the other side of the ledger: the rent, the stock order, the
 * electricity bill. The salon owner's Net Profit is the number they actually
 * care about, and it is the only figure in the system that both sides feed.
 *
 * Measured before writing, 7 Sep 2026, because the write route is not where
 * you would look for it:
 *
 *   route        POST /expenses          - top level, NOT /salons/{id}/expenses
 *                                          (that answers 404)
 *   salon        from the TOKEN, via Salon.find_by_user_id(@current_user.id)
 *   body         flat or nested under "expense"; both accepted
 *   response     200 with data.expense.id
 *   PUT /expenses/{id}    replaces the record; profit follows the new amount
 *   DELETE /expenses/{id} reverses it exactly
 *
 * And the one that decides half the assertions in this file:
 *
 *   expense_type is Credit or Debit, and only DEBIT counts as an operating
 *   expense. Measured: a Credit of 777 moved Operating Expenses by 0.00.
 *
 * That is not a detail. The application auto-writes a CREDIT row for the
 * service price every time an appointment completes - salon 4550's ledger
 * carries 160,000 of them against 0 of real cost. A profit report that summed
 * the ledger without filtering would show this salon spending its entire
 * revenue on nothing. API-E2E-175 is the test that holds that line.
 *
 * The model, confirmed end to end:
 *
 *     total_cost = operating_expenses + service_cogs
 *     net_profit = gross_revenue - total_cost
 *
 * Every test here cleans up after itself in a finally block. An expense is
 * salon-wide and dated, so one left behind would silently shift the profit
 * figures of every test that ran after it - including the ones in other blocks.
 */
public class Block8ExpenseTest extends BaseJourneyTest {

    private static final BigDecimal RENT = new BigDecimal("500.00");
    private static final BigDecimal POWER = new BigDecimal("250.00");

    /** Profit + Expenses only. An expense cannot move Sales or Payments. */
    private Map<String, BigDecimal> costs() {
        return Reports.snapshot(SALON, Reports.COST_TRAIL);
    }

    private String label(String test) {
        return "QA " + test + " " + System.nanoTime() % 1000000;
    }

    // =====================================================================
    // 171-174  an expense is a cost, and it is reversible
    // =====================================================================
    /**
     * API-E2E-171 the one that matters: an expense reduces Net Profit by
     * exactly its own amount, and touches nothing else.
     *
     * It must move Operating Expenses and Total Cost UP by the amount and Net
     * Profit DOWN by the same, while leaving Gross Revenue and COGS alone. An
     * expense is not a sale and not a cost of goods; if it leaked into either,
     * the owner's revenue figure would be wrong for a reason no report shows.
     */
    @Test(description = "API-E2E-171 an expense reduces net profit by exactly its amount")
    public void e2e171_expenseReducesProfit() {
        LocalDate day = Steps.today();
        var before = costs();
        int expenseId = Steps.createExpense(label("E2E-171 rent"), RENT, day);
        try {
            var after = costs();
            assertSameDay(day);

            expectMoved(before, after, "profit.operating_expenses", RENT);
            expectMoved(before, after, "profit.total_cost", RENT);
            expectMoved(before, after, "profit.net_profit", RENT.negate());

            // the sale side is untouched
            expectMoved(before, after, "profit.gross_revenue", ZERO);
            expectMoved(before, after, "profit.service_cogs", ZERO);
            expectMoved(before, after, "profit.tax_collected", ZERO);
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    @Test(description = "API-E2E-172 two expenses accumulate rather than replace")
    public void e2e172_twoExpensesAccumulate() {
        LocalDate day = Steps.today();
        var before = costs();
        int first = Steps.createExpense(label("E2E-172 rent"), RENT, day);
        int second = 0;
        try {
            second = Steps.createExpense(label("E2E-172 power"), POWER, day);
            var after = costs();
            assertSameDay(day);

            BigDecimal both = RENT.add(POWER);
            expectMoved(before, after, "profit.operating_expenses", both);
            expectMoved(before, after, "profit.net_profit", both.negate());
            expectMoved(before, after, "expenses.expense_count", new BigDecimal("2"));
        } finally {
            if (second > 0) Steps.deleteExpense(second);
            Steps.deleteExpense(first);
        }
    }

    /**
     * API-E2E-173 deleting an expense gives the profit back, to the paisa.
     *
     * Written as a round trip on purpose: the whole point is that the books
     * return to exactly where they started, not merely somewhere near.
     */
    @Test(description = "API-E2E-173 deleting an expense reverses it completely")
    public void e2e173_deleteReversesTheExpense() {
        LocalDate day = Steps.today();
        var before = costs();

        int expenseId = Steps.createExpense(label("E2E-173 stock order"), RENT, day);
        var during = costs();
        expectMoved(before, during, "profit.operating_expenses", RENT);

        Steps.deleteExpense(expenseId);
        var after = costs();
        assertSameDay(day);

        expectMoved(before, after, "profit.operating_expenses", ZERO);
        expectMoved(before, after, "profit.total_cost", ZERO);
        expectMoved(before, after, "profit.net_profit", ZERO);
        expectMoved(before, after, "expenses.total_expenses", ZERO);
        expectMoved(before, after, "expenses.expense_count", ZERO);

        assertNull(Steps.expenseRow(SALON, expenseId),
            "expense " + expenseId + " was deleted but is still on the salon's "
          + "ledger - the reports agreeing it is gone is not the same as it "
          + "being gone");
    }

    /**
     * API-E2E-174 the Expense report and the Profit report agree.
     *
     * Two different queries over the same rows: Reports::ExpensesQuery sums the
     * ledger, Reports::ProfitQuery subtracts it from revenue. They are computed
     * independently, so they can disagree - and if they ever do, the owner sees
     * one number on the expense screen and a different one in their P&L.
     */
    @Test(description = "API-E2E-174 the expense report and the profit report agree on the same expense")
    public void e2e174_expenseReportMatchesProfit() {
        LocalDate day = Steps.today();
        var before = costs();
        int expenseId = Steps.createExpense(label("E2E-174 supplies"), POWER, day);
        try {
            var after = costs();
            assertSameDay(day);

            expectMoved(before, after, "expenses.total_expenses", POWER);
            expectMoved(before, after, "expenses.expense_count", ONE);
            expectMoved(before, after, "profit.operating_expenses", POWER);

            BigDecimal onExpenseReport = Reports.moved(before, after, "expenses.total_expenses");
            BigDecimal onProfitReport  = Reports.moved(before, after, "profit.operating_expenses");
            assertEquals(onExpenseReport.compareTo(onProfitReport), 0,
                "the Expense report says the salon spent " + onExpenseReport
              + " and the Profit report says " + onProfitReport
              + " - same expense, two answers");

            Map<String, Object> row = Steps.expenseRow(SALON, expenseId);
            assertNotNull(row, "the expense is in both reports but not on the ledger");
            assertEquals(new BigDecimal(row.get("amount").toString()).compareTo(POWER), 0,
                "the ledger row carries a different amount from the one written");
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    // =====================================================================
    // 175-177  what is NOT an operating expense
    // =====================================================================
    /**
     * API-E2E-175 a CREDIT row is income, and must not be counted as a cost.
     *
     * The most valuable test in the block, because the thing it guards against
     * is already sitting in the data. Every completed appointment auto-writes a
     * Credit expense row for the service price:
     *
     *     {name: "Appointment", expense_type: "Credit", amount: 1000.0,
     *      description: "QA Haircut", date: today}
     *
     * Salon 4550's ledger reports total_credits 160,000 against total_debits 0.
     * If Operating Expenses ever stopped filtering on expense_type, this salon
     * would instantly appear to have spent 160,000 it never spent, and Net
     * Profit would go deeply negative on a day it made money.
     *
     * Measured 7 Sep 2026: a Credit of 777 moved Operating Expenses by 0.00.
     * This test pins that behaviour so a future change to the query cannot
     * quietly undo it.
     */
    @Test(description = "API-E2E-175 a Credit row is income and is not counted as an operating expense")
    public void e2e175_creditIsNotAnExpense() {
        LocalDate day = Steps.today();
        var before = costs();
        BigDecimal income = new BigDecimal("777.00");
        int expenseId = Steps.createExpense(label("E2E-175 income"), "Credit", income, day, null);
        try {
            var after = costs();
            assertSameDay(day);

            expectMoved(before, after, "profit.operating_expenses", ZERO);
            expectMoved(before, after, "profit.total_cost", ZERO);
            expectMoved(before, after, "profit.net_profit", ZERO);

            // It IS on the ledger - it was written, it is simply not a cost.
            Map<String, Object> row = Steps.expenseRow(SALON, expenseId);
            assertNotNull(row, "the Credit row was accepted but never reached the ledger");
            assertEquals(row.get("expense_type"), "Credit",
                "the row came back as a " + row.get("expense_type")
              + " - a Credit that is stored as a Debit is a cost the salon "
              + "never incurred");
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-176 an expense dated yesterday stays in yesterday.
     *
     * The range boundary, from the cost side. Block 4 proved the report window
     * is a calendar day in the salon's timezone; this proves the expense query
     * honours the same window rather than filtering on created_at, which is
     * what a backdated entry would expose.
     */
    @Test(description = "API-E2E-176 a backdated expense does not touch today's profit")
    public void e2e176_backdatedExpenseStaysInThePast() {
        LocalDate day = Steps.today();
        LocalDate yesterday = day.minusDays(1);
        var before = costs();
        int expenseId = Steps.createExpense(label("E2E-176 yesterday"), RENT, yesterday);
        try {
            var after = costs();
            assertSameDay(day);

            expectMoved(before, after, "profit.operating_expenses", ZERO);
            expectMoved(before, after, "profit.net_profit", ZERO);
            expectMoved(before, after, "expenses.total_expenses", ZERO);

            Map<String, Object> row = Steps.expenseRow(SALON, expenseId);
            assertNotNull(row, "the backdated expense was not saved at all");
            assertEquals(row.get("date"), yesterday.toString(),
                "the expense was written for " + yesterday + " but stored as "
              + row.get("date") + " - a backdated entry that silently becomes "
              + "today would move a closed day's profit");
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-177 changing the amount moves profit by the difference, not by
     * the new amount.
     *
     * A PUT replaces the record. The risk this covers is a report that adds the
     * new figure without removing the old one - the classic shape of a
     * double-counting bug, and invisible unless you assert the delta.
     */
    @Test(description = "API-E2E-177 editing an expense moves profit by the difference only")
    public void e2e177_editMovesByTheDifference() {
        LocalDate day = Steps.today();
        String name = label("E2E-177 revised");
        var before = costs();
        int expenseId = Steps.createExpense(name, new BigDecimal("100.00"), day);
        try {
            var afterCreate = costs();
            expectMoved(before, afterCreate, "profit.operating_expenses", new BigDecimal("100.00"));

            Steps.updateExpense(expenseId, name, "Debit", new BigDecimal("400.00"), day);
            var afterEdit = costs();
            assertSameDay(day);

            // 400 in total, not 500 - the 100 must have been taken back out.
            expectMoved(before, afterEdit, "profit.operating_expenses", new BigDecimal("400.00"));
            expectMoved(before, afterEdit, "profit.net_profit", new BigDecimal("-400.00"));
            expectMoved(before, afterEdit, "expenses.expense_count", ONE);
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    // =====================================================================
    // 178-180  both sides of the ledger at once
    // =====================================================================
    /**
     * API-E2E-178 a sale and an expense on the same day, and the whole P&L
     * identity holds.
     *
     * The first test in the suite where revenue and cost both move in one
     * journey. It checks the identity the report is built on rather than the
     * individual figures:
     *
     *     total_cost = operating_expenses + service_cogs
     *     net_profit = gross_revenue - total_cost
     */
    @Test(description = "API-E2E-178 a sale and an expense together, and the P&L identity holds")
    public void e2e178_saleAndExpenseReconcile() {
        LocalDate day = Steps.today();
        var before = costs();
        int expenseId = Steps.createExpense(label("E2E-178 rent"), RENT, day);
        try {
            NewCustomer customer = arriveAsEnquiry("QA E2E-178");
            completeAppointmentFor(customer);
            int billId = billFor(customer, 1, 1, ZERO);
            Steps.settleInFull(billId, "cash");
            var after = costs();
            assertSameDay(day);

            BigDecimal cogs = catalogue.productCost;                  // one product
            BigDecimal revenue = Money.round(
                    gross(1, 1, ZERO).subtract(tax(1, 1, ZERO)));     // net_payable - tax

            expectMoved(before, after, "profit.operating_expenses", RENT);
            expectMoved(before, after, "profit.service_cogs", cogs);
            expectMoved(before, after, "profit.total_cost", RENT.add(cogs));
            expectMoved(before, after, "profit.gross_revenue", revenue);
            expectMoved(before, after, "profit.net_profit",
                        Money.round(revenue.subtract(RENT).subtract(cogs)));

            // and the identity itself, on the absolute figures rather than the
            // deltas - the report must be internally consistent at rest too
            assertIdentityHolds(after);
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-179 the Dashboard's expense panel agrees with the Expense report.
     *
     * Dashboard::ExpensesController is a third query over the same rows, and it
     * is the one the owner sees first thing in the morning. Three independent
     * queries mean three chances to disagree.
     */
    @Test(description = "API-E2E-179 the dashboard's expense panel agrees with the expense report")
    public void e2e179_dashboardAgreesWithTheReport() {
        LocalDate day = Steps.today();
        var before = costs();
        JsonPath panelBefore = Steps.dashboardExpenses(SALON, "today");
        int expenseId = Steps.createExpense(label("E2E-179 laundry"), POWER, day);
        try {
            var after = costs();
            JsonPath panelAfter = Steps.dashboardExpenses(SALON, "today");
            assertSameDay(day);

            expectMoved(before, after, "expenses.total_expenses", POWER);

            BigDecimal panelMoved = Money.round(
                    dashboardTotal(panelAfter).subtract(dashboardTotal(panelBefore)));
            assertEquals(panelMoved.compareTo(POWER), 0,
                "the Expense report recorded " + POWER + " of spend and the "
              + "Dashboard's expense panel recorded " + panelMoved
              + " - the owner's first screen of the morning disagrees with the "
              + "report behind it");
        } finally {
            Steps.deleteExpense(expenseId);
        }
    }

    /**
     * API-E2E-180 the closing case for the cost side: a full day in one test.
     *
     * Two customers, a service, a product, two expenses and a refund - then
     * every profit figure reconciled at once against numbers computed here.
     * This is the state a small salon is actually in at close of business.
     */
    @Test(description = "API-E2E-180 a full trading day - sales, a product, expenses and a refund")
    public void e2e180_fullTradingDay() {
        LocalDate day = Steps.today();
        var before = costs();
        int rent = Steps.createExpense(label("E2E-180 rent"), RENT, day);
        int power = 0;
        try {
            power = Steps.createExpense(label("E2E-180 power"), POWER, day);

            // customer one: service and product, kept
            NewCustomer kept = arriveAsEnquiry("QA E2E-180 kept");
            completeAppointmentFor(kept);
            int keptBill = billFor(kept, 1, 1, ZERO);
            Steps.settleInFull(keptBill, "cash");

            // customer two: a service, paid and then fully refunded
            NewCustomer returned = arriveAsEnquiry("QA E2E-180 returned");
            completeAppointmentFor(returned);
            int returnedBill = billFor(returned, 1, 0, ZERO);
            Steps.settleInFull(returnedBill, "cash");
            Steps.refundInFull(returnedBill, "QA E2E-180 refund");

            var after = costs();
            assertSameDay(day);

            BigDecimal expenses = RENT.add(POWER);
            BigDecimal cogs = catalogue.productCost;
            BigDecimal revenue = Money.round(
                    gross(1, 1, ZERO).subtract(tax(1, 1, ZERO)));

            expectMoved(before, after, "profit.operating_expenses", expenses);
            expectMoved(before, after, "profit.gross_revenue", revenue);
            expectMoved(before, after, "profit.service_cogs", cogs);
            expectMoved(before, after, "profit.total_cost", expenses.add(cogs));
            expectMoved(before, after, "profit.net_profit",
                        Money.round(revenue.subtract(expenses).subtract(cogs)));
            expectMoved(before, after, "expenses.expense_count", new BigDecimal("2"));

            assertIdentityHolds(after);
        } finally {
            if (power > 0) Steps.deleteExpense(power);
            Steps.deleteExpense(rent);
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================
    /**
     * The report has to add up to itself, on absolute figures, not just move
     * correctly. Deltas can be right while the totals are wrong.
     */
    private void assertIdentityHolds(Map<String, BigDecimal> now) {
        BigDecimal opex = now.get("profit.operating_expenses");
        BigDecimal cogs = now.get("profit.service_cogs");
        BigDecimal cost = now.get("profit.total_cost");
        BigDecimal gross = now.get("profit.gross_revenue");
        BigDecimal net = now.get("profit.net_profit");

        assertTrue(Money.closeEnough(cost, Money.round(opex.add(cogs))),
            "Total Cost is " + cost + " but Operating Expenses (" + opex
          + ") plus COGS (" + cogs + ") is " + Money.round(opex.add(cogs)));

        assertTrue(Money.closeEnough(net, Money.round(gross.subtract(cost))),
            "Net Profit is " + net + " but Gross Revenue (" + gross
          + ") minus Total Cost (" + cost + ") is " + Money.round(gross.subtract(cost)));
    }

    /**
     * The dashboard panel's total for the range.
     *
     * Its shape is its own: `data.summary.total.value`, with a sibling `count`,
     * a `monthly_total`, a `pending_approvals` and a `recent` list. The
     * alternatives below are kept because the panel is the least stable of the
     * three expense surfaces and this is the assertion most likely to break on
     * a rename - better to find the total than to fail on a shape change.
     *
     * Worth knowing: `recent` is NOT range-filtered. It returned an expense
     * from 30 July alongside today's. Only `summary.total` respects the range.
     */
    private BigDecimal dashboardTotal(JsonPath panel) {
        for (String path : List.of("data.summary.total.value",
                                   "data.kpis.total_expenses.value",
                                   "data.total_expenses",
                                   "data.kpis.total.value",
                                   "data.summary.total_expenses")) {
            Object value = panel.get(path);
            if (value != null) {
                return new BigDecimal(value.toString());
            }
        }
        throw new AssertionError(
            "the dashboard expense panel carries no total in any known shape; "
          + "body was: " + panel.prettify());
    }
}

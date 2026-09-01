package com.nearz.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The billing arithmetic, worked out here rather than read back from the API.
 *
 * This is the whole point of the exercise. Asserting that the API's total
 * equals the API's total proves nothing; asserting that it equals a number we
 * computed independently is a test. Confirmed against the live QA API:
 *
 *     lineTotal = unitPrice x qty x (1 - lineDiscountPct/100)
 *     subtotal  = sum(lineTotal)
 *     discount  = min(subtotal, subtotal x cartPct/100 + cartFlat)
 *     taxable   = subtotal - discount
 *     tax       = taxable x gstRate
 *     gross     = taxable + tax          <- what the customer pays
 *
 * Always BigDecimal, never double. 0.1 + 0.2 is not 0.3 in floating point, and
 * a rupee test that rounds its own way cannot tell you the API rounded wrong.
 */
public final class Money {

    /** Compared to the paisa. Anything looser hides real rounding faults. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private Money() { }

    public static BigDecimal of(String amount) {
        return new BigDecimal(amount);
    }

    public static BigDecimal of(Number amount) {
        return amount == null ? BigDecimal.ZERO
                              : new BigDecimal(amount.toString());
    }

    /** Two decimal places, half-up, the way an invoice rounds. */
    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Tax on a taxable amount. rate is a fraction: 0.18 for 18%. */
    public static BigDecimal tax(BigDecimal taxable, BigDecimal rate) {
        return round(taxable.multiply(rate));
    }

    /** Taxable plus its tax, before rounding. */
    public static BigDecimal gross(BigDecimal taxable, BigDecimal rate) {
        return round(taxable.add(tax(taxable, rate)));
    }

    /**
     * What the customer actually pays.
     *
     * Bills settle in WHOLE RUPEES when the salon has round-off on, which is
     * the default. TotalsCalculator puts it plainly: "the paise are absorbed
     * into round_off so the invoice reconciles: gross_payable + round_off ==
     * net_payable, always."
     *
     * This was missed until 28 Aug 2026 because every basket up to then landed
     * on a whole rupee by luck - a 1,000 service at 18% is exactly 1,180. The
     * moment a 90-rupee product joined it, 1,090 x 1.18 = 1,286.20 rounded to
     * 1,286 and four tests failed by twenty paise. The tests were wrong, not
     * the API.
     */
    public static BigDecimal netPayable(BigDecimal taxable, BigDecimal rate,
                                        boolean roundOffEnabled) {
        BigDecimal gross = gross(taxable, rate);
        return roundOffEnabled
                ? gross.setScale(0, RoundingMode.HALF_UP).setScale(2)
                : gross;
    }

    /** A cart discount can empty a bill but never invert it. */
    public static BigDecimal discount(BigDecimal subtotal, BigDecimal pct, BigDecimal flat) {
        BigDecimal raw = subtotal.multiply(pct).divide(new BigDecimal("100"))
                                 .add(flat);
        return round(raw.min(subtotal));
    }

    /**
     * Read a money field out of a response, treating a missing or null field
     * as zero.
     *
     * getDouble() throws a NullPointerException on a null field, and several
     * of these are legitimately null: refund_amount on a bill that was never
     * refunded, refunded_at before any reversal. "Never refunded" and
     * "refunded nothing" are the same number here, and a test should say so
     * rather than crash.
     */
    public static BigDecimal at(io.restassured.path.json.JsonPath body, String path) {
        Object value = body.get(path);
        return value == null ? BigDecimal.ZERO : of(value.toString());
    }

    public static boolean closeEnough(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    /** Readable failure text: what we expected, what the API said, the gap. */
    public static String difference(String label, BigDecimal expected, BigDecimal actual) {
        return String.format("%n  %s%n      expected %12s%n      API said %12s%n      out by   %12s",
                label, round(expected), round(actual), round(actual.subtract(expected)));
    }
}

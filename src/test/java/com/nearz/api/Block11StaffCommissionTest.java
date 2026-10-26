package com.nearz.api;

import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-201 .. 216 - staff commission, including the shared-service split.
 *
 * The money question this block answers: when a service is sold by one person,
 * or split 50/50 between two, does each stylist get paid on their own share at
 * their own rate - once - and does a product line stay theirs alone?
 *
 * Everything below was measured against the live QA API on 26 Oct 2026, and
 * every expected figure is computed here from the catalogue price, the share
 * and the rate; nothing is read back from the response it is checking.
 *
 * Confirmed contract:
 *
 *   config        staff carry ONE effective-dated commission object:
 *                 { service_pct, product_pct, membership_pct, effective_from? }
 *                 All three percentages are required together, 0..100. A past
 *                 or omitted effective_from applies today; a future one lands
 *                 in `scheduled` and leaves `current` alone. `history` keeps
 *                 every entry. The legacy commission_type/commission_value pair
 *                 still works and cannot be sent with the object (400).
 *
 *   sharing       a SERVICE line takes `staff: [{staff_id, share}, ...]`.
 *                 Shares are percentages that must total exactly 100, each
 *                 greater than 0, each person once, each from this salon.
 *                 More than two people are accepted. A product line can only
 *                 name one person ("Only a service can be shared between
 *                 staff"). An empty array is accepted and leaves the line
 *                 unattributed - no commission, nobody credited.
 *
 *   arithmetic    per staff: round(sum(share% x line net-of-GST), 2) x their
 *                 own rate. GST is never commissionable; line and bill
 *                 discounts are taken off before the share; a refund scales the
 *                 base down by the unrefunded ratio. Rounding is applied to the
 *                 summed base, NOT per bill (two 45.495 contributions report
 *                 90.99, not 91.00) - see API-E2E-215.
 *
 *   settlement    the rate is frozen when the bill settles: changing a stylist's
 *                 percentage does not re-price a closed bill, and only the next
 *                 sale uses the new rate. Refunding a settled bill reduces the
 *                 payout; re-finalizing answers 409 and a refund larger than the
 *                 bill answers 422.
 *
 *   shape         a line with ONE staff returns `staff` as an object and a line
 *                 with two returns an array. Both shapes are read defensively
 *                 below rather than assumed.
 */
public class Block11StaffCommissionTest extends BaseJourneyTest {

    private static final BigDecimal FIFTEEN = new BigDecimal("15");
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** QA Stylist one: 15% service, 5% product. */
    private int one;
    /** QA Stylist two: 15% service, 15% product. */
    private int two;

    private String oneName;
    private String twoName;

    @BeforeClass(alwaysRun = true)
    public void hireCommissionPair() {
        oneName = "QA COMM one " + System.nanoTime() % 100000;
        twoName = "QA COMM two " + System.nanoTime() % 100000;
        one = Steps.createStaffWithCommission(SALON, oneName, FIFTEEN, FIVE);
        two = Steps.createStaffWithCommission(SALON, twoName, FIFTEEN, FIFTEEN);
    }

    // =====================================================================
    // helpers
    // =====================================================================
    /** One entry of a line's `staff` array. */
    private static Map<String, Object> share(int staffId, String pct) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("staff_id", staffId);
        entry.put("share", new BigDecimal(pct));
        return entry;
    }

    private Map<String, Object> serviceLine(List<Map<String, Object>> staffShares) {
        return serviceLine(staffShares, BigDecimal.ONE);
    }

    private Map<String, Object> serviceLine(List<Map<String, Object>> staffShares, BigDecimal qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "service");
        line.put("ref_id", catalogue.serviceId);
        line.put("qty", qty);
        line.put("unit_price", catalogue.servicePrice);
        line.put("staff", staffShares);
        return line;
    }

    private Map<String, Object> productLine(int staffId) {
        return productLine(staffId, BigDecimal.ONE);
    }

    private Map<String, Object> productLine(int staffId, BigDecimal qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "product");
        line.put("ref_id", catalogue.productId);
        line.put("qty", qty);
        line.put("unit_price", catalogue.productPrice);
        line.put("staff_id", staffId);
        return line;
    }

    /**
     * Open a draft bill and return its id.
     *
     * One retry is allowed, and only for the roster-lag refusal: a staff member
     * created seconds earlier can briefly be missing from the billing lookup
     * ("Choose staff from this salon") even though the roster already lists
     * them. Observed on 26 Oct 2026; it clears on its own. Any other refusal is
     * a real failure, and a second refusal is not retried again.
     */
    private int openBill(NewCustomer customer, List<Map<String, Object>> items,
                         BigDecimal discountPct, Boolean gstEnabled) {
        Response response = Steps.attemptBill(customer.id(), items, discountPct, gstEnabled);
        if (response.statusCode() == 422
                && String.valueOf(response.jsonPath().getString("error"))
                         .contains("Choose staff from this salon")) {
            sleep(2000);
            response = Steps.attemptBill(customer.id(), items, discountPct, gstEnabled);
        }
        assertEquals(response.statusCode(), 201,
            "the bill was refused: " + response.statusCode() + " "
          + Steps.redacted(response) + "\n  items: " + items);
        return response.jsonPath().getInt("data.id");
    }

    private int sell(NewCustomer customer, List<Map<String, Object>> items,
                     BigDecimal discountPct, Boolean gstEnabled) {
        int billId = openBill(customer, items, discountPct, gstEnabled);
        Steps.settleInFull(billId, "cash");
        return billId;
    }

    private BigDecimal row(int staffId, String field) {
        return Steps.staffFigure(SALON, staffId, field);
    }

    private void movedBy(String what, BigDecimal before, BigDecimal after,
                         BigDecimal expected) {
        BigDecimal delta = after.subtract(before);
        assertEquals(delta.compareTo(expected), 0,
            what + " moved " + delta + ", expected " + expected
          + " (before " + before + ", after " + after + ")");
    }

    /** Commission on a share of a commissionable base, as the API computes it. */
    private static BigDecimal commissionOn(BigDecimal base, String sharePct,
                                           BigDecimal rate) {
        BigDecimal staffBase = base.multiply(new BigDecimal(sharePct))
                                   .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return Money.round(staffBase.multiply(rate).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    /** The line's staff entries, whatever shape the serializer chose. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> staffOnLine(int billId, int index) {
        Object raw = Steps.readBill(billId).get("data.items[" + index + "].staff");
        List<Map<String, Object>> entries = new ArrayList<>();
        if (raw instanceof List) {
            entries.addAll((List<Map<String, Object>>) raw);
        } else if (raw instanceof Map) {
            entries.add((Map<String, Object>) raw);
        }
        return entries;
    }

    private BigDecimal shareOf(int billId, int lineIndex, int staffId) {
        for (Map<String, Object> entry : staffOnLine(billId, lineIndex)) {
            if (String.valueOf(entry.get("staff_id")).equals(String.valueOf(staffId))) {
                return new BigDecimal(String.valueOf(entry.get("share")));
            }
        }
        throw new AssertionError("staff " + staffId + " is not on line " + lineIndex
                               + " of bill " + billId);
    }

    /** The staff block from the roster read (list, not show - show 404s). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> commissionBlock(String name) {
        Object block = Steps.staffRecord(SALON, name).get("commission");
        assertNotNull(block, "the roster row for " + name + " has no commission block");
        return (Map<String, Object>) block;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal rateOf(Map<String, Object> block, String section, String key) {
        Object current = block.get(section);
        if (current == null) {
            return null;
        }
        Object value = ((Map<String, Object>) current).get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // 201  one staff member, one service
    // =====================================================================
    /**
     * API-E2E-201 a service sold by one stylist pays that stylist once.
     *
     * Service commission is 15% of the commissionable base, which is the line
     * net of GST and discounts. On a 1,000 rupee service at 18% GST the base is
     * 1,000 and the payout 150 - not 15% of the 1,180 the customer paid, which
     * would hand the stylist a share of the government's tax. The other stylist
     * must not move at all, and the amount must not double.
     */
    @Test(description = "API-E2E-201 single-staff service pays 15% of the ex-GST base, once")
    public void e2e201_singleStaffService() {
        LocalDate day = Steps.today();
        BigDecimal baseBeforeOne = row(one, "commission_base");
        BigDecimal amountBeforeOne = row(one, "commission_amount");
        BigDecimal amountBeforeTwo = row(two, "commission_amount");

        NewCustomer customer = arriveAsEnquiry("QA COMM 201");
        int billId = sell(customer, List.of(serviceLine(List.of(share(one, "100")))),
                          ZERO, null);
        assertSameDay(day);

        BigDecimal base = catalogue.servicePrice;
        movedBy("the stylist's commission base", baseBeforeOne,
                row(one, "commission_base"), base);
        movedBy("the stylist's commission", amountBeforeOne,
                row(one, "commission_amount"), commissionOn(base, "100", FIFTEEN));
        movedBy("the other stylist's commission", amountBeforeTwo,
                row(two, "commission_amount"), ZERO);

        assertEquals(shareOf(billId, 0, one).compareTo(HUNDRED), 0,
            "a single-staff line should answer share 100, not a split");
        assertEquals(staffOnLine(billId, 0).size(), 1,
            "one stylist was named but the line carries " + staffOnLine(billId, 0));
    }

    // =====================================================================
    // 202-204, 211  the shared service
    // =====================================================================
    /**
     * API-E2E-202 a 50/50 shared service pays each stylist on half the value.
     *
     * The split is applied to the commissionable value BEFORE the individual
     * rate: each gets 50% of 1,000, at their own 15%, so 75 each and 150
     * together. Paying both stylists 15% of the whole 1,000 would cost the
     * salon 300 for one haircut, which is the double-pay this feature exists
     * to avoid.
     */
    @Test(description = "API-E2E-202 a 50/50 shared service splits the base before the rate")
    public void e2e202_shared50_50() {
        LocalDate day = Steps.today();
        BigDecimal baseBeforeOne = row(one, "commission_base");
        BigDecimal baseBeforeTwo = row(two, "commission_base");
        BigDecimal amountBeforeOne = row(one, "commission_amount");
        BigDecimal amountBeforeTwo = row(two, "commission_amount");
        BigDecimal revenueBeforeOne = row(one, "service_revenue");
        BigDecimal revenueBeforeTwo = row(two, "service_revenue");

        NewCustomer customer = arriveAsEnquiry("QA COMM 202");
        int billId = sell(customer, List.of(serviceLine(List.of(
                share(one, "50"), share(two, "50")))), ZERO, null);
        assertSameDay(day);

        BigDecimal base = catalogue.servicePrice;
        movedBy("stylist one's base", baseBeforeOne, row(one, "commission_base"),
                commissionOn(base, "50", HUNDRED));
        movedBy("stylist two's base", baseBeforeTwo, row(two, "commission_base"),
                commissionOn(base, "50", HUNDRED));
        movedBy("stylist one's commission", amountBeforeOne,
                row(one, "commission_amount"), commissionOn(base, "50", FIFTEEN));
        movedBy("stylist two's commission", amountBeforeTwo,
                row(two, "commission_amount"), commissionOn(base, "50", FIFTEEN));

        BigDecimal together = row(one, "commission_amount").subtract(amountBeforeOne)
                .add(row(two, "commission_amount").subtract(amountBeforeTwo));
        assertEquals(together.compareTo(commissionOn(base, "100", FIFTEEN)), 0,
            "the two payouts add up to " + together + " on a 1,000 service. Commission "
          + "must be paid on the 1,000 once, split between the two, not on the full "
          + "value twice.");

        // Revenue attribution follows the same share: 1,180 gross, 590 each.
        movedBy("stylist one's service revenue", revenueBeforeOne,
                row(one, "service_revenue"), gross(1, 0, ZERO).multiply(new BigDecimal("0.5")));
        movedBy("stylist two's service revenue", revenueBeforeTwo,
                row(two, "service_revenue"), gross(1, 0, ZERO).multiply(new BigDecimal("0.5")));

        assertEquals(shareOf(billId, 0, one).compareTo(new BigDecimal("50")), 0,
            "the stored share for stylist one is not 50");
        assertEquals(shareOf(billId, 0, two).compareTo(new BigDecimal("50")), 0,
            "the stored share for stylist two is not 50");
        assertEquals(staffOnLine(billId, 0).size(), 2,
            "the same service should carry both stylists, not one of them twice");
    }

    /**
     * API-E2E-203 unequal shares: 70/30 and 90/10.
     *
     * A share is a percentage of the line, not a head count. The junior who
     * shampoos and the senior who cuts are not paid the same, and 90/10 is the
     * boundary a "helper" case produces in practice. Both bills are measured
     * separately so a split that only works at 50/50 cannot pass.
     */
    @Test(description = "API-E2E-203 70/30 and 90/10 shares are paid on their own share")
    public void e2e203_unequalShares() {
        LocalDate day = Steps.today();
        BigDecimal base = catalogue.servicePrice;

        BigDecimal beforeOne = row(one, "commission_amount");
        BigDecimal beforeTwo = row(two, "commission_amount");
        sell(arriveAsEnquiry("QA COMM 203 a"), List.of(serviceLine(List.of(
                share(one, "70"), share(two, "30")))), ZERO, null);
        movedBy("70/30 - stylist one", beforeOne, row(one, "commission_amount"),
                commissionOn(base, "70", FIFTEEN));
        movedBy("70/30 - stylist two", beforeTwo, row(two, "commission_amount"),
                commissionOn(base, "30", FIFTEEN));

        BigDecimal beforeOne2 = row(one, "commission_amount");
        BigDecimal beforeTwo2 = row(two, "commission_amount");
        sell(arriveAsEnquiry("QA COMM 203 b"), List.of(serviceLine(List.of(
                share(one, "90"), share(two, "10")))), ZERO, null);
        assertSameDay(day);
        movedBy("90/10 - stylist one", beforeOne2, row(one, "commission_amount"),
                commissionOn(base, "90", FIFTEEN));
        movedBy("90/10 - stylist two", beforeTwo2, row(two, "commission_amount"),
                commissionOn(base, "10", FIFTEEN));
    }

    /**
     * API-E2E-204 an allocation that does not add up to 100 is refused.
     *
     * The failure this prevents is quiet: 40/50 would otherwise credit 90% of
     * the service and lose the other 10%, or 60/50 would credit 110% and pay
     * out more than the service earned. A zero share is refused too - the API
     * has no 100/0 form ("Each share ... must be more than 0%"), so a second
     * name is either a real participant or left off. A duplicate person, a
     * missing id, a person from another salon and a product line are all
     * refused for the same reason: each would double-pay or mis-pay.
     *
     * An EMPTY staff array is the one accepted shape, and it leaves the line
     * unattributed: 201, nobody credited, no commission. Recorded as an
     * ambiguity in docs/DEFECTS.md (C1) rather than asserted as correct -
     * a till that drops the names loses the attribution silently.
     */
    @Test(description = "API-E2E-204 invalid staff allocations are refused, not silently paid")
    public void e2e204_invalidAllocations() {
        LocalDate day = Steps.today();
        NewCustomer customer = arriveAsEnquiry("QA COMM 204");
        BigDecimal beforeOne = row(one, "commission_amount");
        BigDecimal beforeTwo = row(two, "commission_amount");

        refused("shares totalling 90", customer, List.of(share(one, "40"), share(two, "50")),
                "must add up to 100");
        refused("shares totalling 110", customer, List.of(share(one, "60"), share(two, "50")),
                "must add up to 100");
        refused("a negative share", customer, List.of(share(one, "-10"), share(two, "110")),
                "must be more than 0");
        refused("a share over 100", customer, List.of(share(one, "101"), share(two, "-1")),
                "must be more than 0");
        refused("100/0", customer, List.of(share(one, "100"), share(two, "0")),
                "must be more than 0");
        refused("the same person twice", customer, List.of(share(one, "50"), share(one, "50")),
                "can appear once");
        Map<String, Object> noShare = new LinkedHashMap<>();
        noShare.put("staff_id", one);
        refused("a missing share", customer,
                List.of(noShare, share(two, "50")), "must be more than 0");
        Map<String, Object> noStaff = new LinkedHashMap<>();
        noStaff.put("share", new BigDecimal("50"));
        refused("a missing staff id", customer,
                List.of(noStaff, share(two, "50")), "Choose staff from this salon");

        // A person from another salon must not be paid out of this one.
        int[] foreign = { 0 };
        Response others = io.restassured.RestAssured.given().spec(Api.owner())
                .pathParam("salonId", Env.OWNER_SALON)
                .queryParam("limit", 5)
                .when().get("/salons/{salonId}/staff")
                .then().statusCode(200).extract().response();
        List<Integer> foreignIds = others.jsonPath().getList("data.items.id", Integer.class);
        if (foreignIds != null && !foreignIds.isEmpty()) {
            foreign[0] = foreignIds.get(0);
            refused("a stylist from another salon", customer,
                    List.of(share(foreign[0], "100")), "Choose staff from this salon");
        }

        // Product lines are sold by one person; the API says so explicitly.
        Map<String, Object> sharedProduct = productLine(one);
        sharedProduct.put("staff", List.of(share(one, "50"), share(two, "50")));
        Response product = Steps.attemptBill(customer.id(), List.of(sharedProduct), null, null);
        assertEquals(product.statusCode(), 422,
            "a shared product line was accepted: " + Steps.redacted(product));
        assertTrue(String.valueOf(product.jsonPath().getString("error"))
                        .contains("Only a service can be shared"),
            "a shared product line was refused for the wrong reason: "
          + Steps.redacted(product));

        // The accepted edge: names omitted entirely. No money changes hands.
        int openBill = openBill(customer,
                List.of(serviceLine(List.of())), ZERO, null);
        Steps.settleInFull(openBill, "cash");
        assertSameDay(day);
        movedBy("stylist one's commission after an unattributed bill", beforeOne,
                row(one, "commission_amount"), ZERO);
        movedBy("stylist two's commission after an unattributed bill", beforeTwo,
                row(two, "commission_amount"), ZERO);
    }

    /** Assert a bill with this allocation is refused, and name the reason. */
    private void refused(String what, NewCustomer customer,
                         List<Map<String, Object>> staffShares, String expectedError) {
        Response response = Steps.attemptBill(customer.id(),
                List.of(serviceLine(staffShares)), null, null);
        assertEquals(response.statusCode(), 422,
            what + " was accepted (" + response.statusCode() + "): "
          + Steps.redacted(response));
        assertTrue(String.valueOf(response.jsonPath().getString("error"))
                        .contains(expectedError),
            what + " was refused for the wrong reason: " + Steps.redacted(response));
    }

    /**
     * API-E2E-211 a GST-off bill is commissioned on the full amount.
     *
     * The base is revenue net of GST. With tax switched off on the bill there
     * is no tax to strip, so 1,000 shared 50/50 is 500 each and 75 each at 15%.
     * If the calculation assumed a fixed 18% it would pay 15% of 847 instead.
     * The tax is set per bill, so no salon-wide setting is touched.
     */
    @Test(description = "API-E2E-211 a GST-off bill pays commission on the full value")
    public void e2e211_gstOffBase() {
        LocalDate day = Steps.today();
        BigDecimal beforeOne = row(one, "commission_amount");
        BigDecimal beforeTwo = row(two, "commission_amount");

        NewCustomer customer = arriveAsEnquiry("QA COMM 211");
        sell(customer, List.of(serviceLine(List.of(share(one, "50"), share(two, "50")))),
             ZERO, false);
        assertSameDay(day);

        BigDecimal base = catalogue.servicePrice;
        movedBy("stylist one's commission on an untaxed bill", beforeOne,
                row(one, "commission_amount"), commissionOn(base, "50", FIFTEEN));
        movedBy("stylist two's commission on an untaxed bill", beforeTwo,
                row(two, "commission_amount"), commissionOn(base, "50", FIFTEEN));
    }

    // =====================================================================
    // 205  products
    // =====================================================================
    /**
     * API-E2E-205 product commission uses the product rate and never splits.
     *
     * The two stylists are on deliberately different product rates, 5% and 15%,
     * so a bug that uses the service rate for both - or the first stylist's
     * rate for everybody - shows up immediately. A product earns no service
     * commission, and a shared product line is refused outright.
     */
    @Test(description = "API-E2E-205 product commission follows the person's product rate")
    public void e2e205_productCommission() {
        LocalDate day = Steps.today();
        BigDecimal base = catalogue.productPrice;

        BigDecimal serviceBeforeOne = row(one, "service_commission");
        BigDecimal productBeforeOne = row(one, "product_commission");
        BigDecimal productBeforeTwo = row(two, "product_commission");

        sell(arriveAsEnquiry("QA COMM 205 a"), List.of(productLine(one)), ZERO, null);
        movedBy("stylist one's product commission", productBeforeOne,
                row(one, "product_commission"), commissionOn(base, "100", FIVE));
        movedBy("stylist one's service commission on a product sale", serviceBeforeOne,
                row(one, "service_commission"), ZERO);

        BigDecimal productBeforeTwo2 = row(two, "product_commission");
        sell(arriveAsEnquiry("QA COMM 205 b"), List.of(productLine(two)), ZERO, null);
        assertSameDay(day);
        movedBy("stylist two's product commission", productBeforeTwo2,
                row(two, "product_commission"), commissionOn(base, "100", FIFTEEN));
        assertNotEquals(commissionOn(base, "100", FIVE).compareTo(
                        commissionOn(base, "100", FIFTEEN)), 0,
            "the two product rates must differ for this test to prove anything");
    }

    /**
     * API-E2E-205b a shared service and a product on one bill stay separate.
     *
     * This is the mixed basket the till produces: a 70/30 service plus a
     * shampoo that belongs to one of them. The service splits, the product does
     * not, and the product earns nothing at the service rate. Folded into the
     * same test id's method set rather than a new scenario.
     */
    @Test(description = "API-E2E-205b shared service plus a product on one bill stay separate")
    public void e2e205b_mixedBasket() {
        LocalDate day = Steps.today();
        BigDecimal serviceBase = catalogue.servicePrice;
        BigDecimal productBase = catalogue.productPrice;

        BigDecimal serviceOneBefore = row(one, "service_commission");
        BigDecimal productOneBefore = row(one, "product_commission");
        BigDecimal serviceTwoBefore = row(two, "service_commission");
        BigDecimal productTwoBefore = row(two, "product_commission");

        NewCustomer customer = arriveAsEnquiry("QA COMM 205 mixed");
        int billId = sell(customer, List.of(
                serviceLine(List.of(share(one, "70"), share(two, "30"))),
                productLine(two)), ZERO, null);
        assertSameDay(day);

        movedBy("mixed bill - stylist one's service commission", serviceOneBefore,
                row(one, "service_commission"), commissionOn(serviceBase, "70", FIFTEEN));
        movedBy("mixed bill - stylist two's service commission", serviceTwoBefore,
                row(two, "service_commission"), commissionOn(serviceBase, "30", FIFTEEN));
        movedBy("mixed bill - stylist one's product commission", productOneBefore,
                row(one, "product_commission"), ZERO);
        movedBy("mixed bill - stylist two's product commission", productTwoBefore,
                row(two, "product_commission"), commissionOn(productBase, "100", FIFTEEN));

        assertEquals(staffOnLine(billId, 0).size(), 2,
            "the service line lost its split when a product shared the bill");
        assertEquals(shareOf(billId, 1, two).compareTo(HUNDRED), 0,
            "the product line should belong to one person at 100, not carry a share");
    }

    // =====================================================================
    // 206-208  configuration
    // =====================================================================
    /**
     * API-E2E-206 a rate change applies to the next sale, not the last one.
     *
     * Commission is a function of what was agreed when the work was sold. If
     * the roster edited itself backwards, closing a month and then changing
     * somebody's rate would restate a payout that has already been made. The
     * new rate must reach the next bill, the old bill must stay where it was,
     * and the history must keep both.
     */
    @Test(description = "API-E2E-206 a commission rate change cannot re-price a settled bill")
    public void e2e206_rateChangeIsForwardOnly() {
        LocalDate day = Steps.today();
        BigDecimal beforeOldBill = row(one, "commission_amount");

        // A sale at the original 15% service rate.
        sell(arriveAsEnquiry("QA COMM 206 old"), List.of(
                serviceLine(List.of(share(one, "100")))), ZERO, null);
        BigDecimal afterOldBill = row(one, "commission_amount");
        movedBy("the sale at 15%", beforeOldBill, afterOldBill,
                commissionOn(catalogue.servicePrice, "100", FIFTEEN));

        Response update;
        try {
            update = Steps.attemptCommissionUpdate(SALON, one, Map.of(
                    "service_pct", new BigDecimal("20"),
                    "product_pct", new BigDecimal("10"),
                    "membership_pct", 0));
            assertEquals(update.statusCode(), 200,
                "a valid rate change was refused: " + Steps.redacted(update));

            Map<String, Object> block = commissionBlock(oneName);
            assertEquals(rateOf(block, "current", "service_pct").compareTo(new BigDecimal("20")), 0,
                "the new service rate is not in force today: " + block.get("current"));
            assertEquals(rateOf(block, "current", "product_pct").compareTo(new BigDecimal("10")), 0,
                "the new product rate is not in force today: " + block.get("current"));
            List<Map<String, Object>> history = (List<Map<String, Object>>) block.get("history");
            assertTrue(history != null && history.size() >= 2,
                "the rate change left no history entry: " + block);

            // The closed bill must not move just because the roster did.
            assertEquals(row(one, "commission_amount").compareTo(afterOldBill), 0,
                "changing the rate restated an already settled bill: "
              + afterOldBill + " -> " + row(one, "commission_amount"));

            sell(arriveAsEnquiry("QA COMM 206 new"), List.of(
                    serviceLine(List.of(share(one, "100"))),
                    productLine(one)), ZERO, null);
            assertSameDay(day);
            BigDecimal newSale = commissionOn(catalogue.servicePrice, "100", new BigDecimal("20"))
                    .add(commissionOn(catalogue.productPrice, "100", new BigDecimal("10")));
            movedBy("the first sale after the change", afterOldBill,
                    row(one, "commission_amount"), newSale);
        } finally {
            // Leave the shared pair on its documented rates for later cases.
            Steps.attemptCommissionUpdate(SALON, one, Map.of(
                    "service_pct", FIFTEEN,
                    "product_pct", FIVE,
                    "membership_pct", 0));
        }
    }

    /**
     * API-E2E-207 a future-dated rate is scheduled, not applied.
     *
     * The owner can enter next month's rate today. If the API applied it
     * immediately every sale until then would be paid at the wrong rate; the
     * entry has to wait in `scheduled` with today's rate untouched.
     */
    @Test(description = "API-E2E-207 a future effective date schedules the rate without applying it")
    public void e2e207_futureRateIsScheduled() {
        LocalDate day = Steps.today();
        try {
            Response update = Steps.attemptCommissionUpdate(SALON, one, Map.of(
                    "service_pct", new BigDecimal("25"),
                    "product_pct", new BigDecimal("12"),
                    "membership_pct", 0,
                    "effective_from", Steps.today().plusDays(7).toString()));
            assertEquals(update.statusCode(), 200,
                "a future-dated rate was refused: " + Steps.redacted(update));

            Map<String, Object> block = commissionBlock(oneName);
            assertEquals(rateOf(block, "current", "service_pct").compareTo(FIFTEEN), 0,
                "a rate dated a week ahead replaced today's rate: " + block.get("current"));
            assertEquals(rateOf(block, "scheduled", "service_pct")
                        .compareTo(new BigDecimal("25")), 0,
                "the future rate did not land in scheduled: " + block.get("scheduled"));

            BigDecimal before = row(one, "commission_amount");
            sell(arriveAsEnquiry("QA COMM 207"), List.of(
                    serviceLine(List.of(share(one, "100")))), ZERO, null);
            assertSameDay(day);
            movedBy("a sale before the scheduled date", before,
                    row(one, "commission_amount"),
                    commissionOn(catalogue.servicePrice, "100", FIFTEEN));
        } finally {
            Steps.attemptCommissionUpdate(SALON, one, Map.of(
                    "service_pct", FIFTEEN,
                    "product_pct", FIVE,
                    "membership_pct", 0));
        }
    }

    /**
     * API-E2E-208 invalid commission configuration is refused.
     *
     * Negative rates would pay a stylist to not work; over 100 would pay out
     * more than the sale earned. Both must be refused at the boundary, not
     * clamped silently. A partial object is refused too because the missing
     * category would have no defined rate. The legacy pair and the new object
     * are mutually exclusive, and a non-object is refused rather than coerced.
     */
    @Test(description = "API-E2E-208 invalid commission rates cannot bypass validation")
    public void e2e208_invalidConfiguration() {
        Map<String, Object> before = commissionBlock(oneName);

        refusedConfig("a negative service rate", Map.of(
                "service_pct", new BigDecimal("-1"),
                "product_pct", FIVE, "membership_pct", 0), 422);
        refusedConfig("a service rate over 100", Map.of(
                "service_pct", new BigDecimal("101"),
                "product_pct", FIVE, "membership_pct", 0), 422);
        refusedConfig("a non-numeric rate", Map.of(
                "service_pct", "abc",
                "product_pct", FIVE, "membership_pct", 0), 422);
        refusedConfig("a missing product rate", Map.of(
                "service_pct", FIFTEEN, "membership_pct", 0), 422);

        Response both = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", one)
                .body(Map.of(
                        "commission", Map.of("service_pct", FIFTEEN,
                                             "product_pct", FIVE,
                                             "membership_pct", 0),
                        "commission_type", "percentage",
                        "commission_value", 10))
                .when().patch("/salons/{salonId}/staff/{id}");
        assertEquals(both.statusCode(), 400,
            "the legacy pair and the commission object were both accepted: "
          + Steps.redacted(both));

        Response notAnObject = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", one)
                .body(Map.of("commission", "15"))
                .when().patch("/salons/{salonId}/staff/{id}");
        assertEquals(notAnObject.statusCode(), 400,
            "a commission that is not an object was accepted: "
          + Steps.redacted(notAnObject));

        Map<String, Object> after = commissionBlock(oneName);
        assertEquals(rateOf(after, "current", "service_pct"),
                     rateOf(before, "current", "service_pct"),
            "a refused update still changed the service rate");
        assertEquals(rateOf(after, "current", "product_pct"),
                     rateOf(before, "current", "product_pct"),
            "a refused update still changed the product rate");
    }

    private void refusedConfig(String what, Map<String, Object> commission, int expected) {
        Response response = Steps.attemptCommissionUpdate(SALON, one, commission);
        assertEquals(response.statusCode(), expected,
            what + " was accepted (" + response.statusCode() + "): "
          + Steps.redacted(response));
    }

    /**
     * API-E2E-208b zero commission is a supported setting.
     *
     * 0 is not "unset" - a stylist can be on no commission at all, and a sale
     * they perform must then pay nothing rather than falling back to a default.
     */
    @Test(description = "API-E2E-208b a 0% stylist earns nothing on a sale")
    public void e2e208b_zeroCommission() {
        LocalDate day = Steps.today();
        String name = "QA COMM zero " + System.nanoTime() % 100000;
        int zero = Steps.createStaffWithCommission(SALON, name, ZERO, ZERO);

        NewCustomer customer = arriveAsEnquiry("QA COMM 208 zero");
        sell(customer, List.of(serviceLine(List.of(share(zero, "100"))),
                               productLine(zero)), ZERO, null);
        assertSameDay(day);

        assertEquals(row(zero, "commission_amount").compareTo(ZERO), 0,
            "a stylist on 0% was paid " + row(zero, "commission_amount"));
        assertTrue(row(zero, "service_revenue").compareTo(ZERO) > 0,
            "the 0% stylist's sale was not attributed to them at all: " + row(zero, "service_revenue"));
    }

    // =====================================================================
    // 209-210  refunds and replays
    // =====================================================================
    /**
     * API-E2E-209 a full refund takes both stylists' commission back.
     *
     * The salon gave the money back; it cannot owe commission on it. Both
     * halves must reverse, not just the first name on the bill. Re-finalizing
     * the refunded bill must be refused, and it must not re-create the payout.
     */
    @Test(description = "API-E2E-209 a full refund reverses both stylists' commission")
    public void e2e209_fullRefundReversesBoth() {
        LocalDate day = Steps.today();
        BigDecimal beforeOne = row(one, "commission_amount");
        BigDecimal beforeTwo = row(two, "commission_amount");

        NewCustomer customer = arriveAsEnquiry("QA COMM 209");
        int billId = sell(customer, List.of(
                serviceLine(List.of(share(one, "50"), share(two, "50")))), ZERO, null);
        movedBy("stylist one's commission before refund", beforeOne,
                row(one, "commission_amount"),
                commissionOn(catalogue.servicePrice, "50", FIFTEEN));
        movedBy("stylist two's commission before refund", beforeTwo,
                row(two, "commission_amount"),
                commissionOn(catalogue.servicePrice, "50", FIFTEEN));

        Steps.refundInFull(billId, "QA COMM 209 refund");
        assertSameDay(day);
        movedBy("stylist one's commission after refund", beforeOne,
                row(one, "commission_amount"), ZERO);
        movedBy("stylist two's commission after refund", beforeTwo,
                row(two, "commission_amount"), ZERO);
        assertBillIsRefunded(billId);

        assertEquals(Steps.attemptFinalize(billId, Steps.netPayable(billId), "cash"), 409,
            "a refunded bill was finalized a second time");
        assertEquals(row(one, "commission_amount").compareTo(beforeOne), 0,
            "replaying finalize after a refund re-created commission");
    }

    /**
     * API-E2E-210 a part refund scales the shared commission down.
     *
     * A half refund is a price adjustment, not an unwind: the service was still
     * delivered, so half the commission is still owed. Both stylists lose the
     * same proportion of their own half. Refunding more than the bill is worth
     * must be refused rather than driving the payout negative.
     */
    @Test(description = "API-E2E-210 a part refund halves both stylists' commission")
    public void e2e210_partRefundScalesBoth() {
        LocalDate day = Steps.today();
        NewCustomer customer = arriveAsEnquiry("QA COMM 210");
        int billId = sell(customer, List.of(
                serviceLine(List.of(share(one, "50"), share(two, "50")))), ZERO, null);

        BigDecimal paid = Steps.netPayable(billId);
        BigDecimal half = Money.round(paid.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP));

        BigDecimal beforeOne = row(one, "commission_amount");
        BigDecimal beforeTwo = row(two, "commission_amount");
        Steps.refundPart(billId, half, "QA COMM 210 half back");
        assertSameDay(day);

        BigDecimal fullShare = commissionOn(catalogue.servicePrice, "50", FIFTEEN);
        BigDecimal halfShare = Money.round(fullShare.multiply(new BigDecimal("0.5")));
        movedBy("stylist one's commission after a half refund", beforeOne,
                row(one, "commission_amount"), halfShare.negate());
        movedBy("stylist two's commission after a half refund", beforeTwo,
                row(two, "commission_amount"), halfShare.negate());

        assertEquals(Steps.attemptRefund(billId, paid.multiply(new BigDecimal("2")),
                                         "QA COMM 210 over-refund"), 422,
            "a refund larger than the bill was accepted");

        assertEquals(Steps.repeatPayment(billId, BigDecimal.ONE, "cash") >= 400, true,
            "a refunded bill accepted another payment");
    }

    // =====================================================================
    // 212-216  persisted data and the report
    // =====================================================================
    /**
     * API-E2E-212 the report, the bill and the money agree.
     *
     * The row's commission base is what the bill says it is: net of GST, split
     * by share. The service and product commission columns must add up to the
     * total, and the salon-wide summary must move by exactly the two payouts.
     * Reading the summary is the check that the row is not a private opinion
     * the dashboard never sees.
     */
    @Test(description = "API-E2E-212 bill, staff row and summary agree on a shared sale")
    public void e2e212_reportAgreesWithBill() {
        LocalDate day = Steps.today();
        Map<String, BigDecimal> summaryBefore = Reports.snapshot(SALON, List.of(Reports.STAFF));

        BigDecimal baseBeforeOne = row(one, "commission_base");
        NewCustomer customer = arriveAsEnquiry("QA COMM 212");
        int billId = sell(customer, List.of(
                serviceLine(List.of(share(one, "60"), share(two, "40")))), ZERO, null);
        assertSameDay(day);

        BigDecimal base = catalogue.servicePrice;
        assertEquals(shareOf(billId, 0, one).compareTo(new BigDecimal("60")), 0,
            "the bill and the report disagree about the stored share");
        movedBy("stylist one's commission base", baseBeforeOne,
                row(one, "commission_base"), commissionOn(base, "60", HUNDRED));

        assertEquals(Money.round(row(one, "service_commission")
                        .add(row(one, "product_commission")))
                     .compareTo(row(one, "commission_amount")), 0,
            "service + product commission does not add up to the total on the row");

        BigDecimal summaryAfter = Reports.snapshot(SALON, List.of(Reports.STAFF))
                .get("staff_performance.total_commission");
        movedBy("the salon-wide commission KPI",
                summaryBefore.get("staff_performance.total_commission"), summaryAfter,
                commissionOn(base, "60", FIFTEEN)
                        .add(commissionOn(base, "40", FIFTEEN)));

        assertBillIsPaidBy(billId, customer);
    }

    /**
     * API-E2E-213 one stylist on two lines of the same bill is paid per line.
     *
     * Sharing is per LINE, not per bill: the same person can perform two
     * services, and each line pays them in full. This must not be confused with
     * the same person twice on one line, which the API refuses (API-E2E-204).
     */
    @Test(description = "API-E2E-213 the same stylist on two lines is paid for both")
    public void e2e213_sameStylistTwoLines() {
        LocalDate day = Steps.today();
        BigDecimal before = row(one, "commission_amount");

        NewCustomer customer = arriveAsEnquiry("QA COMM 213");
        int billId = sell(customer, List.of(
                serviceLine(List.of(share(one, "100"))),
                serviceLine(List.of(share(one, "100")))), ZERO, null);
        assertSameDay(day);

        movedBy("two lines performed by the same stylist", before,
                row(one, "commission_amount"),
                commissionOn(catalogue.servicePrice.multiply(new BigDecimal("2")),
                             "100", FIFTEEN));
        assertEquals(Steps.readBill(billId).getList("data.items").size(), 2,
            "two service lines were collapsed into one");
    }

    /**
     * API-E2E-214 a one-name staff array is the same sale as a plain staff_id.
     *
     * The till may name staff either way. Both must store the full 100% share
     * and pay the same money, or a UI that switches shape would change payroll.
     */
    @Test(description = "API-E2E-214 a 100% staff array matches the single staff_id form")
    public void e2e214_singleShareMatchesStaffId() {
        LocalDate day = Steps.today();
        BigDecimal before = row(one, "commission_amount");

        NewCustomer customer = arriveAsEnquiry("QA COMM 214");
        Map<String, Object> line = serviceLine(List.of(share(one, "100")));
        int billId = sell(customer, List.of(line), ZERO, null);
        assertSameDay(day);

        movedBy("a 100% array sale", before, row(one, "commission_amount"),
                commissionOn(catalogue.servicePrice, "100", FIFTEEN));
        assertEquals(shareOf(billId, 0, one).compareTo(HUNDRED), 0,
            "a one-name staff array did not store share 100");
        assertEquals(staffOnLine(billId, 0).size(), 1,
            "a one-name staff array stored more than one person");
    }

    /**
     * API-E2E-215 commission is rounded on the summed base, not per bill.
     *
     * Two identical odd bills are the only way to see this. Each contributes
     * 33.33% of 910 at 15% = 45.495. Rounding each bill pays 45.50 + 45.50 =
     * 91.00; rounding the sum pays 90.99. The live API reports 90.99, so the
     * payout is a single calculation over the period's base - which means a
     * month of small sales cannot accumulate rounding in the stylist's favour.
     */
    @Test(description = "API-E2E-215 commission rounds the summed base, not each bill")
    public void e2e215_roundingIsOnTheSummedBase() {
        LocalDate day = Steps.today();
        String name = "QA COMM round " + System.nanoTime() % 100000;
        int odd = Steps.createStaffWithCommission(SALON, name, FIFTEEN, FIFTEEN);

        BigDecimal discounted = Money.round(
                catalogue.servicePrice.multiply(new BigDecimal("0.91")));

        sell(arriveAsEnquiry("QA COMM 215 a"), List.of(serviceLine(
                List.of(share(one, "66.67"), share(odd, "33.33")))), new BigDecimal("9"), false);
        BigDecimal afterOne = row(odd, "commission_amount");
        assertEquals(afterOne.compareTo(new BigDecimal("45.50")), 0,
            "one odd bill paid " + afterOne + ", expected 45.50");
        assertEquals(row(odd, "commission_base").compareTo(
                     Money.round(discounted.multiply(new BigDecimal("0.3333")))), 0,
            "the commissionable base is not 33.33% of the discounted line: "
          + row(odd, "commission_base"));

        sell(arriveAsEnquiry("QA COMM 215 b"), List.of(serviceLine(
                List.of(share(one, "66.67"), share(odd, "33.33")))), new BigDecimal("9"), false);
        assertSameDay(day);
        assertEquals(row(odd, "commission_amount").compareTo(new BigDecimal("90.99")), 0,
            "two 45.495 contributions paid " + row(odd, "commission_amount")
          + ". 90.99 means the base is summed and rounded once; 91.00 means each "
          + "bill is rounded before it is added.");
    }

    /**
     * API-E2E-216 reopening the report does not change the payout.
     *
     * The report is a read. A cached or recalculating read that drifts between
     * two identical requests makes every figure on the screen arguable, and a
     * stylist checking their own number gets a different answer each refresh.
     */
    @Test(description = "API-E2E-216 three consecutive report reads agree")
    public void e2e216_reportIsStable() {
        LocalDate day = Steps.today();
        BigDecimal before = row(one, "commission_amount");
        sell(arriveAsEnquiry("QA COMM 216"), List.of(
                serviceLine(List.of(share(one, "50"), share(two, "50")))), ZERO, null);
        assertSameDay(day);

        Map<String, Object> first = Steps.staffRow(SALON, one);
        Map<String, Object> second = Steps.staffRow(SALON, one);
        Map<String, Object> third = Steps.staffRow(SALON, one);
        for (String field : List.of("commission_base", "commission_amount",
                                    "service_commission", "product_commission",
                                    "service_revenue", "total_revenue")) {
            assertEquals(String.valueOf(second.get(field)), String.valueOf(first.get(field)),
                "the report's " + field + " changed between two reads");
            assertEquals(String.valueOf(third.get(field)), String.valueOf(first.get(field)),
                "the report's " + field + " changed on the third read");
        }
        assertTrue(row(one, "commission_amount").compareTo(before) > 0,
            "the stable report shows no commission for the new sale");
    }
}

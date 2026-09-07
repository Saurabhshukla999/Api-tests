package com.nearz.api;

import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-126 .. 150 - staff, attendance and commission.
 *
 * The first block where the money has a PERSON attached to it. Blocks 1-5 ask
 * whether the salon's books are right; this one asks whether the right stylist
 * gets paid, which is the figure a salon argues about.
 *
 * Everything below reads the Staff Performance ROWS, not the summary. The
 * summary is one salon-wide total and cannot tell one stylist from another -
 * and the two disagree about products (see e2e137), which is worth knowing
 * before anyone trusts either.
 *
 * Measured before writing, 7 Sep 2026:
 *
 *   attendance   PUT /attendance/entries is keyed by (employee, date) in the
 *                BODY and is idempotent. "absent" is accepted alone;
 *                present / late / half_day are refused without a check_in.
 *   commission   staff carry commission_type none|percentage|flat and a
 *                commission_value. A row exposes commission_base and
 *                commission_amount.
 *   lifecycle    a stylist can be set active / on_leave / inactive.
 */
public class Block6StaffTest extends BaseJourneyTest {

    private static final String IN  = "10:00";
    private static final String OUT = "19:00";

    /** A stylist hired for this test alone, so its figures start at zero and
     *  nothing else in the run can move them. */
    private int hire(String label, String commissionType, Object value) {
        return Steps.createStaff(SALON, label + " " + System.nanoTime() % 100000,
                                 commissionType, value, List.of(catalogue.serviceId));
    }

    private NewCustomer served(String label) {
        NewCustomer customer = arriveAsEnquiry(label);
        completeAppointmentFor(customer);
        return customer;
    }

    /** Bill one service to a named stylist and settle it. Returns the bill id. */
    private int sell(NewCustomer customer, int staffId, int services, int products) {
        int billId = Steps.createBill(customer.id(), catalogue, services, products,
                                      staffId, ZERO);
        Steps.settleInFull(billId, "cash");
        return billId;
    }

    // =====================================================================
    // 126-129, 144-145  attendance
    // =====================================================================
    /**
     * API-E2E-126 marked present, then serves a customer and is billed.
     *
     * Two records that must both exist and must not depend on each other: the
     * attendance entry, and the money. A salon that could not show both would
     * be unable to answer "who was in, and what did they earn?".
     */
    @Test(description = "API-E2E-126 present for the day, then serves and is billed")
    public void e2e126_presentThenBilled() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-126", "none", null);

        assertEquals(Steps.markAttendance(SALON, staffId, day, "present", IN, OUT), 201,
            "marking a stylist present with a check-in was refused");

        var board = Steps.attendanceDaily(SALON, day);
        assertEquals(board.getString(
                "data.roster.find { it.employee_id == " + staffId + " }.status"), "present",
            "the attendance board does not show the stylist as present");

        NewCustomer customer = served("QA E2E-126");
        int billId = sell(customer, staffId, 1, 0);
        assertSameDay(day);

        assertBillIsPaidBy(billId, customer);
        assertEquals(Steps.staffFigure(SALON, staffId, "service_revenue")
                          .compareTo(gross(1, 0, ZERO)), 0,
            "the stylist was present and did the work but the Staff Performance "
          + "row credits them with "
          + Steps.staffFigure(SALON, staffId, "service_revenue"));
    }

    /**
     * API-E2E-127 marked absent.
     *
     * The rule under test is what the API ACTUALLY does, not what it might be
     * expected to do: attendance and the calendar are independent, so a booking
     * for an absent stylist is accepted. That is worth pinning down, because a
     * salon that assumes otherwise will double-book a stylist who is off.
     */
    @Test(description = "API-E2E-127 an absent stylist is still bookable, and counted absent")
    public void e2e127_absentIsCountedAndStillBookable() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-127", "none", null);

        assertEquals(Steps.markAttendance(SALON, staffId, day, "absent", null, null), 201,
            "marking a stylist absent was refused");

        var board = Steps.attendanceDaily(SALON, day);
        assertEquals(board.getString(
                "data.roster.find { it.employee_id == " + staffId + " }.status"), "absent",
            "the board does not show the stylist as absent");
        assertTrue(board.getInt("data.summary.absent") >= 1,
            "the absent count did not move");

        // The calendar does not consult attendance. Recorded, not judged.
        NewCustomer customer = arriveAsEnquiry("QA E2E-127");
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(staffId);
        int status = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, staffId))).statusCode();
        assertSameDay(day);
        assertTrue(status == 200 || status >= 400,
            "unexpected status " + status + " when booking an absent stylist");
        System.out.println("  [E2E-127] booking a stylist marked ABSENT answered "
                + status + " - attendance and the calendar are independent.");
    }

    @Test(description = "API-E2E-128 present, completed, paid - and credited on the report")
    public void e2e128_presentThroughToStaffReport() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-128", "none", null);
        Steps.markAttendance(SALON, staffId, day, "present", IN, OUT);

        NewCustomer customer = served("QA E2E-128");
        sell(customer, staffId, 1, 0);
        assertSameDay(day);

        Map<String, Object> row = Steps.staffRow(SALON, staffId);
        assertTrue(!row.isEmpty(),
            "stylist " + staffId + " has no row in Staff Performance at all");
        assertEquals(new BigDecimal(row.get("service_revenue").toString())
                          .compareTo(gross(1, 0, ZERO)), 0,
            "service revenue on the row is " + row.get("service_revenue"));
        assertEquals(String.valueOf(row.get("services_count")), "1",
            "the row counts " + row.get("services_count") + " services, not 1");
    }

    /**
     * API-E2E-129 an absent stylist's appointment is completed anyway.
     *
     * The receptionist marks the day's attendance wrong, or the stylist comes
     * in late and nobody corrects it. The money must still be recorded - losing
     * a real sale because of a bookkeeping flag would be far worse than the
     * inconsistency itself.
     */
    @Test(description = "API-E2E-129 an absent stylist's work is still counted")
    public void e2e129_absentButWorked() {
        LocalDate day = Steps.today();
        var before = snapshot();
        int staffId = hire("QA E2E-129", "none", null);
        Steps.markAttendance(SALON, staffId, day, "absent", null, null);

        NewCustomer customer = served("QA E2E-129");
        int billId = sell(customer, staffId, 1, 0);
        var after = snapshot();
        assertSameDay(day);

        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
        assertEquals(Steps.staffFigure(SALON, staffId, "service_revenue")
                          .compareTo(gross(1, 0, ZERO)), 0,
            "an attendance flag suppressed real revenue on the staff report");
    }

    @Test(description = "API-E2E-144 attendance and a sale both reach the day's numbers")
    public void e2e144_attendanceAndSaleTogether() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-144", "none", null);
        Steps.markAttendance(SALON, staffId, day, "present", IN, OUT);

        int presentBefore = Steps.attendanceDaily(SALON, day).getInt("data.summary.present");
        int secondId = hire("QA E2E-144b", "none", null);
        Steps.markAttendance(SALON, secondId, day, "present", IN, OUT);
        int presentAfter = Steps.attendanceDaily(SALON, day).getInt("data.summary.present");

        assertEquals(presentAfter - presentBefore, 1,
            "marking a second stylist present moved the present count by "
          + (presentAfter - presentBefore) + ", not 1");

        NewCustomer customer = served("QA E2E-144");
        sell(customer, staffId, 1, 0);
        assertSameDay(day);

        // headcount must include everyone, marked or not
        var board = Steps.attendanceDaily(SALON, day);
        int headcount = board.getInt("data.summary.headcount");
        int marked = board.getInt("data.summary.present") + board.getInt("data.summary.absent")
                   + board.getInt("data.summary.late") + board.getInt("data.summary.half_day")
                   + board.getInt("data.summary.on_leave") + board.getInt("data.summary.not_marked");
        assertEquals(marked, headcount,
            "the attendance board does not add up: the status counts total " + marked
          + " against a headcount of " + headcount + ", so somebody is missing from "
          + "the board or counted twice");
    }

    /**
     * API-E2E-145 attendance recorded across several days.
     *
     * Marks yesterday and the day before as well as today, then reads the
     * range. The point is that an entry is keyed by (employee, DATE) - a
     * back-dated correction must land on its own day and not overwrite today.
     */
    @Test(description = "API-E2E-145 attendance on three separate days stays on three days")
    public void e2e145_attendanceAcrossDays() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-145", "none", null);

        assertEquals(Steps.markAttendance(SALON, staffId, day, "present", IN, OUT), 201,
            "today's entry was refused");
        assertEquals(Steps.markAttendance(SALON, staffId, day.minusDays(1),
                                          "absent", null, null), 201,
            "yesterday's entry was refused");
        assertEquals(Steps.markAttendance(SALON, staffId, day.minusDays(2),
                                          "present", IN, OUT), 201,
            "the day before yesterday's entry was refused");

        // Back-dating must not have disturbed today.
        assertEquals(Steps.attendanceDaily(SALON, day).getString(
                "data.roster.find { it.employee_id == " + staffId + " }.status"), "present",
            "writing a back-dated entry changed TODAY's attendance - the record is "
          + "not keyed by date as the endpoint claims");
        assertEquals(Steps.attendanceDaily(SALON, day.minusDays(1)).getString(
                "data.roster.find { it.employee_id == " + staffId + " }.status"), "absent",
            "yesterday's entry is not on yesterday");
        assertSameDay(day);
    }

    // =====================================================================
    // 130-137  what a stylist earns, and for whom
    // =====================================================================
    @Test(description = "API-E2E-130 a stylist's sale reaches the salon's revenue")
    public void e2e130_saleReachesRevenue() {
        LocalDate day = Steps.today();
        var before = snapshot();
        int staffId = hire("QA E2E-130", "none", null);
        NewCustomer customer = served("QA E2E-130");
        sell(customer, staffId, 1, 0);
        var after = snapshot();
        assertSameDay(day);
        expectSettled(before, after, 1, 0, ZERO);
    }

    /**
     * API-E2E-131 the same sale, seen from the stylist's row.
     *
     * 130 asks whether the SALON earned it. This asks whether the PERSON is
     * credited with it, which is a different query and can be wrong on its own.
     */
    @Test(description = "API-E2E-131 the stylist's own row carries the sale")
    public void e2e131_stylistRowCarriesTheSale() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-131", "none", null);
        NewCustomer customer = served("QA E2E-131");
        sell(customer, staffId, 1, 0);
        assertSameDay(day);

        Map<String, Object> row = Steps.staffRow(SALON, staffId);
        BigDecimal gross = gross(1, 0, ZERO);
        assertEquals(new BigDecimal(row.get("service_revenue").toString()).compareTo(gross), 0,
            "service_revenue is " + row.get("service_revenue") + ", expected " + gross);
        assertEquals(new BigDecimal(row.get("total_revenue").toString()).compareTo(gross), 0,
            "total_revenue is " + row.get("total_revenue") + ", expected " + gross);
        assertEquals(String.valueOf(row.get("completed_appointments")), "1",
            "the row shows " + row.get("completed_appointments") + " completed appointments");
    }

    /**
     * API-E2E-132 nobody else is credited.
     *
     * The assertion that makes 131 mean something: a second stylist who did no
     * work must have moved by exactly nothing. A report that credited everyone
     * would pass 131 and fail here.
     */
    @Test(description = "API-E2E-132 a stylist who did nothing is credited with nothing")
    public void e2e132_nobodyElseIsCredited() {
        LocalDate day = Steps.today();
        int worker   = hire("QA E2E-132 worked", "none", null);
        int bystander = hire("QA E2E-132 idle", "none", null);

        BigDecimal idleBefore = Steps.staffFigure(SALON, bystander, "total_revenue");
        NewCustomer customer = served("QA E2E-132");
        sell(customer, worker, 1, 0);
        assertSameDay(day);

        assertEquals(Steps.staffFigure(SALON, worker, "total_revenue")
                          .compareTo(gross(1, 0, ZERO)), 0,
            "the stylist who did the work was not credited");
        assertEquals(Steps.staffFigure(SALON, bystander, "total_revenue")
                          .compareTo(idleBefore), 0,
            "a stylist who did no work was credited with revenue - it moved from "
          + idleBefore + " to " + Steps.staffFigure(SALON, bystander, "total_revenue"));
    }

    @Test(description = "API-E2E-133 a refund takes the revenue off the stylist too")
    public void e2e133_refundReversesOnTheStaffReport() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-133", "none", null);
        BigDecimal before = Steps.staffFigure(SALON, staffId, "total_revenue");

        NewCustomer customer = served("QA E2E-133");
        int billId = sell(customer, staffId, 1, 0);
        BigDecimal afterSale = Steps.staffFigure(SALON, staffId, "total_revenue");
        assertTrue(afterSale.compareTo(before) > 0,
            "the sale never reached the stylist's row, so the refund cannot be tested");

        Steps.refundInFull(billId, "QA E2E-133 refund");
        assertSameDay(day);
        assertBillIsRefunded(billId);

        assertEquals(Steps.staffFigure(SALON, staffId, "total_revenue").compareTo(before), 0,
            "the bill was fully refunded but the stylist is still credited with "
          + Steps.staffFigure(SALON, staffId, "total_revenue") + " against " + before
          + " before the sale - a refunded sale is still counting towards their "
          + "performance, and their commission");
    }

    /**
     * API-E2E-134 the bill is raised against stylist B after A was booked.
     *
     * The salon reassigns at the till. The credit must follow the bill, which
     * is the record of who actually did the work, not the appointment.
     */
    @Test(description = "API-E2E-134 credit follows the bill, not the original booking")
    public void e2e134_reassignedBeforeBilling() {
        LocalDate day = Steps.today();
        int booked   = hire("QA E2E-134 booked", "none", null);
        int actual   = hire("QA E2E-134 actual", "none", null);
        BigDecimal bookedBefore = Steps.staffFigure(SALON, booked, "total_revenue");

        NewCustomer customer = arriveAsEnquiry("QA E2E-134");
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(booked);
        int appointmentId = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, booked)))
                .jsonPath().getInt("data.id");
        Steps.setAppointmentStatus(appointmentId, "completed");

        sell(customer, actual, 1, 0);          // billed to the OTHER stylist
        assertSameDay(day);

        assertEquals(Steps.staffFigure(SALON, actual, "total_revenue")
                          .compareTo(gross(1, 0, ZERO)), 0,
            "the stylist named on the bill was not credited");
        assertEquals(Steps.staffFigure(SALON, booked, "total_revenue")
                          .compareTo(bookedBefore), 0,
            "the originally BOOKED stylist was credited for work billed to someone "
          + "else - two people are being paid for one haircut");
    }

    /**
     * API-E2E-135 one bill, two service lines, two stylists.
     *
     * A cut and a blow-dry by different people on the same invoice. Each line
     * carries its own staff_id, so each stylist must get exactly their own line
     * and not the whole bill.
     */
    @Test(description = "API-E2E-135 a two-stylist bill splits the credit by line")
    public void e2e135_twoStylistsOneBill() {
        LocalDate day = Steps.today();
        int first  = hire("QA E2E-135 first", "none", null);
        int second = hire("QA E2E-135 second", "none", null);

        NewCustomer customer = served("QA E2E-135");
        int billId = Steps.createBillLines(customer.id(), List.of(
                Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                       "unit_price", catalogue.servicePrice, "staff_id", first),
                Map.of("type", "service", "ref_id", catalogue.serviceId, "qty", 1,
                       "unit_price", catalogue.servicePrice, "staff_id", second)));
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        BigDecimal oneService = gross(1, 0, ZERO);
        BigDecimal wholeBill  = gross(2, 0, ZERO);

        BigDecimal firstGot  = Steps.staffFigure(SALON, first, "service_revenue");
        BigDecimal secondGot = Steps.staffFigure(SALON, second, "service_revenue");

        assertEquals(firstGot.compareTo(oneService), 0,
            "the first stylist got " + firstGot + " for one line of a " + wholeBill
          + " bill; expected " + oneService
          + (firstGot.compareTo(wholeBill) == 0
             ? " - they have been credited with the WHOLE bill including the other "
             + "stylist's line" : ""));
        assertEquals(secondGot.compareTo(oneService), 0,
            "the second stylist got " + secondGot + ", expected " + oneService);
    }

    @Test(description = "API-E2E-136 a product sale lands in product revenue, not service revenue")
    public void e2e136_productSale() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-136", "none", null);
        NewCustomer customer = served("QA E2E-136");

        int billId = Steps.createBill(customer.id(), catalogue, 0, 1, staffId, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        Map<String, Object> row = Steps.staffRow(SALON, staffId);
        BigDecimal productOnly = Money.netPayable(catalogue.productPrice,
                                                  catalogue.gstRate, catalogue.roundOffEnabled);

        assertEquals(new BigDecimal(row.get("product_revenue").toString())
                          .compareTo(productOnly), 0,
            "product revenue is " + row.get("product_revenue") + ", expected " + productOnly);
        assertEquals(new BigDecimal(row.get("service_revenue").toString()).compareTo(ZERO), 0,
            "no service was sold, but the row credits " + row.get("service_revenue")
          + " of SERVICE revenue for a product-only bill");
    }

    /**
     * API-E2E-137 a mixed basket, and the place the two reports disagree.
     *
     * The ROW splits the basket correctly: service_revenue 1,180 and
     * product_revenue 236. The SUMMARY card does not - its
     * total_service_revenue moves by the whole 1,416 (Finding A).
     *
     * This test pins both halves down. It is the case that turns Finding A from
     * "the staff report is wrong somewhere" into "the row is right and the card
     * is wrong", which is the difference between a bug report a backend
     * engineer can act on and one they cannot.
     */
    @Test(description = "API-E2E-137 a mixed basket: the row splits it, the summary card does not")
    public void e2e137_mixedBasketRowVersusCard() {
        LocalDate day = Steps.today();
        var before = snapshot();
        int staffId = hire("QA E2E-137", "none", null);

        NewCustomer customer = served("QA E2E-137");
        sell(customer, staffId, 1, 1);
        var after = snapshot();
        assertSameDay(day);

        BigDecimal wholeBill   = gross(1, 1, ZERO);
        BigDecimal serviceOnly = gross(1, 0, ZERO);

        // The row, which gets it right.
        Map<String, Object> row = Steps.staffRow(SALON, staffId);
        BigDecimal rowService = new BigDecimal(row.get("service_revenue").toString());
        BigDecimal rowProduct = new BigDecimal(row.get("product_revenue").toString());
        BigDecimal rowTotal   = new BigDecimal(row.get("total_revenue").toString());

        assertEquals(rowService.compareTo(serviceOnly), 0,
            "the ROW's service_revenue is " + rowService + ", expected " + serviceOnly);
        assertTrue(rowProduct.compareTo(ZERO) > 0,
            "the row shows no product revenue on a basket containing a product");
        assertTrue(Money.closeEnough(rowService.add(rowProduct), rowTotal),
            "the row does not add up: service " + rowService + " + product " + rowProduct
          + " should be total " + rowTotal);

        // The card, which does not - Finding A, asserted rather than described.
        BigDecimal cardMoved = Reports.moved(before, after,
                "staff_performance.total_service_revenue");
        assertEquals(cardMoved.compareTo(wholeBill), 0,
            "Finding A appears to have been FIXED: the summary card moved by "
          + cardMoved + " rather than the whole bill " + wholeBill + ". If the card "
          + "now moves by the service portion " + serviceOnly + ", delete this "
          + "assertion and close Finding A in docs/DEFECTS.md.");
        System.out.println("  [Finding A] row service_revenue " + rowService
                + " is correct; summary card moved " + cardMoved
                + " which is the whole bill.");
    }

    // =====================================================================
    // 138-143  commission
    // =====================================================================
    /**
     * API-E2E-138 a stylist on 10% commission.
     *
     * The row exposes both halves of the sum: commission_base (what the
     * commission is calculated ON) and commission_amount (what they are owed).
     * Measured on the live API: the base is the PRE-TAX subtotal, 1,000 for a
     * 1,000 service - not the 1,180 the customer paid. That distinction is
     * worth an assertion of its own, because paying 10% of the tax-inclusive
     * figure would over-pay every stylist by 18% of their commission.
     */
    @Test(description = "API-E2E-138 percentage commission is 10% of the pre-tax base")
    public void e2e138_percentageCommission() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-138", "percentage", 10);

        NewCustomer customer = served("QA E2E-138");
        sell(customer, staffId, 1, 0);
        assertSameDay(day);

        Map<String, Object> row = Steps.staffRow(SALON, staffId);
        BigDecimal base   = new BigDecimal(String.valueOf(row.get("commission_base")));
        BigDecimal earned = new BigDecimal(String.valueOf(row.get("commission_amount")));
        BigDecimal preTax = catalogue.servicePrice;

        assertEquals(base.compareTo(preTax), 0,
            "commission is being calculated on " + base + ". It should be the "
          + "PRE-TAX " + preTax + " - paying commission on the tax-inclusive "
          + gross(1, 0, ZERO) + " means paying the stylist a share of the "
          + "government's GST.");
        assertEquals(earned.compareTo(Money.round(base.multiply(new BigDecimal("0.10")))), 0,
            "a stylist on 10% of " + base + " should be owed "
          + Money.round(base.multiply(new BigDecimal("0.10"))) + ", the report says "
          + earned);
    }

    @Test(description = "API-E2E-139 flat commission is a fixed amount per bill")
    public void e2e139_flatCommission() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-139", "flat", 50);

        NewCustomer customer = served("QA E2E-139");
        sell(customer, staffId, 1, 0);
        assertSameDay(day);

        BigDecimal earned = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertTrue(earned.compareTo(ZERO) > 0,
            "a stylist on a flat 50 commission earned nothing on a completed sale");
        assertEquals(earned.compareTo(new BigDecimal("50")), 0,
            "a flat commission of 50 paid out " + earned + ". A flat amount must not "
          + "scale with the bill - if it did, it would be a percentage.");
    }

    /**
     * API-E2E-140 commission does not accrue on an unsettled bill.
     *
     * The draft is a basket, not a sale. A stylist owed commission the moment a
     * bill is drafted would be paid for work the customer never paid for.
     */
    @Test(description = "API-E2E-140 a draft bill earns no commission until it is paid")
    public void e2e140_noCommissionOnADraft() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-140", "percentage", 10);
        NewCustomer customer = served("QA E2E-140");

        int billId = Steps.createBill(customer.id(), catalogue, 1, 0, staffId, ZERO);
        BigDecimal onDraft = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertEquals(onDraft.compareTo(ZERO), 0,
            "the bill is still a draft but the stylist is already owed " + onDraft);

        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        BigDecimal onPaid = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertTrue(onPaid.compareTo(ZERO) > 0,
            "the bill was settled but no commission was recorded");
    }

    @Test(description = "API-E2E-141 commission across two bills adds up")
    public void e2e141_commissionAccumulates() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-141", "percentage", 10);

        sell(served("QA E2E-141 a"), staffId, 1, 0);
        BigDecimal afterOne = Steps.staffFigure(SALON, staffId, "commission_amount");

        sell(served("QA E2E-141 b"), staffId, 1, 0);
        BigDecimal afterTwo = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertSameDay(day);

        assertEquals(afterTwo.compareTo(Money.round(afterOne.multiply(new BigDecimal("2")))), 0,
            "two identical sales should owe twice one sale. After one: " + afterOne
          + ", after two: " + afterTwo);
    }

    /**
     * API-E2E-142 a refund must take the commission back.
     *
     * The sharpest money question in the block. If commission survives a refund,
     * the salon has paid a stylist for a sale it gave back - and because
     * commission is usually settled monthly, nobody notices until payroll.
     */
    @Test(description = "API-E2E-142 a refunded sale takes its commission with it")
    public void e2e142_refundReversesCommission() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-142", "percentage", 10);
        BigDecimal before = Steps.staffFigure(SALON, staffId, "commission_amount");

        NewCustomer customer = served("QA E2E-142");
        int billId = sell(customer, staffId, 1, 0);
        BigDecimal owed = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertTrue(owed.compareTo(before) > 0,
            "no commission was earned, so the reversal cannot be tested");

        Steps.refundInFull(billId, "QA E2E-142 refund");
        assertSameDay(day);

        BigDecimal after = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertEquals(after.compareTo(before), 0,
            "the sale was fully refunded but the stylist is still owed " + after
          + " (it was " + before + " before the sale, " + owed + " after it). The "
          + "salon is paying commission on money it has given back.");
    }

    /**
     * API-E2E-143 two stylists, two bills, one of them refunded.
     *
     * Only the refunded stylist loses their commission. A reversal that swept
     * the whole day, or credited the wrong person, shows up here and nowhere
     * else in the block.
     */
    @Test(description = "API-E2E-143 one refund among two stylists touches only one of them")
    public void e2e143_refundIsolatedToOneStylist() {
        LocalDate day = Steps.today();
        int refunded = hire("QA E2E-143 refunded", "percentage", 10);
        int kept     = hire("QA E2E-143 kept", "percentage", 10);

        int refundedBill = sell(served("QA E2E-143 a"), refunded, 1, 0);
        sell(served("QA E2E-143 b"), kept, 1, 0);

        BigDecimal keptOwed = Steps.staffFigure(SALON, kept, "commission_amount");
        assertTrue(keptOwed.compareTo(ZERO) > 0, "the second stylist earned nothing");

        Steps.refundInFull(refundedBill, "QA E2E-143 refund");
        assertSameDay(day);

        assertEquals(Steps.staffFigure(SALON, refunded, "commission_amount").compareTo(ZERO), 0,
            "the refunded stylist is still owed "
          + Steps.staffFigure(SALON, refunded, "commission_amount"));
        assertEquals(Steps.staffFigure(SALON, kept, "commission_amount").compareTo(keptOwed), 0,
            "refunding one stylist's bill changed a DIFFERENT stylist's commission, "
          + "from " + keptOwed + " to "
          + Steps.staffFigure(SALON, kept, "commission_amount"));
    }

    // =====================================================================
    // 146-150  the roster changes underneath the history
    // =====================================================================
    /**
     * API-E2E-146 a stylist is deactivated, then their existing appointment is
     * billed.
     *
     * People leave mid-day. The work already done must still be billable - a
     * salon that could not invoice a departed stylist's last customer would
     * lose real money.
     */
    @Test(description = "API-E2E-146 work by a stylist deactivated afterwards is still billable")
    public void e2e146_deactivatedMidJourney() {
        LocalDate day = Steps.today();
        var before = snapshot();
        int staffId = hire("QA E2E-146", "none", null);

        NewCustomer customer = arriveAsEnquiry("QA E2E-146");
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(staffId);
        int appointmentId = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, staffId)))
                .jsonPath().getInt("data.id");
        Steps.setAppointmentStatus(appointmentId, "completed");

        assertEquals(Steps.setStaffStatus(SALON, staffId, "inactive"), 200,
            "the stylist could not be deactivated");

        int billId = sell(customer, staffId, 1, 0);
        var after = snapshot();
        assertSameDay(day);

        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
    }

    /**
     * API-E2E-147 a deactivated stylist keeps their history.
     *
     * Deactivating is not deleting. The revenue they earned this morning has to
     * stay on the books this evening, or the day's takings change every time
     * somebody tidies the roster.
     */
    @Test(description = "API-E2E-147 deactivating a stylist does not erase what they earned")
    public void e2e147_historySurvivesDeactivation() {
        LocalDate day = Steps.today();
        int staffId = hire("QA E2E-147", "percentage", 10);

        NewCustomer customer = served("QA E2E-147");
        sell(customer, staffId, 1, 0);

        BigDecimal earnedWhileActive = Steps.staffFigure(SALON, staffId, "total_revenue");
        BigDecimal owedWhileActive   = Steps.staffFigure(SALON, staffId, "commission_amount");
        assertTrue(earnedWhileActive.compareTo(ZERO) > 0, "the sale was not recorded");

        assertEquals(Steps.setStaffStatus(SALON, staffId, "inactive"), 200,
            "the stylist could not be deactivated");
        assertSameDay(day);

        assertEquals(Steps.staffFigure(SALON, staffId, "total_revenue")
                          .compareTo(earnedWhileActive), 0,
            "deactivating the stylist changed their recorded revenue from "
          + earnedWhileActive + " to "
          + Steps.staffFigure(SALON, staffId, "total_revenue")
          + ". Today's takings must not depend on who is still on the roster.");
        assertEquals(Steps.staffFigure(SALON, staffId, "commission_amount")
                          .compareTo(owedWhileActive), 0,
            "deactivating the stylist changed the commission they are owed - money "
          + "already earned cannot disappear when somebody edits the roster");
    }

    @Test(description = "API-E2E-148 a stylist can only be booked for a service they are assigned")
    public void e2e148_serviceAssignmentGovernsBooking() {
        LocalDate day = Steps.today();

        // Hired for QA Haircut only, deliberately NOT for QA Blow Dry.
        int staffId = hire("QA E2E-148", "none", null);
        NewCustomer customer = arriveAsEnquiry("QA E2E-148");

        Catalogue.Booking ok = catalogue.nextFreeSlotFor(staffId);
        assertEquals(Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                ok.start(), ok.end(),
                List.of(Steps.line(catalogue.serviceId, staffId))).statusCode(), 200,
            "the stylist was refused for the service they ARE assigned to");

        Catalogue.Booking no = catalogue.nextFreeSlotFor(staffId);
        int refused = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                no.start(), no.end(),
                List.of(Steps.line(catalogue.secondServiceId(), staffId))).statusCode();
        assertSameDay(day);

        assertTrue(refused >= 400,
            "a stylist was booked for QA Blow Dry, which they are not assigned to "
          + "(status " + refused + "). The assignment list is not being enforced, so "
          + "a salon can book a colourist for a treatment they cannot perform.");
    }

    @Test(description = "API-E2E-149 adding a service to a stylist makes them bookable for it")
    public void e2e149_assignmentChangeTakesEffect() {
        LocalDate day = Steps.today();

        // Hired for BOTH from the start; the pair to 148, which proves the
        // refusal there was about the assignment and not about the service.
        int staffId = Steps.createStaff(SALON, "QA E2E-149 " + System.nanoTime() % 100000,
                "none", null, List.of(catalogue.serviceId, catalogue.secondServiceId()));

        NewCustomer customer = arriveAsEnquiry("QA E2E-149");
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(staffId);
        assertEquals(Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.secondServiceId(), staffId))).statusCode(), 200,
            "a stylist assigned to QA Blow Dry was still refused for it");

        int billId = Steps.createBillFully(customer.id(), catalogue, 0, 0, staffId,
                ZERO, ZERO, null, null, catalogue.secondServiceId(),
                catalogue.secondServicePrice());
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        BigDecimal expected = Money.netPayable(catalogue.secondServicePrice(),
                                               catalogue.gstRate, catalogue.roundOffEnabled);
        assertEquals(Steps.staffFigure(SALON, staffId, "service_revenue").compareTo(expected), 0,
            "the stylist was credited "
          + Steps.staffFigure(SALON, staffId, "service_revenue")
          + " for a " + expected + " QA Blow Dry");
    }

    /**
     * API-E2E-150 the closing case: three stylists, three customers, three
     * bills, and the sum has to reconcile.
     *
     * Each row must hold exactly its own sale, and the three rows together must
     * equal what the salon's Sales report says it took. That is the check a
     * salon owner actually performs at the end of a day, and it is the one that
     * catches a report that double-counts or drops a line.
     */
    @Test(description = "API-E2E-150 three stylists, three sales, and the rows reconcile with Sales")
    public void e2e150_threeStylistsReconcile() {
        LocalDate day = Steps.today();
        var before = snapshot();

        int[] staff = { hire("QA E2E-150 a", "none", null),
                        hire("QA E2E-150 b", "none", null),
                        hire("QA E2E-150 c", "none", null) };

        for (int i = 0; i < staff.length; i++) {
            sell(served("QA E2E-150 " + i), staff[i], 1, 0);
        }
        var after = snapshot();
        assertSameDay(day);

        BigDecimal each = gross(1, 0, ZERO);
        BigDecimal rowsTotal = ZERO;
        for (int id : staff) {
            BigDecimal got = Steps.staffFigure(SALON, id, "total_revenue");
            assertEquals(got.compareTo(each), 0,
                "stylist " + id + " should hold exactly one " + each + " sale, holds " + got);
            rowsTotal = rowsTotal.add(got);
        }

        BigDecimal salesMoved = Reports.moved(before, after, "sales.total_revenue");
        assertEquals(rowsTotal.compareTo(salesMoved), 0,
            "the three stylists' rows add up to " + rowsTotal + " but Sales moved by "
          + salesMoved + ". The staff report and the sales report disagree about the "
          + "same three bills.");
        expectMoved(before, after, "sales.bills_count", new BigDecimal("3"));
    }
}

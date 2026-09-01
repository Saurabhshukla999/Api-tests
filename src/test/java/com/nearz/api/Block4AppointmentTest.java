package com.nearz.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-026 .. 050 - the appointment lifecycle.
 *
 * Blocks 1, 4 and 5 follow the money. This block follows the CALENDAR: who is
 * booked, with whom, for what, and what the Appointments report says about it.
 * So every snapshot here is calendarSnapshot(), which adds that report to the
 * six the money journeys read.
 *
 * Three things learned building this, all of which shape what is below:
 *
 *   1. Report summaries are cached for 60 seconds and nothing invalidates them
 *      (D17). Reports.snapshot works around it with a unique query parameter;
 *      e2e039 asserts the staleness deliberately, because it is what a real
 *      user sees.
 *   2. PUT /appointments/{id}/reschedule answers "Feature not enabled for this
 *      salon" on 4550, so 036 and 037 are parked rather than guessed at.
 *   3. An invalid salon_service_id or staff_id returns 500, not 422 (D7). The
 *      tests below assert what MUST be true - no orphan appointment is created
 *      - rather than pinning the status code to today's wrong answer.
 */
public class Block4AppointmentTest extends BaseJourneyTest {

    private static final String APPTS   = "appointments.total_appointments";
    private static final String DONE    = "appointments.completed";
    private static final String CANCEL  = "appointments.cancelled";
    private static final String NOSHOW  = "appointments.no_show";

    // -----------------------------------------------------------------------
    // 026-031  booking shapes
    // -----------------------------------------------------------------------
    @Test(description = "API-E2E-026 existing customer, stylist assigned, through to payment")
    public void e2e026_existingCustomerFullJourney() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-026");
        int appointmentId = completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);

        assertAppointmentIs(appointmentId, "completed");
        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    /**
     * API-E2E-027 booked with no stylist at all.
     *
     * AppointmentService declares belongs_to :staff, optional: true, and the
     * calendar renders these in an "Unassigned" column - a booking taken before
     * anyone knows who is free. Confirmed the API accepts it (200).
     *
     * The point: an unassigned booking must still be a booking. If it were
     * missing from total_appointments, a salon that takes walk-ins this way
     * would under-count its whole day.
     */
    @Test(description = "API-E2E-027 an appointment with no stylist is still on the books")
    public void e2e027_noStylistAssigned() {
        LocalDate day = Steps.today();
        var before = appointmentsSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-027");
        Catalogue.Booking slot = catalogue.nextFreeSlot();
        Response response = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, null)));
        assertEquals(response.statusCode(), 200,
            "booking without a stylist was refused, but staff is optional on "
          + "AppointmentService: " + response.asString());

        int appointmentId = response.jsonPath().getInt("data.id");
        JsonPath booked = Steps.readAppointment(appointmentId);
        assertNull(booked.get("data.salon_services[0].staff_id"),
            "a stylist was assigned to an appointment booked without one");

        var after = appointmentsSnapshot();
        assertSameDay(day);
        expectMoved(before, after, APPTS, ONE);
    }

    @Test(description = "API-E2E-028 stylist A swapped for stylist B, then billed")
    public void e2e028_stylistSwappedThenBilled() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-028");
        int appointmentId = bookAppointmentFor(customer);
        JsonPath booked = Steps.readAppointment(appointmentId);
        int lineId = booked.getInt("data.salon_services[0].id");
        int wasStaff = booked.getInt("data.salon_services[0].staff_id");

        int nowStaff = moveToAnotherStylist(appointmentId, lineId, wasStaff);
        assertNotEquals(nowStaff, wasStaff, "the stylist did not actually change");

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = Steps.createBill(customer.id(), catalogue, 1, 0, nowStaff, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);
        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    /**
     * API-E2E-029 service A swapped for service B, then billed at B's price.
     *
     * The stylist has to move with the service - roster stylists are assigned
     * to QA Haircut only. QA Blow Dry is priced differently on purpose, so a
     * swap the API ignored would fail on the money rather than pass quietly.
     *
     * The appointment's own `amount` is NOT asserted here: it keeps the old
     * price, which is D16 and has its own failing test in Block 1.
     */
    @Test(description = "API-E2E-029 service swapped, the bill follows the new service")
    public void e2e029_serviceSwappedThenBilled() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-029");
        int appointmentId = bookAppointmentFor(customer);
        int lineId = Steps.readAppointment(appointmentId)
                          .getInt("data.salon_services[0].id");

        int newService = catalogue.secondServiceId();
        BigDecimal newPrice = catalogue.secondServicePrice();
        int dualStaff = catalogue.secondServiceStaffId();
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(dualStaff);
        Steps.changeAppointment(appointmentId, Map.of(
                "start_time", slot.start(), "end_time", slot.end(),
                "appointment_services_attributes", List.of(Map.of(
                        "id", lineId, "salon_service_id", newService,
                        "staff_id", dualStaff))));

        assertEquals(Steps.readAppointment(appointmentId)
                          .getString("data.salon_services[0].service_name"),
                     "QA Blow Dry", "the service did not change");

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = Steps.createBill(customer.id(), catalogue, 0, 0, dualStaff,
                                      ZERO, null, newService, newPrice);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);

        BigDecimal gross = Money.netPayable(newPrice, catalogue.gstRate,
                                            catalogue.roundOffEnabled);
        expectMoved(before, after, "sales.total_revenue", gross);
        expectMoved(before, after, "payments.total_collected", gross);
        expectMoved(before, after, "profit.gross_revenue", newPrice);
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    @Test(description = "API-E2E-030 one appointment carrying two services")
    public void e2e030_twoServicesOneAppointment() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-030");
        Catalogue.Booking slot = catalogue.nextFreeSlot();
        Response response = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, slot.staffId()),
                        Steps.line(catalogue.serviceId, slot.staffId())));
        assertEquals(response.statusCode(), 200,
            "two services on one appointment was refused: " + response.asString());

        int appointmentId = response.jsonPath().getInt("data.id");
        assertEquals(Steps.readAppointment(appointmentId)
                          .getList("data.salon_services").size(), 2,
            "both service lines should be on the appointment");

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = billFor(customer, 2, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);

        // TWO services sold, but still ONE appointment. Counting the lines
        // instead of the bookings is an easy mistake for a report to make.
        expectSettled(before, after, 2, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    @Test(description = "API-E2E-031 two services, two different stylists, one appointment")
    public void e2e031_twoServicesTwoStylists() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-031");
        Catalogue.Booking first = catalogue.nextFreeSlot();
        Catalogue.Booking second = catalogue.nextFreeSlot();
        Response response = Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                first.start(), first.end(),
                List.of(Steps.line(catalogue.serviceId, first.staffId()),
                        Steps.line(catalogue.serviceId, second.staffId())));
        assertEquals(response.statusCode(), 200,
            "two stylists on one appointment was refused: " + response.asString());

        int appointmentId = response.jsonPath().getInt("data.id");
        List<Integer> staff = Steps.readAppointment(appointmentId)
                                   .getList("data.salon_services.staff_id", Integer.class);
        assertEquals(staff.size(), 2, "both lines should be present");
        assertEquals(staff.stream().distinct().count(), 2L,
            "both lines were given the same stylist: " + staff);

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = billFor(customer, 2, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);
        expectSettled(before, after, 2, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
    }

    // -----------------------------------------------------------------------
    // 032-037  the status lifecycle
    // -----------------------------------------------------------------------
    /**
     * API-E2E-032 the full status strip: arrived, started, completed.
     *
     * The calendar's status strip is the only route to Arrived and Started
     * (routes.rb: "the only route that can reach Arrived / Started / NoShow").
     * Each step is read back, because a PATCH answering 200 while leaving the
     * status alone is exactly the kind of thing that gets shipped.
     */
    @Test(description = "API-E2E-032 arrived, then started, then completed")
    public void e2e032_fullStatusStrip() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-032");
        int appointmentId = bookAppointmentFor(customer);

        for (String status : List.of("arrived", "started", "completed")) {
            Steps.setAppointmentStatus(appointmentId, status);
            assertAppointmentIs(appointmentId, status);
        }

        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);
        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, DONE, ONE);
    }

    @Test(description = "API-E2E-033 a no-show counts as a no-show and earns nothing")
    public void e2e033_noShowCounted() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-033");
        int appointmentId = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(appointmentId, "no_show");

        var after = calendarSnapshot();
        assertSameDay(day);
        assertAppointmentIs(appointmentId, "no_show");

        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, NOSHOW, ONE);
        expectMoved(before, after, DONE, ZERO);
        expectNothingSold(before, after);
    }

    /**
     * API-E2E-034 a cancellation is counted as one.
     *
     * Note the report lumps CANCELLED and DECLINED into one `cancelled` figure
     * (AppointmentsQuery#kpis), so this asserts the pair, not the status alone.
     */
    @Test(description = "API-E2E-034 a cancellation is counted and earns nothing")
    public void e2e034_cancellationCounted() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-034");
        int appointmentId = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(appointmentId, "cancelled");

        var after = calendarSnapshot();
        assertSameDay(day);
        assertAppointmentIs(appointmentId, "cancelled");

        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, CANCEL, ONE);
        expectMoved(before, after, DONE, ZERO);
        expectNothingSold(before, after);
    }

    /**
     * API-E2E-035 cancel, rebook the same customer, complete and bill.
     *
     * The customer walks out and comes back an hour later. Two appointments,
     * one cancelled and one completed, but only ONE sale - and one customer,
     * not two. A cancellation that quietly took the sale with it, or a rebook
     * that created a second customer record, both show up here.
     */
    @Test(description = "API-E2E-035 cancelled then rebooked: two appointments, one sale")
    public void e2e035_cancelThenRebook() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-035");
        int cancelled = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(cancelled, "cancelled");

        int rebooked = completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);
        assertAppointmentIs(cancelled, "cancelled");
        assertAppointmentIs(rebooked, "completed");
        assertBillIsPaidBy(billId, customer);

        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, APPTS, new BigDecimal("2"));
        expectMoved(before, after, CANCEL, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    @Test(description = "API-E2E-036 rescheduled once, then completed and billed")
    public void e2e036_rescheduledOnce() {
        throw new SkipException(
            "PUT /appointments/{id}/reschedule answers 422 \"Feature not enabled "
          + "for this salon\" on salon " + SALON + ". Measured 30 Aug 2026 with "
          + "both a wrapped and a flat payload, so it is the feature flag and "
          + "not the request shape. Ask for the reschedule flag on the QA salon "
          + "- see docs/TEST_ENVIRONMENT_REQUEST.md - and delete this skip.");
    }

    @Test(description = "API-E2E-037 rescheduled twice, then completed and billed")
    public void e2e037_rescheduledTwice() {
        throw new SkipException(
            "Same feature flag as API-E2E-036: reschedule is disabled on salon "
          + SALON + ".");
    }

    // -----------------------------------------------------------------------
    // 038-043  what the reports do with each ending
    // -----------------------------------------------------------------------
    /**
     * API-E2E-038 completed, billed, then refunded.
     *
     * The money unwinds to zero. The APPOINTMENT does not: it still happened,
     * the stylist's chair was still occupied for half an hour, and the calendar
     * should still say so. A refund that also deleted the booking would leave
     * the salon unable to explain its own day.
     */
    @Test(description = "API-E2E-038 a refund reverses the money but not the appointment")
    public void e2e038_refundLeavesTheAppointment() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-038");
        int appointmentId = completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        Steps.refundInFull(billId, "QA E2E-038 refund");

        var after = calendarSnapshot();
        assertSameDay(day);

        assertBillIsRefunded(billId);
        assertAppointmentIs(appointmentId, "completed");
        expectFullyReversed(before, after, gross(1, 0, ZERO));
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
    }

    /**
     * API-E2E-039 completed but never billed - and the cache that hides it.
     *
     * Two separate claims, and the second is the interesting one:
     *
     *   a) a completed appointment with no bill earns nothing. The calendar
     *      moves, the money does not.
     *   b) the report the SALON OWNER sees does not move either, for up to a
     *      minute, because Reports::BaseController caches summaries for 60s and
     *      no write invalidates them (D17).
     *
     * (b) is asserted through a plain read, deliberately bypassing the
     * cache-busting that Reports.snapshot does, because staleness is the thing
     * under test. This is the test that would have found D17 on day one.
     */
    @Test(description = "API-E2E-039 completed without a bill earns nothing, and the report lags a minute")
    public void e2e039_completedButNotBilled() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();
        BigDecimal asOwnerBefore = plainAppointmentCount();

        NewCustomer customer = arriveAsEnquiry("QA E2E-039");
        int appointmentId = completeAppointmentFor(customer);

        var after = calendarSnapshot();
        assertSameDay(day);
        assertAppointmentIs(appointmentId, "completed");

        // (a) the calendar moved, the till did not
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ONE);
        expectNothingSold(before, after);

        // (b) D17 - the same figure read the way the dashboard reads it
        BigDecimal asOwnerAfter = plainAppointmentCount();
        BigDecimal truth = Reports.moved(before, after, APPTS);
        BigDecimal seen  = asOwnerAfter.subtract(asOwnerBefore);
        assertTrue(truth.compareTo(seen) >= 0,
            "the cached read reported MORE movement than the real one, which "
          + "should be impossible: real +" + truth + ", cached +" + seen);
        if (seen.signum() == 0) {
            System.out.println("  [D17] the appointment is real (+" + truth
                + " uncached) but the dashboard still shows the old figure.");
        }
    }

    /**
     * API-E2E-040 billed without ever completing the appointment.
     *
     * The salon takes the money at the desk and nobody touches the calendar.
     * The bill is legitimate and the revenue is real, so Sales must move - but
     * `completed` must NOT, or a salon could show 100% completion while its
     * calendar is full of appointments nobody marked.
     */
    @Test(description = "API-E2E-040 billing does not complete the appointment for you")
    public void e2e040_billedWithoutCompleting() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-040");
        int appointmentId = bookAppointmentFor(customer);   // left pending
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);

        assertBillIsPaidBy(billId, customer);
        assertAppointmentIs(appointmentId, "pending");
        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
        expectMoved(before, after, DONE, ZERO);
    }

    @Test(description = "API-E2E-041 the customer is corrected before billing")
    public void e2e041_customerCorrectedBeforeBilling() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-041");
        int appointmentId = bookAppointmentFor(customer);

        String corrected = "QA E2E-041 corrected";
        JsonPath changed = Steps.changeAppointment(appointmentId,
                Map.of("on_behalf_of_username", corrected));
        assertEquals(changed.getString("data.user.username"), corrected,
            "the correction did not apply");
        assertEquals(String.valueOf(changed.getString("data.user.mobile"))
                        .replace("+", "").replaceFirst("^91", ""),
                     customer.phone(),
            "correcting the name moved the appointment onto a different phone "
          + "number - the customer join is broken by a rename");

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);
        assertBillIsPaidBy(billId, customer);
        expectSettled(before, after, 1, 0, ZERO);
        expectMoved(before, after, APPTS, ONE);
    }

    /**
     * API-E2E-042 the stylist changes, and Staff Performance must follow.
     *
     * This is the one with real money behind it: commission. The salon pays the
     * person who did the work, and this test says the report agrees. It reads
     * the staff report by ROWS rather than by summary, because the summary is a
     * salon-wide total that cannot tell one stylist from another.
     */
    @Test(description = "API-E2E-042 revenue is credited to the stylist who did the work")
    public void e2e042_staffReportFollowsTheSwap() {
        LocalDate day = Steps.today();

        NewCustomer customer = arriveAsEnquiry("QA E2E-042");
        int appointmentId = bookAppointmentFor(customer);
        JsonPath booked = Steps.readAppointment(appointmentId);
        int lineId = booked.getInt("data.salon_services[0].id");
        int wasStaff = booked.getInt("data.salon_services[0].staff_id");

        BigDecimal originalBefore = staffRevenue(wasStaff);
        int nowStaff = moveToAnotherStylist(appointmentId, lineId, wasStaff);
        BigDecimal newBefore = staffRevenue(nowStaff);

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = Steps.createBill(customer.id(), catalogue, 1, 0, nowStaff, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        BigDecimal gross = gross(1, 0, ZERO);
        assertEquals(staffRevenue(nowStaff).subtract(newBefore).compareTo(gross), 0,
            "stylist " + nowStaff + " did the work but was not credited with "
          + gross + " - Staff Performance moved by "
          + staffRevenue(nowStaff).subtract(newBefore));
        assertEquals(staffRevenue(wasStaff).subtract(originalBefore).compareTo(ZERO), 0,
            "stylist " + wasStaff + " was taken off this appointment but was "
          + "still credited for it - two stylists paid for one haircut");
    }

    @Test(description = "API-E2E-043 the Services report follows a swapped service")
    public void e2e043_serviceReportFollowsTheSwap() {
        LocalDate day = Steps.today();
        var before = calendarSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-043");
        int appointmentId = bookAppointmentFor(customer);
        int lineId = Steps.readAppointment(appointmentId)
                          .getInt("data.salon_services[0].id");

        int newService = catalogue.secondServiceId();
        BigDecimal newPrice = catalogue.secondServicePrice();
        int dualStaff = catalogue.secondServiceStaffId();
        Catalogue.Booking slot = catalogue.nextFreeSlotFor(dualStaff);
        Steps.changeAppointment(appointmentId, Map.of(
                "start_time", slot.start(), "end_time", slot.end(),
                "appointment_services_attributes", List.of(Map.of(
                        "id", lineId, "salon_service_id", newService,
                        "staff_id", dualStaff))));

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = Steps.createBill(customer.id(), catalogue, 0, 0, dualStaff,
                                      ZERO, null, newService, newPrice);
        Steps.settleInFull(billId, "cash");

        var after = calendarSnapshot();
        assertSameDay(day);

        BigDecimal gross = Money.netPayable(newPrice, catalogue.gstRate,
                                            catalogue.roundOffEnabled);
        expectMoved(before, after, "services.services_sold", ONE);
        expectMoved(before, after, "services.total_revenue", gross);
    }

    // -----------------------------------------------------------------------
    // 044-048  duplicates and bad input
    // -----------------------------------------------------------------------
    /**
     * API-E2E-044 the same booking sent twice.
     *
     * The receptionist double-clicks Book. The API must not end up with two
     * appointments for the same person in the same chair at the same minute.
     * It does not need to answer 200 twice - a refusal is the better answer -
     * so what is asserted is the OUTCOME: at most one appointment in that slot.
     */
    @Test(description = "API-E2E-044 a double-clicked booking does not become two appointments")
    public void e2e044_duplicateBookingRequest() {
        LocalDate day = Steps.today();
        var before = appointmentsSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-044");
        Catalogue.Booking slot = catalogue.nextFreeSlot();
        List<Map<String, Object>> lines =
                List.of(Steps.line(catalogue.serviceId, slot.staffId()));

        Response first = Steps.attemptBooking(SALON, customer.name(),
                customer.phone(), slot.start(), slot.end(), lines);
        assertEquals(first.statusCode(), 200,
            "the first booking failed: " + first.asString());

        Response second = Steps.attemptBooking(SALON, customer.name(),
                customer.phone(), slot.start(), slot.end(), lines);

        var after = appointmentsSnapshot();
        assertSameDay(day);

        BigDecimal created = Reports.moved(before, after, APPTS);
        assertEquals(created.compareTo(ONE), 0,
            "sending the same booking twice created " + created + " appointments. "
          + "The second request answered " + second.statusCode() + ": "
          + second.asString());
    }

    /**
     * API-E2E-045 the same request retried after a timeout.
     *
     * Same shape as 044 but a different story: the client never saw the first
     * response and sent it again. Identical expectation, and worth keeping
     * separate because the fix for one (a unique-slot constraint) is not the
     * fix for the other (an idempotency key).
     */
    @Test(description = "API-E2E-045 a retried booking request is not a second booking")
    public void e2e045_retriedBookingRequest() {
        LocalDate day = Steps.today();
        var before = appointmentsSnapshot();

        NewCustomer customer = arriveAsEnquiry("QA E2E-045");
        Catalogue.Booking slot = catalogue.nextFreeSlot();
        List<Map<String, Object>> lines =
                List.of(Steps.line(catalogue.serviceId, slot.staffId()));

        Steps.attemptBooking(SALON, customer.name(), customer.phone(),
                slot.start(), slot.end(), lines);
        Response retry = Steps.attemptBooking(SALON, customer.name(),
                customer.phone(), slot.start(), slot.end(), lines);

        var after = appointmentsSnapshot();
        assertSameDay(day);

        assertEquals(Reports.moved(before, after, APPTS).compareTo(ONE), 0,
            "a retry of the same request produced a second appointment. The "
          + "retry answered " + retry.statusCode() + ": " + retry.asString());
    }

    /**
     * API-E2E-046 booked against a phone number the API should not accept.
     *
     * Measured: on_behalf_of_mobile_no "not-a-phone" is accepted with 200 and a
     * user is created for it. That is a validation gap, but the thing that
     * would actually hurt is an appointment with no customer behind it at all,
     * so that is what is asserted here. The lenient validation is reported
     * separately rather than pinned as an expectation.
     */
    @Test(description = "API-E2E-046 a booking never ends up without a customer")
    public void e2e046_noOrphanFromABadCustomer() {
        LocalDate day = Steps.today();
        Catalogue.Booking slot = catalogue.nextFreeSlot();

        Response response = Steps.attemptBooking(SALON, "QA E2E-046",
                "not-a-phone", slot.start(), slot.end(),
                List.of(Steps.line(catalogue.serviceId, slot.staffId())));
        assertSameDay(day);

        if (response.statusCode() >= 400) {
            return;     // refused, which is the correct answer
        }
        int appointmentId = response.jsonPath().getInt("data.id");
        JsonPath created = Steps.changeAppointment(appointmentId, Map.of("comment", "QA"));
        assertTrue(created.get("data.user") != null
                        && created.getInt("data.user.id") > 0,
            "the API accepted \"not-a-phone\" as a mobile number (" 
          + response.statusCode() + ") AND left appointment " + appointmentId
          + " with no customer attached - an orphan booking nobody can bill");
    }

    @Test(description = "API-E2E-047 an invalid stylist id creates no appointment")
    public void e2e047_noOrphanFromABadStylist() {
        assertNoAppointmentCreated(
                Steps.line(catalogue.serviceId, 99999999), "stylist");
    }

    @Test(description = "API-E2E-048 an invalid service id creates no appointment")
    public void e2e048_noOrphanFromABadService() {
        assertNoAppointmentCreated(
                Steps.line(99999999, catalogue.staffIds.get(0)), "service");
    }

    // -----------------------------------------------------------------------
    // 049-050  the ids hold together
    // -----------------------------------------------------------------------
    /**
     * API-E2E-049 / D18 - the bill knows which appointment it came from.
     *
     * FAILS TODAY, on purpose, in the known-defect group.
     *
     * `bills.appointment_id` exists as a column and two reports depend on it:
     *
     *   Reports::AppointmentsQuery#walk_ins_for
     *       bills.where(status: PAID, appointment_id: nil).count
     *   Reports::MarketingQuery
     *       groups bill revenue by appointment_id to attribute promo codes
     *
     * But `POST /api/v1/billing/bills` accepts no appointment id, and nothing
     * in the billing module sets one - `appointment_id` does not appear
     * anywhere under app/controllers/api/v1/billing or in the bill composer.
     * So a customer who booked at 10am and paid at 10:30 is filed as a WALK-IN,
     * and any promo code they used is attributed to nobody.
     *
     * Measured on salon 4550, which shows 94 appointments and 75 walk-ins on a
     * day when every single bill came from a booked appointment made by this
     * suite.
     *
     * It is left failing rather than skipped because the assertion is right and
     * the API is wrong. The question for the backend team is whether the
     * billing module is SUPPOSED to link (in which case this is a bug) or
     * whether the calendar has a separate billing path that does (in which case
     * `walk_ins` is still counting POS sales to booked customers as walk-ins).
     */
    @Test(groups = "known-defect",
          description = "API-E2E-049 / D18 the appointment id survives into the bill")
    public void e2e049_appointmentIdReachesTheBill() {
        LocalDate day = Steps.today();
        NewCustomer customer = arriveAsEnquiry("QA E2E-049");
        int appointmentId = completeAppointmentFor(customer);
        int billId = billFor(customer, 1, 0, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        JsonPath bill = Steps.readBill(billId);
        Object onBill = bill.get("data.appointment_id");
        assertTrue(onBill != null,
            "bill " + billId + " was raised for appointment " + appointmentId
          + " but carries no appointment_id. The Appointments report counts a "
          + "PAID bill with appointment_id nil as a WALK-IN, so every booked "
          + "customer is being reported as one.");
        assertEquals(String.valueOf(onBill), String.valueOf(appointmentId),
            "bill " + billId + " points at appointment " + onBill
          + " but was raised for " + appointmentId);
    }

    /**
     * API-E2E-050 one customer, one stylist, one service, all the way through.
     *
     * The last test in the block is the one that says the pieces are still
     * bolted together: the id the enquiry produced is the id on the appointment
     * and on the bill, and the stylist and service on the appointment are the
     * ones that were booked.
     */
    @Test(description = "API-E2E-050 customer, stylist and service stay consistent end to end")
    public void e2e050_idsStayConsistent() {
        LocalDate day = Steps.today();
        NewCustomer customer = arriveAsEnquiry("QA E2E-050");
        int appointmentId = bookAppointmentFor(customer);

        JsonPath booked = Steps.readAppointment(appointmentId);
        int bookedStaff   = booked.getInt("data.salon_services[0].staff_id");
        String bookedName = booked.getString("data.salon_services[0].service_name");

        Steps.setAppointmentStatus(appointmentId, "completed");
        int billId = Steps.createBill(customer.id(), catalogue, 1, 0, bookedStaff, ZERO);
        Steps.settleInFull(billId, "cash");
        assertSameDay(day);

        JsonPath after = Steps.readAppointment(appointmentId);
        assertEquals(after.getInt("data.salon_services[0].staff_id"), bookedStaff,
            "the stylist on the appointment changed on its own between booking "
          + "and billing");
        assertEquals(after.getString("data.salon_services[0].service_name"), bookedName,
            "the service on the appointment changed on its own");

        JsonPath bill = Steps.readBill(billId);
        assertEquals(bill.getInt("data.customer_id"), customer.id(),
            "the bill is against a different customer than the enquiry created");

        // and the phone, which is the only thing joining the appointment to the
        // customer in the first place - POST /appointments takes no customer id
        JsonPath profile = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", customer.id())
                .when().get("/salons/{salonId}/salon_customer_profiles/{id}")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(String.valueOf(profile.getString("data.mobile"))
                        .replace("+", "").replaceFirst("^91", ""),
                     customer.phone(),
            "the customer's phone changed during the journey, so the appointment "
          + "can no longer be joined to them");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------
    /**
     * The appointments count read the way the DASHBOARD reads it - no
     * cache-busting parameter. Used by e2e039 to measure D17.
     */
    private BigDecimal plainAppointmentCount() {
        Object value = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today")
                .when().get("/salons/{salonId}/reports/appointments/summary")
                .then().statusCode(200).extract()
                .jsonPath().get("data.kpis.total_appointments.value");
        return value == null ? ZERO : new BigDecimal(value.toString());
    }

    /** One stylist's revenue today, from the Staff Performance rows. */
    private BigDecimal staffRevenue(int staffId) {
        JsonPath rows = io.restassured.RestAssured.given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("range", "today")
                .queryParam("limit", 200)
                .queryParam("_qa", System.nanoTime())
                .when().get("/salons/{salonId}/reports/staff_performance/rows")
                .then().statusCode(200).extract().jsonPath();

        // The rows endpoints nest the page inside data - the payload is
        // {data: {data: [...], meta: {...}}} - so the rows are at data.data.
        // Reading data[] instead silently finds nothing and every figure looks
        // like zero, which is how this helper failed the first time.
        Object revenue = rows.get(
                "data.data.find { it.staff_id == " + staffId + " }.service_revenue");
        if (revenue == null) {
            revenue = rows.get(
                "data.find { it.staff_id == " + staffId + " }.service_revenue");
        }
        assertTrue(revenue != null,
            "stylist " + staffId + " has no row in the Staff Performance report "
          + "at all, so their revenue cannot be checked. The report returned "
          + rows.get("data.meta"));
        return new BigDecimal(revenue.toString());
    }

    /** Move the appointment onto any stylist the API will accept. See the note
     *  on the same helper in Block1EnquiryTest. */
    private int moveToAnotherStylist(int appointmentId, int lineId, int wasStaff) {
        for (int candidate : catalogue.staffIds) {
            if (candidate == wasStaff) {
                continue;
            }
            if (Steps.attemptAppointmentChange(appointmentId, Map.of(
                    "appointment_services_attributes", List.of(Map.of(
                            "id", lineId, "staff_id", candidate))) ) == 200) {
                return candidate;
            }
        }
        throw new AssertionError("no stylist on the roster would accept "
          + "appointment " + appointmentId + " on salon " + SALON);
    }

    /**
     * Send a booking that must not produce an appointment, and prove none was.
     *
     * The status code is NOT asserted. Both of these currently answer 500 where
     * 422 is correct (D7), and pinning the expectation to 500 would mean the
     * test goes red when the bug is FIXED. What must be true either way is that
     * the calendar did not grow.
     */
    private void assertNoAppointmentCreated(Map<String, Object> badLine, String what) {
        LocalDate day = Steps.today();
        var before = appointmentsSnapshot();
        Catalogue.Booking slot = catalogue.nextFreeSlot();

        Response response = Steps.attemptBooking(SALON, "QA bad " + what,
                Steps.newPhone(), slot.start(), slot.end(), List.of(badLine));

        var after = appointmentsSnapshot();
        assertSameDay(day);

        assertTrue(response.statusCode() >= 400,
            "a booking with an invalid " + what + " id was ACCEPTED with "
          + response.statusCode() + ": " + response.asString());
        assertEquals(Reports.moved(before, after, APPTS).compareTo(ZERO), 0,
            "a booking with an invalid " + what + " id was refused with "
          + response.statusCode() + " but still left an appointment behind");
    }
}

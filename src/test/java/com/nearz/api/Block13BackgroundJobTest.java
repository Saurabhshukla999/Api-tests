package com.nearz.api;

import io.restassured.specification.RequestSpecification;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-220..223: the jobs that run after a booking. LOCAL DOCKER ONLY.
 *
 * Creating an appointment queues AppointmentAutoConfirmJob for 15 minutes
 * later; confirming one queues AppointmentReminderJob for 30 minutes before
 * the slot (backend app/models/appointment.rb). Waiting for either is not a
 * test, so each case books through the API, runs the job NOW inside the web
 * container (LocalBackend.performNow), then checks the outcome through the
 * API again - the appointment's status and the customer's own notifications,
 * read with a token minted for that customer.
 *
 * Rules, from new_master_aws app/jobs:
 *   auto-confirm: pending -> confirmed; any other status is left alone
 *   reminder:     confirmed and not yet reminded -> "NEARZ Upcoming Appointment",
 *                 once (reminder_sent); anything else is left alone
 *
 * Skipped unless the base URL is localhost.
 */
public class Block13BackgroundJobTest extends BaseJourneyTest {

    private static final String AUTO_CONFIRM = "AppointmentAutoConfirmJob";
    private static final String REMINDER = "AppointmentReminderJob";
    private static final String CONFIRMATION = "NEARZ Booking Confirmation";
    private static final String UPCOMING = "NEARZ Upcoming Appointment";

    @BeforeMethod(alwaysRun = true)
    public void localOnly() {
        LocalBackend.require();
    }

    @Test(description = "API-E2E-220 auto-confirm confirms a pending booking and tells the customer")
    public void e2e220_autoConfirmConfirmsPending() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-220");
        int appointmentId = bookAppointmentFor(customer);
        assertEquals(Steps.appointmentStatus(appointmentId), "pending",
            "a new booking should start pending, or there is nothing to auto-confirm");

        LocalBackend.performNow(AUTO_CONFIRM, appointmentId);

        assertEquals(Steps.appointmentStatus(appointmentId), "confirmed",
            "the auto-confirm job left appointment " + appointmentId + " unconfirmed");
        RequestSpecification asCustomer = LocalBackend.asCustomerOf(appointmentId);
        Map<String, Object> note = Steps.notificationAbout(asCustomer, appointmentId, CONFIRMATION);
        assertNotNull(note, "the customer was not told the booking is confirmed");
        assertTrue(String.valueOf(note.get("body")).contains("is confirmed by"),
            "unexpected confirmation text: " + note.get("body"));
    }

    @Test(description = "API-E2E-221 auto-confirm leaves a cancelled booking cancelled")
    public void e2e221_autoConfirmIgnoresCancelled() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-221");
        int appointmentId = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(appointmentId, "cancelled");

        LocalBackend.performNow(AUTO_CONFIRM, appointmentId);

        assertEquals(Steps.appointmentStatus(appointmentId), "cancelled",
            "the auto-confirm job revived a cancelled booking");
        assertEquals(Steps.countNotificationsAbout(
                LocalBackend.asCustomerOf(appointmentId), appointmentId, CONFIRMATION), 0L,
            "a cancelled booking's customer was told it is confirmed");
    }

    @Test(description = "API-E2E-222 the reminder reaches the customer of a confirmed booking exactly once")
    public void e2e222_reminderSentOnce() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-222");
        int appointmentId = bookAppointmentFor(customer);
        Steps.setAppointmentStatus(appointmentId, "confirmed");

        LocalBackend.performNow(REMINDER, appointmentId);
        LocalBackend.performNow(REMINDER, appointmentId);   // a Sidekiq retry, or a duplicate enqueue

        RequestSpecification asCustomer = LocalBackend.asCustomerOf(appointmentId);
        Map<String, Object> note = Steps.notificationAbout(asCustomer, appointmentId, UPCOMING);
        assertNotNull(note, "the customer of a confirmed booking got no reminder");
        assertTrue(String.valueOf(note.get("body")).contains("reminder"),
            "unexpected reminder text: " + note.get("body"));
        assertEquals(Steps.countNotificationsAbout(asCustomer, appointmentId, UPCOMING), 1L,
            "running the reminder job twice reminded the customer twice");
    }

    @Test(description = "API-E2E-223 no reminder for a booking that is still pending")
    public void e2e223_noReminderWhilePending() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-223");
        int appointmentId = bookAppointmentFor(customer);

        LocalBackend.performNow(REMINDER, appointmentId);

        assertEquals(Steps.appointmentStatus(appointmentId), "pending",
            "the reminder job changed the status of a pending booking");
        assertEquals(Steps.countNotificationsAbout(
                LocalBackend.asCustomerOf(appointmentId), appointmentId, UPCOMING), 0L,
            "a pending booking's customer was reminded");
    }
}

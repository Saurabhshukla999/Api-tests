package com.nearz.api;

import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-217..219: what a booking tells the salon owner.
 *
 * Every booking event sends a push (Firebase) and a Slack message and writes
 * an in-app Notification row (backend app/models/appointment.rb, FcmApi). Push
 * and Slack are switched off locally (DISABLE_EXTERNAL_NOTIFICATIONS); the
 * in-app row is written regardless, and GET /users/{id}/notifications reads it
 * back for the logged-in user. So these tests prove the right message reaches
 * the right person, without anything leaving the machine.
 *
 * Titles and bodies are the backend's, from new_master_aws:
 *   new booking  -> salon owner  "NEARZ booking Approval"
 *   cancellation -> salon owner  "NEARZ Booking Cancellation"
 */
public class Block12NotificationTest extends BaseJourneyTest {

    static final String APPROVAL = "NEARZ booking Approval";
    static final String CANCELLATION = "NEARZ Booking Cancellation";

    @Test(description = "API-E2E-217 a new booking asks the salon owner to approve it")
    public void e2e217_newBookingNotifiesOwner() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-217");
        int appointmentId = bookAppointmentFor(customer);
        String startTime = Steps.readAppointment(appointmentId).getString("data.start_time");

        Map<String, Object> note = Steps.notificationAbout(Api.journey(), appointmentId, APPROVAL);
        assertNotNull(note, "the salon owner has no '" + APPROVAL + "' notification for appointment "
            + appointmentId);
        assertEquals(note.get("screen"), "appointment",
            "the notification opens screen " + note.get("screen") + ", not the appointment");
        assertEquals(note.get("read"), Boolean.FALSE, "a brand-new notification is already marked read");
        String body = String.valueOf(note.get("body"));
        assertTrue(body.startsWith("Please approve booking appointment request"),
            "unexpected approval text: " + body);
        assertTrue(body.contains(startTime),
            "the approval does not name the booked time " + startTime + ": " + body);
        assertEquals(Steps.countNotificationsAbout(Api.journey(), appointmentId, APPROVAL), 1L,
            "one booking produced more than one approval request");
    }

    @Test(description = "API-E2E-218 cancelling a booking tells the salon owner")
    public void e2e218_cancellationNotifiesOwner() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-218");
        int appointmentId = bookAppointmentFor(customer);
        assertNull(Steps.notificationAbout(Api.journey(), appointmentId, CANCELLATION),
            "a cancellation notice exists before anything was cancelled");

        Steps.setAppointmentStatus(appointmentId, "cancelled");

        assertEquals(Steps.appointmentStatus(appointmentId), "cancelled",
            "the appointment did not end up cancelled");
        Map<String, Object> note = Steps.notificationAbout(Api.journey(), appointmentId, CANCELLATION);
        assertNotNull(note, "the salon owner was not told that appointment "
            + appointmentId + " was cancelled");
        assertTrue(String.valueOf(note.get("body")).contains("is cancelled"),
            "unexpected cancellation text: " + note.get("body"));
    }

    @Test(description = "API-E2E-219 another salon's owner is not told about this salon's booking")
    public void e2e219_otherSalonOwnerNotNotified() {
        if (Env.OTHER_SALON.equals(SALON)) {
            throw new org.testng.SkipException("otherSalonId equals the journey salon; "
                + "run scripts/local-backend.sh seed for a second salon");
        }
        NewCustomer customer = arriveAsEnquiry("QA E2E-219");
        int appointmentId = bookAppointmentFor(customer);

        assertNotNull(Steps.notificationAbout(Api.journey(), appointmentId, APPROVAL),
            "the booking's own salon owner got no approval request, so this proves nothing");
        assertNull(Steps.notificationAbout(Api.other(), appointmentId, APPROVAL),
            "salon " + Env.OTHER_SALON + "'s owner received the approval request for an "
          + "appointment at salon " + SALON);
    }
}

package com.nearz.api;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * API-E2E-224..227 and S1, S2, D30: the customer app's side. LOCAL DOCKER ONLY.
 *
 * A customer logs in with an OTP sent by SMS, so the suite cannot log in as
 * one without sending real texts. Instead it mints the customer's token inside
 * the local web container (LocalBackend.asCustomerOf) and then uses only the
 * customer's own endpoints, as the app would:
 *   GET   /users/{id}/appointments       their bookings (answers for the token's user)
 *   GET   /users/{id}/metadata           unread_notification_count
 *   PATCH /notifications/{id}/mark_read  scoped to the token's user
 *
 * The known-defect cases record two login bypasses in new_master_aws
 * (AuthenticationController, D28 and D29) and a 500 (D30). They are local only,
 * by design: on production they would log in to real people's accounts.
 */
public class Block14CustomerAppTest extends BaseJourneyTest {

    @BeforeMethod(alwaysRun = true)
    public void localOnly() {
        LocalBackend.require();
    }

    private static List<Map<String, Object>> appointmentsOf(RequestSpecification customer) {
        return given().spec(customer)
                .when().get("/users/me/appointments")
                .then().statusCode(200)
                .extract().jsonPath().getList("data.appointments");
    }

    private static Map<String, Object> find(List<Map<String, Object>> appointments, int id) {
        for (Map<String, Object> a : appointments) {
            if (String.valueOf(a.get("id")).equals(String.valueOf(id))) {
                return a;
            }
        }
        return null;
    }

    private static int unreadCount(RequestSpecification who) {
        return given().spec(who)
                .when().get("/users/me/metadata")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.unread_notification_count");
    }

    @Test(description = "API-E2E-224 the customer sees the booking in their own appointment list")
    public void e2e224_customerSeesOwnBooking() {
        NewCustomer customer = arriveAsEnquiry("QA E2E-224");
        int appointmentId = bookAppointmentFor(customer);

        Map<String, Object> mine = find(appointmentsOf(LocalBackend.asCustomerOf(appointmentId)), appointmentId);
        assertNotNull(mine, "appointment " + appointmentId + " is missing from its customer's own list");
        assertEquals(String.valueOf(mine.get("date")), LocalDate.now().toString(), "wrong date shown");
        assertEquals(String.valueOf(mine.get("status")).toLowerCase(), "pending",
            "a booking awaiting approval should show as pending, shows " + mine.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> salon = (Map<String, Object>) mine.get("salon");
        assertNotNull(salon, "the booking does not say which salon it is at");
        assertEquals(String.valueOf(salon.get("id")), SALON, "the booking shows the wrong salon");
    }

    @Test(description = "API-E2E-225 a customer does not see another customer's booking")
    public void e2e225_customersAreIsolated() {
        int first = bookAppointmentFor(arriveAsEnquiry("QA E2E-225 a"));
        int second = bookAppointmentFor(arriveAsEnquiry("QA E2E-225 b"));

        List<Map<String, Object>> secondsList = appointmentsOf(LocalBackend.asCustomerOf(second));
        assertNotNull(find(secondsList, second), "customer b cannot see their own booking, so this proves nothing");
        assertEquals(find(secondsList, first), null,
            "customer b can see customer a's appointment " + first);
    }

    @Test(description = "API-E2E-226 marking a notification read lowers the unread count by one")
    public void e2e226_markReadLowersUnreadCount() {
        int appointmentId = bookAppointmentFor(arriveAsEnquiry("QA E2E-226"));
        Steps.setAppointmentStatus(appointmentId, "confirmed");   // the customer is told
        RequestSpecification customer = LocalBackend.asCustomerOf(appointmentId);
        Map<String, Object> note = Steps.notificationAbout(customer, appointmentId, "NEARZ Booking Confirmation");
        assertNotNull(note, "the customer got no confirmation notification to mark read");
        assertEquals(note.get("read"), Boolean.FALSE, "the confirmation was already read");
        int before = unreadCount(customer);

        given().spec(customer).pathParam("id", note.get("id"))
                .when().patch("/notifications/{id}/mark_read")
                .then().statusCode(200);

        assertEquals(unreadCount(customer), before - 1, "the unread count did not drop by one");
        assertEquals(Steps.notificationAbout(customer, appointmentId, "NEARZ Booking Confirmation").get("read"),
            Boolean.TRUE, "the notification still reads as unread");
    }

    @Test(description = "API-E2E-227 a customer cannot mark the salon owner's notification read")
    public void e2e227_cannotMarkSomeoneElsesNotification() {
        int appointmentId = bookAppointmentFor(arriveAsEnquiry("QA E2E-227"));
        Map<String, Object> ownersNote = Steps.notificationAbout(Api.journey(), appointmentId,
                Block12NotificationTest.APPROVAL);
        assertNotNull(ownersNote, "the owner has no approval notification to attack");

        Response attempt = given().spec(LocalBackend.asCustomerOf(appointmentId))
                .pathParam("id", ownersNote.get("id"))
                .when().patch("/notifications/{id}/mark_read");

        assertNotEquals(attempt.statusCode(), 200, "a customer marked the owner's notification read");
        assertEquals(Steps.notificationAbout(Api.journey(), appointmentId, Block12NotificationTest.APPROVAL)
                .get("read"), Boolean.FALSE, "the owner's notification was marked read by a customer");
    }

    // ---------------------------------------------------------------------
    // known defects, kept red on purpose (mvn test -Dgroups=known-defect)
    // ---------------------------------------------------------------------

    @Test(groups = "known-defect",
          description = "D30 marking someone else's notification read answers 404, not 500")
    public void d30_foreignMarkReadIsNotA500() {
        int appointmentId = bookAppointmentFor(arriveAsEnquiry("QA D30"));
        Object ownersNoteId = Steps.notificationAbout(Api.journey(), appointmentId,
                Block12NotificationTest.APPROVAL).get("id");
        int status = given().spec(LocalBackend.asCustomerOf(appointmentId))
                .pathParam("id", ownersNoteId)
                .when().patch("/notifications/{id}/mark_read").statusCode();
        assertEquals(status, 404, "NotificationsController#mark_read calls update on nil for a "
            + "notification that is not the caller's");
    }

    @Test(groups = "known-defect",
          description = "D28 SECURITY: POST /sign_in must not hand out a token for a mobile number alone")
    public void d28_signInNeedsAnOtp() {
        NewCustomer customer = arriveAsEnquiry("QA D28");   // a local, throwaway account
        Response response = given().spec(Api.anonymous())
                .body(Map.of("mobile", customer.phone()))
                .when().post("/sign_in");
        String token = response.jsonPath().getString("token");
        assertFalse(response.statusCode() == 200 && token != null && !token.isBlank(),
            "POST /sign_in returned a login token for " + customer.phone()
          + " with no OTP: anyone who knows a phone number can log in as its owner");
    }

    @Test(groups = "known-defect",
          description = "D29 SECURITY: POST /verify must not accept code 3227 because Origin mentions netlify")
    public void d29_netlifyOriginIsNotAnOtp() {
        NewCustomer customer = arriveAsEnquiry("QA D29");
        Response response = given().spec(Api.anonymous())
                .header("Origin", "https://attacker.netlify.app")
                .body(Map.of("mobile", customer.phone(), "code", "3227"))
                .when().post("/verify");
        assertTrue(response.statusCode() >= 400,
            "POST /verify logged " + customer.phone() + " in with the fixed code 3227 because the "
          + "client-chosen Origin header contains 'netlify' (answered " + response.statusCode() + ")");
    }
}

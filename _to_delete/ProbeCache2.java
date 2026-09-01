package com.nearz.api;

import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;

/**
 * TEMPORARY. Two questions:
 *   1. exactly when does an appointment-only write appear in the report?
 *   2. does an extra query param bust the cache? cache_key() MD5s the
 *      non-blank query params, so a unique one should force a fresh read.
 */
public class ProbeCache2 extends BaseJourneyTest {

    private Object appts(String cacheBuster) {
        var req = given().spec(Api.journey())
                .pathParam("salonId", SALON).queryParam("range", "today");
        if (cacheBuster != null) {
            req = req.queryParam("_qa", cacheBuster);
        }
        return req.when().get("/salons/{salonId}/reports/appointments/summary")
                .then().statusCode(200).extract()
                .jsonPath().get("data.kpis.total_appointments.value");
    }

    @Test
    public void whenDoesAnAppointmentAppear() throws Exception {
        System.out.println("\n===== appointment only, no bill =====");
        Object plainBefore  = appts(null);
        Object bustedBefore = appts(String.valueOf(System.nanoTime()));
        System.out.println("t=0   plain=" + plainBefore + "  cache-busted=" + bustedBefore);

        NewCustomer c = arriveAsEnquiry("QA PROBE C2");
        long t0 = System.currentTimeMillis();
        bookAppointmentFor(c);

        for (int i = 0; i < 8; i++) {
            long s = (System.currentTimeMillis() - t0) / 1000;
            System.out.println("t=" + s + "s  plain=" + appts(null)
                    + "  cache-busted=" + appts(String.valueOf(System.nanoTime())));
            Thread.sleep(10_000);
        }
        System.out.println("Expect +1 on both. If cache-busted moves at once and "
                + "plain lags, the extra param is a reliable way to read the truth.");
    }
}

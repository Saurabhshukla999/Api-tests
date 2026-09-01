package com.nearz.api;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;

import static org.hamcrest.Matchers.lessThan;

/**
 * The request specifications every test starts from.
 *
 * Nothing clever here on purpose: a spec is a base URL, a content type, and an
 * Authorization header. A test that wants to do something writes
 *
 *     given().spec(Api.journey()).body(...).post("/appointments")
 *
 * and everything about that call is visible on the page.
 */
public final class Api {

    private Api() { }

    /** Writes go here: salon 4550, the empty journey salon. */
    public static RequestSpecification journey() {
        return spec(Env.JOURNEY_TOKEN);
    }

    /** Reads against the real QA salon 4536. Never used to write. */
    public static RequestSpecification owner() {
        return spec(Env.OWNER_TOKEN);
    }

    /** A different tenant, for isolation checks. */
    public static RequestSpecification other() {
        return spec(Env.OTHER_TOKEN);
    }

    /** No Authorization header at all. */
    public static RequestSpecification anonymous() {
        return new RequestSpecBuilder()
                .setBaseUri(Env.BASE_URL)
                .setContentType(ContentType.JSON)
                .addFilter(new AllureRestAssured())
                .build();
    }

    private static RequestSpecification spec(String token) {
        return new RequestSpecBuilder()
                .setBaseUri(Env.BASE_URL)
                .setContentType(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + token)
                // Puts the full request and response of every call into the
                // Allure report, attached to the test that made it.
                .addFilter(new AllureRestAssured())
                // Print the request and response only when an assertion fails.
                // A green run stays quiet; a red one shows you exactly what was
                // sent and what came back, which is the only thing you want at
                // 9am when the nightly run went red.
                .log(LogDetail.URI)
                .build();
    }

    /**
     * What a healthy response looks like, reusable across tests.
     *
     * Status, content type and a response-time budget in one object, so a test
     * writes .then().spec(Api.ok()) instead of repeating three assertions.
     * SLA_MILLIS is deliberately generous - this is a smoke-level guard against
     * an endpoint falling off a cliff, not a performance test.
     */
    public static final long SLA_MILLIS = 5_000L;

    public static ResponseSpecification ok() {
        return healthy(200);
    }

    public static ResponseSpecification created() {
        return healthy(201);
    }

    private static ResponseSpecification healthy(int status) {
        return new ResponseSpecBuilder()
                .expectStatusCode(status)
                .expectContentType(ContentType.JSON)
                .expectResponseTime(lessThan(SLA_MILLIS))
                .build();
    }

    /**
     * Turn the API on before anything runs.
     *
     * The Nearz API answers 404s with HTTP 200 and a body of
     * {"error":"not_found"} (defect D1), so a test that only checks the status
     * code can pass against a missing record. Assertions in this suite look at
     * the body, not just the status.
     */
    public static void configure() {
        RestAssured.baseURI = Env.BASE_URL;
        // Leave URL encoding ON. Turning it off makes any query containing a
        // space - a customer name, for instance - fail with
        // "Illegal character in query at index ...".
    }
}

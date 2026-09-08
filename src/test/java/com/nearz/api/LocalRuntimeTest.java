package com.nearz.api;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.ResponseBuilder;
import org.testng.ITestNGListener;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.testng.Assert.assertTrue;

/** Opt-in toolchain check: no Env, credentials, sockets or Nearz API calls. */
public class LocalRuntimeTest {

    @Test(description = "Local JSON serialization, assertions and Allure adapters work")
    public void jsonAndReportingRuntimeWorks() {
        assertTrue(ServiceLoader.load(ITestNGListener.class).stream()
                .anyMatch(provider -> provider.type().getName()
                        .equals("io.qameta.allure.testng.AllureTestNg")),
                "Allure's TestNG listener must be discoverable at runtime");

        given().contentType("application/json")
                // Allure normally runs last; put it before the terminal fake response.
                .filter(new AllureRestAssured() {
                    @Override public int getOrder() { return 0; }
                })
                // Return the serialized request in memory; never continue to HTTP.
                .filter((request, response, context) -> new ResponseBuilder()
                        .setStatusCode(200).setStatusLine("HTTP/1.1 200 OK").setContentType("application/json")
                        .setBody((String) request.getBody()).build())
                .body(Map.of("name", "local-check", "amount", 1180))
                .when().post("http://localhost/unused")
                .then().statusCode(200)
                .body("name", equalTo("local-check"))
                .body("amount", equalTo(1180));
    }
}

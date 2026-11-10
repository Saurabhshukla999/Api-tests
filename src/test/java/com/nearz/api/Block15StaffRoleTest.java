package com.nearz.api;

import io.restassured.specification.RequestSpecification;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;

/**
 * API-E2E-228..230: what each staff role may open. LOCAL DOCKER ONLY.
 *
 * Expected access is new_master_aws Role::DEFAULT_MATRIX, which applies to a
 * salon that has never saved Settings > Roles & Permissions:
 *
 *   Manager       every module except settings
 *   Receptionist  dashboard, appointments, customers, billing
 *   Beautician    dashboard, appointments, customers
 *
 * Each role is hired through POST /salons/{id}/staff with that role as the
 * job title, which links a login account and grants the role
 * (StaffLoginAccess). The token is minted locally because staff log in by OTP.
 * Every probe is a GET, so a wrongly granted module is read, never changed.
 *
 * Settings and the staff list are refused to roles without settings or
 * employees access too (measured 10 Nov 2026), although no controller names
 * SettingsPolicy or EmployeesPolicy directly.
 */
public class Block15StaffRoleTest extends BaseJourneyTest {

    private RequestSpecification manager;
    private RequestSpecification receptionist;
    private RequestSpecification beautician;

    @BeforeClass(alwaysRun = true)
    public void hireOneOfEachRole() {
        LocalBackend.require();
        int saved = LocalBackend.savedRoleGrants(SALON);
        if (saved > 0) {
            throw new SkipException("salon " + SALON + " has " + saved + " saved Roles & Permissions "
                + "rows, so the default matrix these cases expect does not apply");
        }
        manager = LocalBackend.asStaff(hire("Manager"));
        receptionist = LocalBackend.asStaff(hire("Receptionist"));
        beautician = LocalBackend.asStaff(hire("Beautician"));
    }

    private int hire(String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "QA Role " + role + " " + System.nanoTime() % 100000);
        body.put("phone", Steps.newPhone());
        body.put("email", "qa.role." + System.nanoTime() + "@example.test");
        body.put("job_title", role);
        body.put("experience_years", 1);
        body.put("shift_start", "09:00");
        body.put("shift_end", "21:00");
        body.put("weekly_off", List.of());
        body.put("status", "active");
        return given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .body(body)
                .when().post("/salons/{salonId}/staff")
                .then().statusCode(201)
                .body("data.role", org.hamcrest.Matchers.equalTo(role))
                .extract().jsonPath().getInt("data.id");
    }

    /** One read-only request per module. */
    private int open(RequestSpecification who, String module) {
        String today = LocalDate.now().toString();
        var request = given().spec(who).pathParam("salonId", SALON);
        var unscoped = given().spec(who);   // billing finds the salon from the token
        switch (module) {
            case "dashboard":
                return request.queryParam("range", "today").get("/salons/{salonId}/dashboard/overview").statusCode();
            case "appointments":
                return request.queryParam("date", today).get("/salons/{salonId}/appointments").statusCode();
            case "billing":
                return unscoped.get("/api/v1/billing/services").statusCode();
            case "reports":
                return request.queryParam("range", "today").queryParam("_qa", System.nanoTime())
                        .get("/salons/{salonId}/reports/sales/summary").statusCode();
            case "attendance":
                return request.queryParam("date", today).get("/salons/{salonId}/attendance/daily").statusCode();
            case "settings":
                return request.get("/salons/{salonId}/settings").statusCode();
            case "employees":
                return request.get("/salons/{salonId}/staff").statusCode();
            default:
                throw new IllegalArgumentException(module);
        }
    }

    private void expect(RequestSpecification who, String role, String module, boolean allowed) {
        int status = open(who, module);
        if (allowed) {
            assertEquals(status, 200, role + " should open " + module + " but got " + status);
        } else {
            assertEquals(status, 403, role + " should be refused " + module + " but got " + status);
        }
    }

    @Test(description = "API-E2E-228 a Manager opens every module except settings")
    public void e2e228_manager() {
        for (String module : List.of("dashboard", "appointments", "billing", "reports", "attendance", "employees")) {
            expect(manager, "Manager", module, true);
        }
        expect(manager, "Manager", "settings", false);
    }

    @Test(description = "API-E2E-229 a Receptionist opens the front desk, not reports or attendance")
    public void e2e229_receptionist() {
        expect(receptionist, "Receptionist", "dashboard", true);
        expect(receptionist, "Receptionist", "appointments", true);
        expect(receptionist, "Receptionist", "billing", true);
        expect(receptionist, "Receptionist", "reports", false);
        expect(receptionist, "Receptionist", "attendance", false);
        expect(receptionist, "Receptionist", "employees", false);
        expect(receptionist, "Receptionist", "settings", false);
    }

    @Test(description = "API-E2E-230 a Beautician sees the calendar, not billing, reports or attendance")
    public void e2e230_beautician() {
        expect(beautician, "Beautician", "dashboard", true);
        expect(beautician, "Beautician", "appointments", true);
        expect(beautician, "Beautician", "billing", false);
        expect(beautician, "Beautician", "reports", false);
        expect(beautician, "Beautician", "attendance", false);
        expect(beautician, "Beautician", "employees", false);
        expect(beautician, "Beautician", "settings", false);
    }
}

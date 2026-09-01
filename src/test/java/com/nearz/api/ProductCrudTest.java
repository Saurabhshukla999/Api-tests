package com.nearz.api;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.testng.Assert.assertEquals;

/**
 * Plain CRUD on a real resource - the textbook REST Assured shape, applied to
 * the Nearz product catalogue.
 *
 * This is the file to read first if you have seen REST Assured tutorials:
 * create, read, update, delete, one @Test each, Hamcrest matchers inline on
 * .then(). The journey tests in Block1/2/3 do something more, but they are
 * built out of exactly these calls.
 *
 * Two differences from a tutorial worth knowing:
 *
 *   - a tutorial asserts response.getBody().asString().contains("foo"), which
 *     passes if "foo" appears ANYWHERE in the body, including inside an error
 *     message. Here every check names a JSON path.
 *   - a tutorial usually runs against jsonplaceholder, where POST does not
 *     really create anything and DELETE does not really delete. This runs
 *     against the real API, so each test cleans up what it made.
 */
public class ProductCrudTest {

    private static final String SALON = Env.JOURNEY_SALON;
    private static final String COLLECTION = "/salons/{salonId}/products";
    private static final String ITEM = "/salons/{salonId}/products/{id}";

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        Api.configure();
    }

    // =======================================================================
    // CREATE
    // =======================================================================
    @Test(description = "POST a product and get it back with the values we sent")
    public void createProduct() {
        String name = "CRUD " + System.currentTimeMillis();

        int id = given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .body(newProduct(name, 50, 90))
                .when()
                .post(COLLECTION)
                .then()
                .spec(Api.created())              // 201 + JSON + inside the SLA
                .body("data.id", notNullValue())
                .body("data.name", equalTo(name))
                .body("data.unit", equalTo("pcs"))
                .body("data.status", equalTo("active"))
                // The API generates the SKU; we only check it did.
                .body("data.sku", notNullValue())
                .extract().jsonPath().getInt("data.id");

        deleteQuietly(id);
    }

    @Test(description = "POST with an invalid unit is refused, and the error names the legal set")
    public void createProductWithBadUnitIsRefused() {
        given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .body(Map.ofEntries(
                        Map.entry("name", "CRUD bad unit"),
                        Map.entry("brand", "QA Brand"),
                        Map.entry("category", "Haircare"),
                        Map.entry("unit", "litres"),        // not a legal unit
                        Map.entry("cost_price", 50),
                        Map.entry("selling_price", 90),
                        Map.entry("opening_stock", 10),
                        Map.entry("reorder_level", 2)))
                .when()
                .post(COLLECTION)
                .then()
                .statusCode(422)
                .body("error", equalTo("unit must be one of: ml, g, pcs, box"));
    }

    // =======================================================================
    // READ
    // =======================================================================
    @Test(description = "GET the product list and find the one we just created")
    public void readProduct() {
        String name = "CRUD read " + System.currentTimeMillis();
        int id = create(name, 50, 90);

        given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .queryParam("per_page", 200)
                .when()
                .get(COLLECTION)
                .then()
                .spec(Api.ok())
                .body("data.meta.total", greaterThan(0))
                // The list lives under data.items here. Note that
                // /api/v1/billing/products returns the same records under
                // data.products - two endpoints, two names, same thing. Worth
                // knowing before you spend twenty minutes on a null.
                .body("data.items.find { it.id == " + id + " }.name", equalTo(name))
                // Prices come back as STRINGS ("90.0"), not numbers. Money is
                // never compared as a String anywhere it matters - see
                // Money.java - but this is the raw shape.
                .body("data.items.find { it.id == " + id + " }.selling_price",
                      equalTo("90.0"));

        deleteQuietly(id);
    }

    // =======================================================================
    // UPDATE
    // =======================================================================
    @Test(description = "PUT a new name and price, and see both change")
    public void updateProduct() {
        int id = create("CRUD update " + System.currentTimeMillis(), 50, 90);
        String newName = "CRUD updated " + System.currentTimeMillis();

        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .body(Map.of("name", newName, "selling_price", 120))
                .when()
                .put(ITEM)
                .then()
                .spec(Api.ok())
                .body("data.id", equalTo(id))
                .body("data.name", equalTo(newName))
                .body("data.selling_price", equalTo("120.0"))
                // The fields we did NOT send must be left alone. A PUT that
                // silently blanks unmentioned fields is a classic data-loss bug.
                .body("data.cost_price", equalTo("50.0"))
                .body("data.unit", equalTo("pcs"));

        deleteQuietly(id);
    }

    // =======================================================================
    // DELETE
    // =======================================================================
    /**
     * DEFECT D15 (found 28 Aug 2026): DELETE removes the product but answers 500.
     *
     * The record really is gone - a second DELETE returns 404 "product not
     * found" and the product no longer appears in the list - but the first call
     * comes back HTTP 500 with an empty body. So the salon owner clicks Delete,
     * sees an error, and the product vanishes anyway.
     *
     * Products#destroy calls Product#remove!, which returns the SYMBOL :deleted
     * or :deactivated. The controller then does json_response(deleted: true),
     * and something in that path blows up after the delete has already
     * committed. A backend engineer needs to confirm the exact cause.
     *
     * This test asserts the CORRECT behaviour and therefore fails today, which
     * is what a defect tracker should do. It sits in the "known-defect" group,
     * excluded from the default run in testng.xml, so the nightly suite stays
     * green while the bug stays visible:
     *
     *     mvn test -Dgroups=known-defect
     */
    @Test(groups = "known-defect",
          description = "DELETE should confirm the deletion rather than answer 500")
    public void deleteProductAnswersSuccess() {
        int id = create("CRUD delete " + System.currentTimeMillis(), 50, 90);

        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when()
                .delete(ITEM)
                .then()
                .statusCode(200);
    }

    @Test(description = "DELETE really removes the product, whatever it answers")
    public void deleteProductRemovesIt() {
        int id = create("CRUD delete " + System.currentTimeMillis(), 50, 90);

        // Deliberately not asserting the status here - see D15 above. What this
        // test cares about is whether the product is gone, and it is.
        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when().delete(ITEM);

        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when()
                .delete(ITEM)
                .then()
                .statusCode(404)
                .body("error", equalTo("product not found"));
    }

    @Test(description = "DELETE on an id that never existed is a 404, not a 500")
    public void deleteMissingProductIs404() {
        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", 999_999)
                .when()
                .delete(ITEM)
                .then()
                .statusCode(404);
    }

    // =======================================================================
    // helpers
    // =======================================================================
    private static Map<String, Object> newProduct(String name, int cost, int price) {
        return Map.ofEntries(
                Map.entry("name", name),
                Map.entry("brand", "QA Brand"),
                Map.entry("category", "Haircare"),
                // The API names the legal set if you guess wrong:
                // "unit must be one of: ml, g, pcs, box"
                Map.entry("unit", "pcs"),
                Map.entry("cost_price", cost),
                Map.entry("selling_price", price),
                Map.entry("opening_stock", 10),
                Map.entry("reorder_level", 2));
    }

    private int create(String name, int cost, int price) {
        int id = given().spec(Api.journey())
                .pathParam("salonId", SALON)
                .body(newProduct(name, cost, price))
                .when().post(COLLECTION)
                .then().spec(Api.created())
                .extract().jsonPath().getInt("data.id");
        assertEquals(id > 0, true, "the product was created without an id");
        return id;
    }

    /** Clean up after ourselves. The status is ignored on purpose - see D15. */
    private void deleteQuietly(int id) {
        given().spec(Api.journey())
                .pathParam("salonId", SALON).pathParam("id", id)
                .when().delete(ITEM);
    }
}

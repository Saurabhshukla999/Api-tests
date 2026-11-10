package com.nearz.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.specification.RequestSpecification;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;

/**
 * The response of every GET the backend documents, checked against the
 * backend's own OpenAPI files.
 *
 * The specs are backend/swagger/v1/*.json (new_master_aws, the source of truth;
 * -DswaggerDir to point elsewhere). For each documented GET whose only path
 * parameter is the salon, the test fills required query parameters from the
 * spec itself (example, default, first enum value, or today for a date) and
 * validates the live response with Atlassian's OpenAPI validator. Requests are
 * not validated, only responses: the contract under test is what the API sends
 * back.
 *
 * Read-only (GET only), so it may also run against production, where a failure
 * means the deployed build has drifted from the repository's spec. Not in
 * testng.xml: run it with
 *
 *     mvn test -DconfigFile=config.local.properties -DsuiteXml=testng-contract.xml
 */
public class ContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, OpenApiValidationFilter> FILTERS = new HashMap<>();

    static Path swaggerDir() {
        return Path.of(System.getProperty("swaggerDir", "../backend/swagger/v1"));
    }

    @DataProvider(name = "documentedGets")
    public Iterator<Object[]> documentedGets() throws Exception {
        Path dir = swaggerDir();
        if (!Files.isDirectory(dir)) {
            throw new SkipException("no OpenAPI files at " + dir.toAbsolutePath() + " (-DswaggerDir)");
        }
        List<Object[]> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode paths = JSON.readTree(file.toFile()).path("paths");
                for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
                    String path = it.next();
                    JsonNode get = paths.path(path).path("get");
                    if (!get.isMissingNode()) {
                        cases.add(new Object[] {file.getFileName().toString(), path});
                    }
                }
            }
        }
        return cases.iterator();
    }

    private static synchronized OpenApiValidationFilter filterFor(String specFile) {
        return FILTERS.computeIfAbsent(specFile, name -> {
            File file = swaggerDir().resolve(name).toFile();
            OpenApiInteractionValidator validator = OpenApiInteractionValidator
                    .createForSpecificationUrl(file.toURI().toString())
                    .withBasePathOverride("/")
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build();
            return new OpenApiValidationFilter(validator);
        });
    }

    @Test(dataProvider = "documentedGets",
          description = "CONTRACT every documented GET answers in the documented shape")
    public void responseMatchesSpec(String specFile, String path) throws Exception {
        JsonNode operation = JSON.readTree(swaggerDir().resolve(specFile).toFile())
                .path("paths").path(path).path("get");
        List<JsonNode> parameters = new ArrayList<>();
        operation.path("parameters").forEach(parameters::add);
        JSON.readTree(swaggerDir().resolve(specFile).toFile())
                .path("paths").path(path).path("parameters").forEach(parameters::add);

        String concrete = path;
        RequestSpecification request = given().spec(Api.journey())
                .filter(filterFor(specFile));
        for (JsonNode p : parameters) {
            JsonNode param = resolve(specFile, p);
            String name = param.path("name").asText();
            String in = param.path("in").asText();
            if ("path".equals(in)) {
                if (!(name.equals("salon_id") || (name.equals("id") && path.startsWith("/salons/{id}")))) {
                    throw new SkipException("needs a " + name + " the contract check does not create");
                }
                concrete = concrete.replace("{" + name + "}", Env.JOURNEY_SALON);
            } else if ("query".equals(in) && param.path("required").asBoolean(false)) {
                String value = sampleValue(name, param.path("schema"), param.path("example"));
                if (value == null) {
                    throw new SkipException("cannot fill required query parameter " + name);
                }
                request = request.queryParam(name, value);
            }
        }
        if (concrete.contains("{")) {
            throw new SkipException("unfilled path parameter in " + concrete);
        }
        // A failed validation throws OpenApiValidationException with every
        // mismatch listed: that message is the finding.
        request.when().get(concrete);
    }

    /** Follow a $ref to components/parameters within the same file. */
    private static JsonNode resolve(String specFile, JsonNode node) throws Exception {
        if (!node.has("$ref")) {
            return node;
        }
        String ref = node.get("$ref").asText();            // #/components/parameters/Range
        JsonNode spec = JSON.readTree(swaggerDir().resolve(specFile).toFile());
        JsonNode target = spec;
        for (String part : ref.replaceFirst("^#/", "").split("/")) {
            target = target.path(part);
        }
        return target;
    }

    private static String sampleValue(String name, JsonNode schema, JsonNode example) {
        if (!example.isMissingNode() && !example.isNull()) {
            return example.asText();
        }
        if (schema.has("example")) {
            return schema.get("example").asText();
        }
        if (schema.has("default")) {
            return schema.get("default").asText();
        }
        if (schema.has("enum") && schema.get("enum").size() > 0) {
            return schema.get("enum").get(0).asText();
        }
        String format = schema.path("format").asText();
        if ("date".equals(format) || name.endsWith("date") || name.equals("from") || name.equals("to")) {
            return LocalDate.now().toString();
        }
        if ("integer".equals(schema.path("type").asText())) {
            return "1";
        }
        return null;
    }
}

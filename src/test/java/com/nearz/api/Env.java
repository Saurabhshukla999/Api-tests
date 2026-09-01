package com.nearz.api;

import java.io.InputStream;
import java.util.Properties;

/**
 * Where the tests point and who they log in as.
 *
 * Values come from src/test/resources/config.properties, and any of them can be
 * overridden on the command line without editing the file:
 *
 *     mvn test -DbaseUrl=https://staging.example.com
 *
 * Three salons, each with one job. This separation is not decoration - putting
 * journeys on the live QA salon is how weekday 9 ended up permanently on salon
 * 4536, and swapping the isolation salon for an empty one silently blinded the
 * tenant-leak check.
 */
public final class Env {

    private static final Properties P = load();

    /** The API under test. */
    public static final String BASE_URL = get("baseUrl");

    /** Salon 4536 - has real QA data. Journeys only READ here. */
    public static final String OWNER_SALON = get("ownerSalonId");
    public static final String OWNER_TOKEN = get("ownerToken");

    /** Salon 4550 - empty. Every journey that WRITES writes here. */
    public static final String JOURNEY_SALON = get("journeySalonId");
    public static final String JOURNEY_TOKEN = get("journeyToken");

    /** Salon 1725 - the other tenant, for isolation checks. Must keep its data. */
    public static final String OTHER_SALON = get("otherSalonId");
    public static final String OTHER_TOKEN = get("otherToken");

    private Env() { }

    private static String get(String key) {
        String override = System.getProperty(key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String value = P.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "config.properties is missing '" + key + "'. Copy "
              + "config.properties.example, fill it in, and try again.");
        }
        return value.trim();
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = Env.class.getClassLoader()
                                       .getResourceAsStream("config.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                    "src/test/resources/config.properties not found.");
            }
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not read config.properties", e);
        }
        return props;
    }
}

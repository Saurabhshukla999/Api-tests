package com.nearz.api;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import java.util.Set;

/**
 * Where the tests point and who they log in as.
 *
 * Values come from src/test/resources/config.properties, or from the file
 * named by -DconfigFile (for the local Docker backend:
 * -DconfigFile=config.local.properties). Any single value can also be overridden
 * on the command line without editing the file:
 *
 *     mvn test -DbaseUrl=http://localhost:3000
 *
 * testnearz.co.in is PRODUCTION: the live app and the B2B website use it.
 * Writes there are refused unless -DallowProductionWrites=true (see
 * ProductionGuard). Write-heavy runs belong on the local Docker backend.
 *
 * Three salons, each with one job. This separation is not decoration - putting
 * journeys on the real salon 4536 is how weekday 9 ended up permanently on it,
 * and swapping the isolation salon for an empty one silently blinded the
 * tenant-leak check.
 */
public final class Env {

    /** Hosts that serve real customers. Never a target for writes by default. */
    static final Set<String> PRODUCTION_HOSTS = Set.of("testnearz.co.in", "www.testnearz.co.in");

    private static final String CONFIG_FILE = configFile();

    private static final Properties P = load();

    /** The API under test. */
    public static final String BASE_URL = get("baseUrl");

    /** On production: salon 4536, a real salon with real data. Only READ here. */
    public static final String OWNER_SALON = get("ownerSalonId");
    public static final String OWNER_TOKEN = get("ownerToken");

    /** On production: salon 4550, the test salon. Every journey that WRITES writes here. */
    public static final String JOURNEY_SALON = get("journeySalonId");
    public static final String JOURNEY_TOKEN = get("journeyToken");

    /** On production: salon 1725, the other tenant, for isolation checks. Must keep its data. */
    public static final String OTHER_SALON = get("otherSalonId");
    public static final String OTHER_TOKEN = get("otherToken");

    /**
     * Leading digits of every phone the suite invents (customers, staff).
     * A block agreed with the backend team, so test users can be found with
     * {@code mobile LIKE '<prefix>%'} and never land on a real person's
     * account. Optional, so an older config.properties keeps working.
     */
    public static final String TEST_PHONE_PREFIX = optional("testPhonePrefix", "600");

    /**
     * Command line only, never read from a file: writing to production has to
     * be a deliberate choice on every run.
     */
    public static final boolean ALLOW_PRODUCTION_WRITES =
            Boolean.parseBoolean(System.getProperty("allowProductionWrites", "false"));

    private Env() { }

    /** True when the base URL is a production host. */
    public static boolean targetsProduction(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null && PRODUCTION_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String configFile() {
        String name = System.getProperty("configFile");
        return name == null || name.isBlank() ? "config.properties" : name.trim();
    }

    private static String optional(String key, String fallback) {
        String override = System.getProperty(key);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String value = P.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String get(String key) {
        String override = System.getProperty(key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String value = P.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                CONFIG_FILE + " is missing '" + key + "'. Copy "
              + "config.properties.example, fill it in, and try again.");
        }
        return value.trim();
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = Env.class.getClassLoader()
                                       .getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "src/test/resources/" + CONFIG_FILE + " not found.");
            }
            props.load(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + CONFIG_FILE, e);
        }
        return props;
    }
}

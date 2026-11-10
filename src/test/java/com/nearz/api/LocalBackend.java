package com.nearz.api;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.testng.SkipException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Reaches inside the LOCAL Docker backend for what the API cannot do: run a
 * background job now instead of in 15 minutes, or mint a token for a customer
 * whose login needs an OTP.
 *
 * Only ever used when the base URL is localhost. Everything goes through
 * `docker exec` on the web container (localWebContainer in the config file,
 * default nearz-tests-web-1) and `rails runner`, which refuses to run outside the
 * development environment here because the script checks Rails.env first.
 */
final class LocalBackend {

    private static final String CONTAINER = Env.LOCAL_WEB_CONTAINER;

    private LocalBackend() { }

    /** Skip the calling test unless the suite targets the local Docker backend. */
    static void require() {
        String host;
        try {
            host = URI.create(Env.BASE_URL.trim()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new SkipException("needs the local Docker backend (base URL is "
                + Env.BASE_URL + "); run with -DconfigFile=config.local.properties");
        }
        if (Env.targetsProduction(Env.BASE_URL)) {
            throw new SkipException("never run against production");
        }
    }

    /**
     * Run Ruby in the web container with rails runner and return the value of
     * the last line printed as RESULT=...
     */
    static String rails(String ruby) {
        require();
        try {
            Path script = Files.createTempFile("nearz-runner-", ".rb");
            Files.writeString(script, "abort 'not development' unless Rails.env.development?\n" + ruby);
            exec(120, "docker", "cp", script.toString(), CONTAINER + ":/tmp/nearz-runner.rb");
            Files.deleteIfExists(script);
            String out = exec(180, "docker", "exec", CONTAINER, "bash", "-lc",
                    "cd /nearz && bundle exec rails runner /tmp/nearz-runner.rb");
            String result = null;
            for (String line : out.split("\\R")) {
                if (line.startsWith("RESULT=")) {
                    result = line.substring("RESULT=".length()).trim();
                }
            }
            if (result == null) {
                throw new AssertionError("rails runner printed no RESULT line:\n"
                    + Steps.redacted(out.replaceAll("eyJ[A-Za-z0-9._-]+", "<token>")));
            }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not reach the local web container "
                + CONTAINER + " (is Docker running? scripts/local-backend.sh status)", e);
        }
    }

    /** Run an ActiveJob now, as Sidekiq or the async adapter would later. */
    static void performNow(String jobClass, int appointmentId) {
        if (!jobClass.matches("[A-Z][A-Za-z0-9:]*Job")) {
            throw new IllegalArgumentException("not a job class: " + jobClass);
        }
        rails(jobClass + ".perform_now(" + appointmentId + ")\nputs 'RESULT=ok'\n");
    }

    /** A request spec logged in as the customer an appointment belongs to. */
    static RequestSpecification asCustomerOf(int appointmentId) {
        return specFor(rails("a = Appointment.find(" + appointmentId + ")\n"
            + "puts \"RESULT=#{JsonWebToken.encode(user_id: a.user_id)}\"\n"));
    }

    /**
     * A request spec logged in as a rostered staff member. Creating the staff
     * through the API links a login account (StaffLoginAccess); an empty result
     * means that link was never made.
     */
    static RequestSpecification asStaff(int staffId) {
        String token = rails("s = Staff.find(" + staffId + ")\n"
            + "puts \"RESULT=#{s.user_id ? JsonWebToken.encode(user_id: s.user_id) : 'none'}\"\n");
        if ("none".equals(token)) {
            throw new AssertionError("staff " + staffId + " has no login account (staffs.user_id is "
                + "empty), so StaffLoginAccess never ran for them");
        }
        return specFor(token);
    }

    /** Number of saved Roles & Permissions rows for a salon; 0 means the default matrix applies. */
    static int savedRoleGrants(String salonId) {
        return Integer.parseInt(rails("puts \"RESULT=#{RolePermission.for_salon("
            + Integer.parseInt(salonId) + ").count}\"\n"));
    }

    private static RequestSpecification specFor(String token) {
        return new RequestSpecBuilder()
                .setBaseUri(Env.BASE_URL)
                .setContentType(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + token)
                .addFilter(new ProductionGuard())
                .build();
    }

    private static String exec(int timeoutSeconds, String... command) throws java.io.IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            Thread reader = new Thread(() -> {
                try {
                    in.transferTo(buffer);
                } catch (java.io.IOException ignored) {
                    // the process ended; whatever was read is kept
                }
            });
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command[0], command[1])
                    + " did not finish in " + timeoutSeconds + "s");
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(command[0] + " " + command[1] + " exited "
                + process.exitValue() + ":\n" + out.replaceAll("eyJ[A-Za-z0-9._-]+", "<token>"));
        }
        return out;
    }
}

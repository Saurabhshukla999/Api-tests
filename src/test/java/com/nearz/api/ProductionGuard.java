package com.nearz.api;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.Set;

/**
 * Refuses every write to production before it is sent.
 *
 * testnearz.co.in serves the live app and the B2B website. A journey there
 * leaves customers, bills and global users behind that analytics then counts,
 * and completing an appointment pages Slack. GET, HEAD and OPTIONS pass; any
 * other method fails the test with this message unless the run was started
 * with -DallowProductionWrites=true.
 *
 * Installed first on every spec in {@link Api}, so nothing is sent, logged or
 * attached when it trips.
 */
final class ProductionGuard implements Filter {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response,
                           FilterContext context) {
        String method = String.valueOf(request.getMethod()).toUpperCase();
        if (!READS.contains(method)
                && Env.targetsProduction(request.getURI())
                && !Env.ALLOW_PRODUCTION_WRITES) {
            throw new IllegalStateException(
                "Refused " + method + " " + request.getURI() + ": that is PRODUCTION. "
              + "Run write tests against the local Docker backend "
              + "(-DconfigFile=config.local.properties, see SETUP.md), or pass "
              + "-DallowProductionWrites=true if writing to production is intended.");
        }
        return context.next(request, response);
    }
}

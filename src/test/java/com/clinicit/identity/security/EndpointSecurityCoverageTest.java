package com.clinicit.identity.security;

import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Guards against a future endpoint being added without protection. Walks every
 * mapped API endpoint, so new controllers are covered automatically.
 */
class EndpointSecurityCoverageTest extends PostgresIntegrationTest {

    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/login", "GET /api/v1/health", "GET /api/v1/public/queue-status/{code}");

    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;

    private List<Endpoint> apiEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(method.name(), pattern, entry.getValue()));
                }
            }
        }
        assertThat(endpoints).hasSizeGreaterThan(25);
        return endpoints;
    }

    record Endpoint(String method, String pattern, HandlerMethod handler) {
        String key() { return method + " " + pattern; }
        String samplePath() { return pattern.replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000000"); }
    }

    @Test
    void everyNonPublicEndpointRejectsAnonymousRequestsWith401() throws Exception {
        List<String> open = new ArrayList<>();
        for (Endpoint endpoint : apiEndpoints()) {
            if (PUBLIC.contains(endpoint.key())) {
                continue;
            }
            int status = mvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.samplePath()))
                    .andReturn().getResponse().getStatus();
            if (status != 401) {
                open.add(endpoint.key() + " -> " + status);
            }
        }
        assertThat(open).as("endpoints reachable without authentication").isEmpty();
    }

    @Test
    void everyNonPublicEndpointDeclaresItsRoles() {
        List<String> undeclared = apiEndpoints().stream()
                .filter(endpoint -> !PUBLIC.contains(endpoint.key()))
                .filter(endpoint -> !AnnotatedElementUtils.hasAnnotation(endpoint.handler().getMethod(), PreAuthorize.class)
                        && !AnnotatedElementUtils.hasAnnotation(endpoint.handler().getBeanType(), PreAuthorize.class))
                .map(Endpoint::key)
                .toList();

        assertThat(undeclared).as("endpoints without @AdminOnly / @FrontDesk / @AnyStaff").isEmpty();
    }
}

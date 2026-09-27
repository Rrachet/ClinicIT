package com.clinicit.observability;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Probes, Prometheus metrics, request ids: the production observability baseline. */
class ObservabilityIntegrationTest extends PostgresIntegrationTest {

    @LocalManagementPort int managementPort;
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meters;
    @Autowired QueueService queue;
    @Autowired TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();
    Clinic clinic;
    DoctorProfile doctor;
    Actor desk;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
    }

    private HttpResponse<String> fetch(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String name) {
        var timer = meters.find(name).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void livenessAndReadinessAreUpOnTheInternalPortWithoutAToken() throws Exception {
        HttpResponse<String> liveness = fetch(managementPort, "/actuator/health/liveness");
        HttpResponse<String> readiness = fetch(managementPort, "/actuator/health/readiness");

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"").contains("\"db\"");
    }

    @Test
    void metricsAreOnTheInternalPortOnlyAndNothingElseIsExposed() throws Exception {
        HttpResponse<String> prometheus = fetch(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("http_server_requests_seconds", "hikaricp_connections_active",
                "clinicit_websocket_connections", "clinicit_notifications_pending");

        // Not exposed, and not open either: the security filter covers the management port too.
        for (String hidden : java.util.List.of("/actuator/env", "/actuator/beans", "/actuator/configprops")) {
            HttpResponse<String> response = fetch(managementPort, hidden);
            assertThat(response.statusCode()).as(hidden).isIn(401, 404);
            assertThat(response.body()).as(hidden).doesNotContain("spring.datasource", "propertySources");
        }
        // The public port does not serve actuator endpoints at all.
        HttpResponse<String> publicPort = fetch(port, "/actuator/prometheus");
        assertThat(publicPort.statusCode()).isIn(401, 404);
        assertThat(publicPort.body()).doesNotContain("hikaricp");
    }

    @Test
    void queueMetricsCountCommittedOperationsOnly() {
        double joins = count("clinicit.queue.joins");
        double calls = count("clinicit.queue.calls");
        long waits = timerCount("clinicit.queue.wait");
        long consultations = timerCount("clinicit.consultation.duration");

        QueueEntryResponse entry = queue.join(desk, arrivedAppointment(doctor, patient(clinic, "A")).getId());
        tx.executeWithoutResult(status -> {
            queue.join(desk, arrivedAppointment(doctor, patient(clinic, "B")).getId());
            status.setRollbackOnly();
        });
        clock.set(clock.instant().plus(Duration.ofMinutes(12)));
        queue.callNext(desk, doctor.getId());
        queue.startConsultation(desk, entry.id());
        clock.set(clock.instant().plus(Duration.ofMinutes(9)));
        queue.complete(desk, entry.id());

        assertThat(count("clinicit.queue.joins")).isEqualTo(joins + 1); // not the rolled-back join
        assertThat(count("clinicit.queue.calls")).isEqualTo(calls + 1);
        assertThat(timerCount("clinicit.queue.wait")).isEqualTo(waits + 1);
        assertThat(timerCount("clinicit.consultation.duration")).isEqualTo(consultations + 1);
        assertThat(meters.find("clinicit.consultation.duration").timer().max(java.util.concurrent.TimeUnit.MINUTES))
                .isGreaterThanOrEqualTo(9.0);
        assertThat(count("clinicit.queue.transitions", "to", "COMPLETED")).isPositive();
    }

    @Test
    void notificationDeliveriesAreCounted() {
        double sent = count("clinicit.notifications.deliveries", "outcome", "sent", "channel", "SMS");

        queue.join(desk, arrivedAppointment(doctor, patient(clinic, "A")).getId()); // "you're checked in"

        assertThat(count("clinicit.notifications.deliveries", "outcome", "sent", "channel", "SMS")).isEqualTo(sent + 1);
    }

    @Test
    void everyResponseCarriesARequestIdAndOnlySafeIncomingIdsAreKept() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/api/v1/health").header("X-Request-Id", "lb-7f3a9c21"))
                .andExpect(header().string("X-Request-Id", "lb-7f3a9c21"));
        mvc.perform(get("/api/v1/health").header("X-Request-Id", "x\nFAKE LOG LINE"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }
}

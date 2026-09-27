package com.clinicit.prediction;

import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.prediction.api.WaitEstimatesResponse;
import com.clinicit.prediction.domain.WaitTimeEstimate.Source;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.support.FakeModelServer;
import com.clinicit.support.FakeModelServer.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static com.clinicit.support.FakeModelServer.instanceCount;
import static com.clinicit.support.FakeModelServer.modelAnswer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Boot → ML service integration against a scripted fake service: the model's answer
 * when it is good, and the baseline in every other case, without ever affecting the queue.
 */
class WaitTimePredictionIntegrationTest extends PredictionScenario {

    @Autowired MockMvc mvc;

    final FakeModelServer ml = FakeModelServer.get();
    QueueEntryResponse first;
    QueueEntryResponse second;
    QueueEntryResponse third;

    @BeforeEach
    void setUp() {
        setUpClinic();
        at(10, 0);
        QueueEntryResponse inRoom = checkIn(drA);
        first = checkIn(drA);
        second = checkIn(drA);
        third = checkIn(drA);
        callAndStart(drA);
        assertThat(inRoom.tokenNumber()).isLessThan(first.tokenNumber());
        at(10, 5);
    }

    private WaitEstimatesResponse estimates() {
        return waitTimePredictions.forDoctorToday(desk, drA.getId());
    }

    private void modelAnswers(double estimate, double lower, double upper, String version) {
        ml.respond(body -> Reply.json(modelAnswer(instanceCount(body), estimate, lower, upper, version)));
    }

    @Test
    void theModelsRangeIsUsedAndLabelledWithItsVersion() throws Exception {
        modelAnswers(23.4, 17.2, 31.9, "wait-hgb-test-1");
        String admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));

        mvc.perform(get("/api/v1/queues/today/wait-estimates").param("doctorId", drA.getId().toString()).with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].tokenNumber").value(first.tokenNumber()))
                .andExpect(jsonPath("$.entries[0].estimatedWaitMinutes").value(23))
                .andExpect(jsonPath("$.entries[0].lowerBoundMinutes").value(17))
                .andExpect(jsonPath("$.entries[0].upperBoundMinutes").value(32))
                .andExpect(jsonPath("$.entries[0].source").value("MODEL"))
                .andExpect(jsonPath("$.entries[0].modelVersion").value("wait-hgb-test-1"))
                .andExpect(jsonPath("$.entries[0].fallbackReason").doesNotExist());

        // One batched call for the doctor's whole queue, carrying features only.
        assertThat(ml.requests()).hasSize(1);
        String request = ml.requests().get(0);
        assertThat(request).contains("\"schemaVersion\":\"1\"").contains("\"patientsAhead\":0").contains("\"patientsAhead\":2");
        assertThat(instanceCount(request)).isEqualTo(3);
        assertThat(request).doesNotContain(clinic.getId().toString(), drA.getId().toString(),
                first.id().toString(), "Asha", "Dr. A");
    }

    @Test
    void whenTheServiceIsDownTheBaselineIsUsedAndTheServiceIsLeftAloneForAWhile() {
        WaitEstimatesResponse response = estimates();

        // No consultations recorded yet, so the baseline uses the default 10 minutes per patient ahead.
        assertThat(response.entries()).extracting(WaitEstimatesResponse.Entry::estimatedWaitMinutes).containsExactly(0, 10, 20);
        assertThat(response.entries()).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo(Source.BASELINE);
            assertThat(e.modelVersion()).isEqualTo("baseline-v1");
            assertThat(e.fallbackReason()).isEqualTo("ML_UNAVAILABLE");
        });
        assertThat(response.entries().get(1).lowerBoundMinutes()).isEqualTo(5);
        assertThat(response.entries().get(1).upperBoundMinutes()).isEqualTo(20);
        assertThat(ml.requests()).hasSize(1);

        // The queue changes, but the service is not tried again during the back-off...
        checkIn(drA);
        estimates();
        assertThat(ml.requests()).hasSize(1);

        // ...and is tried again once it has passed.
        clock.set(clock.instant().plus(Duration.ofSeconds(31)));
        modelAnswers(12, 8, 18, "wait-hgb-test-1");
        assertThat(estimates().entries()).allSatisfy(e -> assertThat(e.source()).isEqualTo(Source.MODEL));
    }

    @Test
    void aSlowServiceTimesOutQuicklyAndTheBaselineIsUsed() {
        ml.respond(body -> Reply.slow(3_000, modelAnswer(instanceCount(body), 20, 15, 25, "late")));

        long started = System.nanoTime();
        WaitEstimatesResponse response = estimates();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(1_500);
        assertThat(response.entries()).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo(Source.BASELINE);
            assertThat(e.fallbackReason()).isEqualTo("ML_UNAVAILABLE");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // negative estimate
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P,{"estimatedWaitMinutes":-4,"lowerBoundMinutes":0,"upperBoundMinutes":3,"source":"MODEL"}]}""",
            // lower bound above the estimate
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P,{"estimatedWaitMinutes":10,"lowerBoundMinutes":12,"upperBoundMinutes":15,"source":"MODEL"}]}""",
            // implausibly long (more than a day)
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P,{"estimatedWaitMinutes":2000,"lowerBoundMinutes":1,"upperBoundMinutes":3000,"source":"MODEL"}]}""",
            // missing estimate
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P,{"lowerBoundMinutes":1,"upperBoundMinutes":3,"source":"MODEL"}]}""",
            // one prediction too few
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P]}""",
            // unknown schema version
            """
            {"schemaVersion":"2","modelVersion":"v","predictions":[P,P,P]}""",
            // no model version
            """
            {"schemaVersion":"1","modelVersion":"","predictions":[P,P,P]}""",
            // unknown source
            """
            {"schemaVersion":"1","modelVersion":"v","predictions":[P,P,{"estimatedWaitMinutes":5,"lowerBoundMinutes":1,"upperBoundMinutes":9,"source":"GUESS"}]}"""
    })
    void anInvalidPredictionIsNeverShown(String template) {
        String valid = """
                {"estimatedWaitMinutes":5,"lowerBoundMinutes":1,"upperBoundMinutes":9,"source":"MODEL"}""";
        ml.respond(body -> Reply.json(template.replace("P", valid)));

        assertThat(estimates().entries()).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo(Source.BASELINE);
            assertThat(e.fallbackReason()).isEqualTo("INVALID_PREDICTION");
        });
    }

    @Test
    void theServiceMayDeclineToUseItsModelForARow() {
        String baselineRow = """
                {"estimatedWaitMinutes":null,"lowerBoundMinutes":null,"upperBoundMinutes":null,"source":"BASELINE","reason":"INSUFFICIENT_HISTORY"}""";
        ml.respond(body -> Reply.json("""
                {"schemaVersion":"1","modelVersion":"wait-hgb-test-1","predictions":[%s,%s,%s]}"""
                .formatted(baselineRow, baselineRow, baselineRow)));

        assertThat(estimates().entries()).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo(Source.BASELINE);
            assertThat(e.modelVersion()).isEqualTo("baseline-v1");
            assertThat(e.fallbackReason()).isEqualTo("INSUFFICIENT_HISTORY");
        });
    }

    @Test
    void estimatesAreReusedUntilTheQueueChanges() throws Exception {
        modelAnswers(20, 15, 25, "wait-hgb-test-1");
        estimates();
        estimates();
        mvc.perform(get("/api/v1/public/queue-status/{code}", statusCode(second))).andExpect(status().isOk());
        assertThat(ml.requests()).hasSize(1);

        checkIn(drA);
        estimates();
        assertThat(ml.requests()).hasSize(2);

        // Time alone also refreshes them (the doctor's current consultation keeps running).
        clock.set(clock.instant().plus(Duration.ofSeconds(61)));
        estimates();
        assertThat(ml.requests()).hasSize(3);
    }

    @Test
    void aNewModelVersionShowsUpOnTheNextRefresh() {
        modelAnswers(20, 15, 25, "wait-hgb-2026-01");
        assertThat(estimates().entries().get(0).modelVersion()).isEqualTo("wait-hgb-2026-01");

        modelAnswers(18, 14, 23, "wait-hgb-2026-02");
        checkIn(drA);
        assertThat(estimates().entries().get(0).modelVersion()).isEqualTo("wait-hgb-2026-02");
    }

    @Test
    void thePatientSeesARangeOnlyWhileWaiting() throws Exception {
        modelAnswers(23.4, 17.2, 31.9, "wait-hgb-test-1");

        mvc.perform(get("/api/v1/public/queue-status/{code}", statusCode(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedWait.estimatedWaitMinutes").value(23))
                .andExpect(jsonPath("$.estimatedWait.lowerBoundMinutes").value(17))
                .andExpect(jsonPath("$.estimatedWait.upperBoundMinutes").value(32))
                .andExpect(jsonPath("$.estimatedWait.modelVersion").doesNotExist());

        at(10, 20);
        queue.complete(desk, jdbc.queryForObject(
                "select id from queue_entries where status = 'IN_CONSULTATION'", java.util.UUID.class));
        queue.callNext(desk, drA.getId());
        mvc.perform(get("/api/v1/public/queue-status/{code}", statusCode(first)))
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.estimatedWait").doesNotExist());
    }

    @Test
    void estimatesAreClinicScopedAndDoctorsSeeOnlyTheirOwnQueue() throws Exception {
        DoctorProfile drB = doctor(clinic, "Dr. B");
        String doctorB = login(staff(clinic, Role.DOCTOR, drB, "b@city.test"));
        String doctorA = login(staff(clinic, Role.DOCTOR, drA, "a@city.test"));
        var other = clinic("Other Clinic");
        String otherDesk = login(staff(other, Role.RECEPTIONIST, null, "desk@other.test"));

        mvc.perform(get("/api/v1/queues/today/wait-estimates").param("doctorId", drA.getId().toString()).with(bearer(otherDesk)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/queues/today/wait-estimates").param("doctorId", drA.getId().toString()).with(bearer(doctorB)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/queues/today/wait-estimates").with(bearer(doctorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(drA.getId().toString()))
                .andExpect(jsonPath("$.entries.length()").value(3));
        mvc.perform(get("/api/v1/queues/today/wait-estimates").with(bearer(doctorB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(get("/api/v1/queues/today/wait-estimates")).andExpect(status().isUnauthorized());
    }

    @Test
    void theQueueNeverWaitsForOrDependsOnTheMlService() {
        // A service that would hang every call for 3 s.
        ml.respond(body -> Reply.slow(3_000, "{}"));

        long started = System.nanoTime();
        at(10, 30);
        QueueEntryResponse walkIn = checkIn(drA);
        queue.complete(desk, jdbc.queryForObject(
                "select id from queue_entries where status = 'IN_CONSULTATION'", java.util.UUID.class));
        QueueEntryResponse called = queue.callNext(desk, drA.getId());
        queue.skip(desk, called.id());
        queue.requeue(desk, called.id());
        QueueEntryResponse again = queue.callNext(desk, drA.getId());
        queue.startConsultation(desk, again.id());
        queue.complete(desk, again.id());
        queue.callNext(desk, drA.getId());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(ml.requests()).as("queue operations never call the ML service").isEmpty();
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(walkIn.status().name()).isEqualTo("WAITING");
        assertThat(List.of(first, second)).isNotEmpty();
    }

    private String statusCode(QueueEntryResponse entry) {
        return jdbc.queryForObject("select status_code from queue_entries where id = ?", String.class, entry.id());
    }
}

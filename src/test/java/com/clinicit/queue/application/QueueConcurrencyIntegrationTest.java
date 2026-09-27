package com.clinicit.queue.application;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.domain.QueueStatus;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires real concurrent transactions at PostgreSQL. Every task waits on a shared
 * start gate so they hit the database together rather than one after another.
 */
class QueueConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int THREADS = 24;

    @Autowired QueueService queue;
    @Autowired TransactionTemplate tx;

    ExecutorService pool;
    Clinic clinic;
    DoctorProfile doctor;
    Actor desk;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
        clinic = clinic("Busy Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** Runs each input concurrently and returns per-task outcomes (result or exception). */
    private <T, R> List<Outcome<R>> race(List<T> inputs, Function<T, R> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(inputs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<R>>> futures = new ArrayList<>();

        for (T input : inputs) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return Outcome.ok(action.apply(input));
                } catch (RuntimeException e) {
                    return Outcome.failed(e);
                }
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<Outcome<R>> outcomes = new ArrayList<>();
        for (Future<Outcome<R>> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    record Outcome<R>(R value, RuntimeException error) {
        static <R> Outcome<R> ok(R value) { return new Outcome<>(value, null); }
        static <R> Outcome<R> failed(RuntimeException e) { return new Outcome<>(null, e); }
        boolean succeeded() { return error == null; }
    }

    @Test
    void simultaneousJoinsGetUniqueGapFreeTokens() throws Exception {
        // Includes the first join of the day, where no counter row exists yet.
        DoctorProfile second = doctor(clinic, "Dr. Mehta");
        List<UUID> appointmentIds = IntStream.range(0, THREADS)
                .mapToObj(i -> arrivedAppointment(i % 2 == 0 ? doctor : second, patient(clinic, "P" + i)).getId())
                .toList();

        List<Outcome<QueueEntryResponse>> outcomes = race(appointmentIds, id -> queue.join(desk, id));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).extracting(o -> o.value().tokenNumber())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, THREADS).boxed().toList());
        assertThat(jdbc.queryForObject(
                "select last_token from queue_token_counters where clinic_id = ?", Integer.class, clinic.getId()))
                .isEqualTo(THREADS);
    }

    @Test
    void sameAppointmentJoinedConcurrentlyGetsExactlyOneEntry() throws Exception {
        Appointment appointment = arrivedAppointment(doctor, patient(clinic, "Double Click"));
        List<UUID> sameIdManyTimes = IntStream.range(0, 10).mapToObj(i -> appointment.getId()).toList();

        List<Outcome<QueueEntryResponse>> outcomes = race(sameIdManyTimes, id -> queue.join(desk, id));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allMatch(o -> o.error() instanceof InvalidStateTransitionException);
        assertThat(jdbc.queryForObject("select count(*) from queue_entries", Integer.class)).isEqualTo(1);
        // The losing transactions rolled back their counter increments too.
        assertThat(jdbc.queryForObject("select last_token from queue_token_counters", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCallNextCallsExactlyOnePatient() throws Exception {
        for (int i = 0; i < 10; i++) {
            queue.join(desk, arrivedAppointment(doctor, patient(clinic, "P" + i)).getId());
        }
        List<UUID> sameDoctor = IntStream.range(0, 10).mapToObj(i -> doctor.getId()).toList();

        List<Outcome<QueueEntryResponse>> outcomes = race(sameDoctor, id -> queue.callNext(desk, id));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value().tokenNumber())
                .isEqualTo(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allMatch(o -> o.error() instanceof BusinessRuleException b && b.getCode().equals("DOCTOR_BUSY"));
        assertThat(jdbc.queryForObject(
                "select count(*) from queue_entries where status = 'CALLED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void callNextPassesOverAHeadOfQueueRowLockedByAnotherTransaction() throws Exception {
        QueueEntryResponse first = queue.join(desk, arrivedAppointment(doctor, patient(clinic, "Being Skipped")).getId());
        QueueEntryResponse second = queue.join(desk, arrivedAppointment(doctor, patient(clinic, "Next In Line")).getId());

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Another transaction holds the lock on #1 exactly as a concurrent "skip" does
        // (Hibernate's PESSIMISTIC_WRITE is FOR NO KEY UPDATE, which still lets foreign-key
        // checks from other transactions, e.g. notification inserts, proceed).
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("select id from queue_entries where id = ? for no key update", first.id());
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // Must not block on #1 and must not come back empty: it calls #2.
        QueueEntryResponse called = pool.submit(() -> queue.callNext(desk, doctor.getId())).get(5, TimeUnit.SECONDS);

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(called.id()).isEqualTo(second.id());
        assertThat(queue.get(desk, first.id()).status()).isEqualTo(QueueStatus.WAITING);
    }

    @Test
    void concurrentSkipAndStartOfTheSameEntryHaveOneWinner() throws Exception {
        QueueEntryResponse entry = queue.join(desk, arrivedAppointment(doctor, patient(clinic, "Contested")).getId());
        queue.callNext(desk, doctor.getId());

        List<Function<UUID, QueueEntryResponse>> actions = List.of(id -> queue.skip(desk, id), id -> queue.startConsultation(desk, id));
        List<Outcome<QueueEntryResponse>> outcomes = race(actions, action -> action.apply(entry.id()));

        // Both are legal from CALLED, but only one can happen; the loser then sees the new state.
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        QueueStatus finalStatus = queue.get(desk, entry.id()).status();
        assertThat(finalStatus).isIn(QueueStatus.SKIPPED, QueueStatus.IN_CONSULTATION);
        assertThat(appointmentStatus(entry.appointmentId()).name()).isEqualTo(finalStatus.name());
    }
}

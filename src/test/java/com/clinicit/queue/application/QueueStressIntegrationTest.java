package com.clinicit.queue.application;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many receptionists and doctors hammering the same clinic with every queue operation at
 * once. Business-rule rejections are expected (someone else got there first); anything
 * else, such as a deadlock, lock timeout, optimistic-lock failure or constraint violation,
 * means the locking design is wrong. Afterwards every invariant is checked in SQL.
 */
class QueueStressIntegrationTest extends PostgresIntegrationTest {

    private static final int WORKERS = 12;
    private static final int OPS_PER_WORKER = 120;
    private static final int PATIENTS_PER_DOCTOR = 25;

    @Autowired QueueService queue;

    Actor desk;

    @Test
    void mixedConcurrentOperationsNeverDeadlockOrBreakInvariants() throws Exception {
        Clinic clinic = clinic("Stress Clinic");
        desk = frontDesk(clinic);
        List<DoctorProfile> doctorList = List.of(
                doctor(clinic, "Dr. A"), doctor(clinic, "Dr. B"), doctor(clinic, "Dr. C"));

        List<UUID> arrived = new ArrayList<>();
        for (DoctorProfile doctor : doctorList) {
            for (int i = 0; i < PATIENTS_PER_DOCTOR; i++) {
                arrived.add(arrivedAppointment(doctor, patient(clinic, doctor.getDisplayName() + " P" + i)).getId());
            }
        }
        Collections.shuffle(arrived, new Random(42));

        ConcurrentLinkedQueue<UUID> notYetJoined = new ConcurrentLinkedQueue<>(arrived);
        List<UUID> entryIds = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch go = new CountDownLatch(1);
        for (int w = 0; w < WORKERS; w++) {
            Random random = new Random(1000 + w);
            pool.submit(() -> {
                go.await();
                for (int op = 0; op < OPS_PER_WORKER; op++) {
                    try {
                        runRandomOperation(random, doctorList, notYetJoined, entryIds);
                    } catch (BusinessRuleException expected) {
                        // lost a race or illegal right now: fine
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("workers finished (no hang)").isTrue();

        assertThat(unexpected).as("unexpected errors: %s", unexpected).isEmpty();
        assertThat(entryIds).as("the test actually exercised the queue").hasSizeGreaterThan(20);
        assertInvariants(clinic);
    }

    private void runRandomOperation(
            Random random, List<DoctorProfile> doctorList, ConcurrentLinkedQueue<UUID> notYetJoined, List<UUID> entryIds
    ) {
        int dice = random.nextInt(10);
        if (dice < 3 || entryIds.isEmpty()) {
            UUID appointmentId = notYetJoined.poll();
            if (appointmentId != null) {
                entryIds.add(queue.join(desk, appointmentId).id());
            }
            return;
        }
        if (dice < 5) {
            queue.callNext(desk, doctorList.get(random.nextInt(doctorList.size())).getId());
            return;
        }
        UUID entry = entryIds.get(random.nextInt(entryIds.size()));
        QueueEntryResponse result = switch (dice) {
            case 5 -> queue.startConsultation(desk, entry);
            case 6 -> queue.complete(desk, entry);
            case 7 -> queue.skip(desk, entry);
            case 8 -> queue.requeue(desk, entry);
            default -> queue.markNoShow(desk, entry);
        };
    }

    private void assertInvariants(Clinic clinic) {
        List<Integer> tokens = jdbc.queryForList(
                "select token_number from queue_entries where clinic_id = ? order by token_number",
                Integer.class, clinic.getId());
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= tokens.size(); i++) {
            expected.add(i);
        }
        assertThat(tokens).as("tokens are unique and gap-free").isEqualTo(expected);

        assertThat(jdbc.queryForObject("""
                select coalesce(max(active), 0) from (
                    select count(*) as active from queue_entries
                    where status in ('CALLED', 'IN_CONSULTATION')
                    group by doctor_id, queue_date) per_doctor
                """, Integer.class))
                .as("at most one active patient per doctor").isLessThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("""
                select count(*) from queue_entries q join appointments a on a.id = q.appointment_id
                where a.status <> q.status
                """, Integer.class))
                .as("every appointment mirrors its queue entry").isZero();

        assertThat(jdbc.queryForObject("""
                select count(*) from appointments a
                where not exists (select 1 from queue_entries q where q.appointment_id = a.id)
                  and a.status <> 'ARRIVED'
                """, Integer.class))
                .as("appointments outside the queue are untouched").isZero();

        assertThat(jdbc.queryForObject(
                "select last_token from queue_token_counters where clinic_id = ?", Integer.class, clinic.getId()))
                .as("counter matches issued tokens").isEqualTo(tokens.size());

        assertHistoryConsistent(clinic);
    }

    /** The operational history is a complete, gap-free record of what the racing workers did. */
    private void assertHistoryConsistent(Clinic clinic) {
        assertThat(jdbc.queryForObject("""
                select count(*) from queue_entries q
                where q.clinic_id = ?
                  and (select count(*) from operational_events e where e.queue_entry_id = q.id) <> q.version + 1
                """, Integer.class, clinic.getId()))
                .as("one event per committed transition (the join plus one per version bump)").isZero();

        assertThat(jdbc.queryForObject("""
                select count(*) from queue_entries q
                where q.clinic_id = ?
                  and (select count(*) from operational_events e
                       where e.queue_entry_id = q.id and e.event_type = 'WAITING') <> 1
                """, Integer.class, clinic.getId()))
                .as("exactly one WAITING (joined) event per entry").isZero();

        assertThat(jdbc.queryForObject("""
                with ordered as (
                    select previous_status, occurred_at,
                           lag(case event_type when 'REQUEUED' then 'WAITING' else event_type end)
                               over (partition by appointment_id order by seq) as status_before,
                           lag(occurred_at) over (partition by appointment_id order by seq) as before_at
                    from operational_events where clinic_id = ?
                )
                select count(*) from ordered
                where previous_status is distinct from coalesce(status_before, 'ARRIVED')
                   or occurred_at < before_at
                """, Integer.class, clinic.getId()))
                .as("each event starts where the previous one ended, in time order").isZero();

        assertThat(jdbc.queryForObject("""
                select count(*) from appointments a
                join lateral (
                    select case event_type when 'REQUEUED' then 'WAITING' else event_type end as status_after
                    from operational_events e where e.appointment_id = a.id
                    order by seq desc limit 1
                ) last on true
                where a.clinic_id = ? and last.status_after <> a.status
                """, Integer.class, clinic.getId()))
                .as("the latest event matches every appointment's current status").isZero();

        assertThat(jdbc.queryForObject("""
                select coalesce(sum(case when event_type in ('WAITING', 'REQUEUED') then 1
                                         when previous_status = 'WAITING' then -1 else 0 end), 0)
                from operational_events where clinic_id = ?
                """, Integer.class, clinic.getId()))
                .as("queue length replayed from history equals the patients waiting now")
                .isEqualTo(jdbc.queryForObject(
                        "select count(*) from queue_entries where clinic_id = ? and status = 'WAITING'",
                        Integer.class, clinic.getId()));
    }
}

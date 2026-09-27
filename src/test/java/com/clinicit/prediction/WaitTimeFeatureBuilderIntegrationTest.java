package com.clinicit.prediction;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.prediction.application.WaitTimeFeatureBuilder;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import com.clinicit.queue.api.QueueEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitTimeFeatureBuilderIntegrationTest extends PredictionScenario {

    @Autowired WaitTimeFeatureBuilder features;

    QueueEntryResponse s;
    QueueEntryResponse u;
    QueueEntryResponse v;

    @BeforeEach
    void setUp() {
        setUpClinic();
    }

    /**
     * Yesterday: two consultations of 12 and 8 minutes after waits of 20 and 27 minutes.
     * Today: R, S, U, V join; R is seen 09:30–09:45; S is called at 09:50.
     */
    private void history() {
        var yesterday = TODAY.minusDays(1);
        at(yesterday, 10, 0);
        QueueEntryResponse p = checkIn(drA);
        at(yesterday, 10, 5);
        QueueEntryResponse q = checkIn(drA);
        at(yesterday, 10, 20);
        callAndStart(drA);
        at(yesterday, 10, 32);
        queue.complete(desk, p.id());
        callAndStart(drA);
        at(yesterday, 10, 40);
        queue.complete(desk, q.id());

        at(9, 0);
        QueueEntryResponse r = checkIn(drA);
        at(9, 5);
        s = checkIn(drA);
        at(9, 10);
        u = checkIn(drA);
        at(9, 20);
        v = checkIn(drA);
        at(9, 30);
        callAndStart(drA);
        at(9, 45);
        queue.complete(desk, r.id());
        at(9, 50);
        callAndStart(drA);
    }

    private WaitTimeFeatures asOf(QueueEntryResponse entry, Instant asOf, long upToSeq) {
        return features.at(clinic.getId(), drA.getId(), entry.id(), entry.tokenNumber(), asOf, upToSeq, CLINIC_ZONE);
    }

    @Test
    void featuresDescribeTheQueueAsItWasAtThatMoment() {
        history();
        Instant tenOClock = at(10, 0);

        WaitTimeFeatures f = asOf(v, tenOClock, Long.MAX_VALUE);

        assertThat(f.dayOfWeek()).isEqualTo(2); // Tuesday 10 March 2026, in the clinic
        assertThat(f.minuteOfDay()).isEqualTo(600.0);
        assertThat(f.queueLength()).isEqualTo(2);         // U and V
        assertThat(f.patientsAhead()).isEqualTo(1);       // U
        assertThat(f.completedToday()).isEqualTo(1);      // R
        assertThat(f.doctorBusy()).isEqualTo(1);          // S
        assertThat(f.activePatientMinutes()).isEqualTo(10.0);
        assertThat(f.callsLastHour()).isEqualTo(2);       // R at 09:30, S at 09:50
        assertThat(f.recentConsultationMinutes()).isEqualTo(15.0);
        assertThat(f.historicalConsultationMinutes()).isEqualTo(10.0);
        assertThat(f.historicalConsultationCount()).isEqualTo(2);
        assertThat(f.historicalWaitMinutes()).isEqualTo(23.5);
    }

    @Test
    void laterEventsNeverChangeTheFeaturesOfAnEarlierMoment() {
        history();
        Instant tenOClock = at(10, 0);
        WaitTimeFeatures before = asOf(v, tenOClock, Long.MAX_VALUE);

        // Everything that happens next: S finishes, U is called, V is called, more patients join.
        at(10, 5);
        queue.complete(desk, s.id());
        callAndStart(drA);
        at(10, 6);
        checkIn(drA);
        at(10, 20);
        queue.complete(desk, u.id());
        callAndStart(drA);

        assertThat(asOf(v, tenOClock, Long.MAX_VALUE)).isEqualTo(before);
    }

    @Test
    void aSnapshotCanBePinnedBetweenEventsWithTheSameTimestamp() {
        Instant sameInstant = at(10, 10);
        QueueEntryResponse x = checkIn(drA);
        long xJoined = joinSeq(x.id());
        checkIn(drA); // at exactly the same time, but after X

        assertThat(asOf(x, sameInstant, xJoined).queueLength()).isEqualTo(1);
        assertThat(asOf(x, sameInstant, Long.MAX_VALUE).queueLength()).isEqualTo(2);
    }

    @Test
    void aNewDoctorOrQuietDayHasNoHistoryRatherThanZeroes() {
        at(9, 0);
        QueueEntryResponse first = checkIn(drA);

        WaitTimeFeatures f = asOf(first, clock.instant(), Long.MAX_VALUE);
        assertThat(f.patientsAhead()).isZero();
        assertThat(f.queueLength()).isEqualTo(1);
        assertThat(f.doctorBusy()).isZero();
        assertThat(f.recentConsultationMinutes()).isNull();
        assertThat(f.historicalConsultationMinutes()).isNull();
        assertThat(f.historicalConsultationCount()).isZero();
        assertThat(f.historicalWaitMinutes()).isNull();
    }

    @Test
    void anotherClinicsActivityIsInvisible() {
        history();
        Instant tenOClock = at(10, 0);
        WaitTimeFeatures before = asOf(v, tenOClock, Long.MAX_VALUE);

        Clinic other = clinic("Other Clinic");
        DoctorProfile otherDoctor = doctor(other, "Dr. Other");
        var otherDesk = frontDesk(other);
        at(9, 55);
        for (int i = 0; i < 3; i++) {
            queue.join(otherDesk, arrivedAppointment(otherDoctor, patient(other, "Other " + i)).getId());
        }
        queue.callNext(otherDesk, otherDoctor.getId());

        assertThat(asOf(v, tenOClock, Long.MAX_VALUE)).isEqualTo(before);
    }
}

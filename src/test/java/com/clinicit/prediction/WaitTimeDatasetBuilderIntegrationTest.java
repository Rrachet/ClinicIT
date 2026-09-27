package com.clinicit.prediction;

import com.clinicit.prediction.application.WaitTimeDatasetBuilder;
import com.clinicit.prediction.application.WaitTimeDatasetBuilder.Example;
import com.clinicit.queue.api.QueueEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class WaitTimeDatasetBuilderIntegrationTest extends PredictionScenario {

    @Autowired WaitTimeDatasetBuilder dataset;

    @BeforeEach
    void setUp() {
        setUpClinic();
    }

    /**
     * 09:00 A, B and C join at the same instant. A is seen 09:10–09:20, B 09:20–09:30.
     * D joins 09:22. C is skipped at 09:25 (never called in that waiting period), comes back
     * at 09:35 and is seen 09:40–09:48. D is called at 09:50.
     */
    private void day() {
        at(9, 0);
        QueueEntryResponse a = checkIn(drA);
        QueueEntryResponse b = checkIn(drA);
        QueueEntryResponse c = checkIn(drA);
        at(9, 10);
        queue.callNext(desk, drA.getId());
        at(9, 12);
        queue.startConsultation(desk, a.id());
        at(9, 20);
        queue.complete(desk, a.id());
        queue.callNext(desk, drA.getId());
        at(9, 21);
        queue.startConsultation(desk, b.id());
        at(9, 22);
        checkIn(drA);
        at(9, 25);
        queue.skip(desk, c.id());
        at(9, 30);
        queue.complete(desk, b.id());
        at(9, 35);
        queue.requeue(desk, c.id());
        at(9, 40);
        callAndStart(drA);
        at(9, 48);
        queue.complete(desk, c.id());
        at(9, 50);
        queue.callNext(desk, drA.getId());
    }

    @Test
    void oneExamplePerMomentAnEstimateWouldHaveBeenShown() {
        day();

        List<Example> examples = dataset.build(null);

        // (snapshot, patients ahead, queue length, minutes until first call)
        assertThat(examples).extracting(Example::snapshot, e -> e.features().patientsAhead(),
                        e -> e.features().queueLength(), Example::actualWaitMinutes)
                .containsExactly(
                        tuple("JOIN", 0, 1, 10.0),         // A, alone at that instant
                        tuple("JOIN", 1, 2, 20.0),         // B, pinned before C joined
                        tuple("QUEUE_MOVED", 0, 2, 10.0),  // B after A was called (C still waiting)
                        tuple("JOIN", 1, 2, 28.0),         // D: C ahead
                        tuple("QUEUE_MOVED", 0, 1, 25.0),  // D after C was skipped
                        tuple("QUEUE_MOVED", 1, 2, 15.0),  // D after C came back
                        tuple("QUEUE_MOVED", 0, 1, 10.0)); // D after C was called
        // C's first waiting period ended in a skip: no observed wait, so no example.
        assertThat(examples).allSatisfy(e -> assertThat(e.localDate()).isEqualTo(TODAY));
        assertThat(examples.get(3).features().completedToday()).isEqualTo(1);
        assertThat(examples.get(3).features().doctorBusy()).isEqualTo(1);
    }

    @Test
    void laterActivityNeverChangesExamplesAlreadyInTheDataset() throws IOException {
        day();
        List<String> before = csv(dataset.build(null)).lines().toList();

        // The rest of the day and the whole next day.
        at(10, 30);
        checkIn(drA);
        at(TODAY.plusDays(1), 9, 0);
        checkIn(drA);
        checkIn(drA);
        at(TODAY.plusDays(1), 9, 15);
        QueueEntryResponse first = callAndStart(drA);
        at(TODAY.plusDays(1), 9, 30);
        queue.complete(desk, first.id());
        queue.callNext(desk, drA.getId());
        clock.set(NOW);

        List<String> after = csv(dataset.build(null)).lines().toList();
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(after).hasSizeGreaterThan(before.size());
    }

    @Test
    void theCsvIsDeterministicAndHasNoIdentifiers() throws IOException {
        day();

        String first = csv(dataset.build(null));
        String second = csv(dataset.build(null));

        assertThat(first).isEqualTo(second);
        assertThat(first.lines().findFirst().orElseThrow())
                .isEqualTo(String.join(",", WaitTimeDatasetBuilder.COLUMNS));
        assertThat(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-").matcher(first).find()).isFalse();
        assertThat(first.lines().skip(1).findFirst().orElseThrow())
                .startsWith("2026-03-10,2026-03-10T03:30:00Z,JOIN,2,540.000,0,1,0,0,0.000,0,,,0,,10.000");
    }

    @Test
    void oneClinicCanBeExportedAlone() {
        day();
        var other = clinic("Other");
        var otherDoctor = doctor(other, "Dr. Other");
        var otherDesk = frontDesk(other);
        at(9, 0);
        queue.join(otherDesk, arrivedAppointment(otherDoctor, patient(other, "X")).getId());
        at(9, 5);
        queue.callNext(otherDesk, otherDoctor.getId());

        assertThat(dataset.build(clinic.getId())).hasSize(7);
        assertThat(dataset.build(other.getId())).hasSize(1);
        assertThat(dataset.build(null)).hasSize(8);
    }

    private String csv(List<Example> examples) throws IOException {
        StringWriter out = new StringWriter();
        dataset.writeCsv(examples, out);
        return out.toString();
    }
}

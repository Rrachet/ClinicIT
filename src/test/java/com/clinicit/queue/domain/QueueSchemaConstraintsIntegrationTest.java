package com.clinicit.queue.domain;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database must hold the queue invariants on its own, even if a future code
 * path (or a manual SQL fix) bypasses the service. These go straight to SQL.
 */
class QueueSchemaConstraintsIntegrationTest extends PostgresIntegrationTest {

    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile doctor;
    QueueEntryResponse first;
    QueueEntryResponse second;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        first = queue.join(arrivedAppointment(doctor, patient(clinic, "A")).getId());
        second = queue.join(arrivedAppointment(doctor, patient(clinic, "B")).getId());
    }

    @Test
    void duplicateTokenForClinicAndDayIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set token_number = ? where id = ?", first.tokenNumber(), second.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondActivePatientForSameDoctorIsRejected() {
        jdbc.update("update queue_entries set status = 'CALLED' where id = ?", first.id());

        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set status = 'IN_CONSULTATION' where id = ?", second.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondEntryForSameAppointmentIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set appointment_id = ? where id = ?", first.appointmentId(), second.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set status = 'TELEPORTED' where id = ?", first.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update appointments set status = 'TELEPORTED' where id = ?", first.appointmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void appointmentCannotReferencePatientOfAnotherClinic() {
        Clinic other = clinic("Other Clinic");
        var foreignPatient = patient(other, "Foreign");

        assertThatThrownBy(() -> jdbc.update(
                "update appointments set patient_id = ? where id = ?", foreignPatient.getId(), first.appointmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void appointmentCannotReferenceDoctorOfAnotherClinic() {
        DoctorProfile foreignDoctor = doctor(clinic("Other Clinic"), "Dr. Foreign");

        assertThatThrownBy(() -> jdbc.update(
                "update appointments set doctor_id = ? where id = ?", foreignDoctor.getId(), first.appointmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void queueEntryMustMatchItsAppointmentsDoctorAndClinic() {
        DoctorProfile colleague = doctor(clinic, "Dr. Mehta");

        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set doctor_id = ? where id = ?", colleague.getId(), first.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update queue_entries set clinic_id = ? where id = ?", clinic("Other Clinic").getId(), first.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonPositiveTokenIsRejected() {
        assertThatThrownBy(() -> jdbc.update("update queue_entries set token_number = 0 where id = ?", first.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

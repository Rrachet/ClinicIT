package com.clinicit.identity.security;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.patient.domain.Patient;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.queue.domain.QueueStatus;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Staff of Clinic A try to read and change every kind of Clinic B resource, by id and
 * through every listing. Another clinic's ids must behave exactly like ids that do not
 * exist (404), listings must never include Clinic B's rows, and nothing in Clinic B may
 * change. The admin runs every case because it has every role, so any failure is a
 * scoping failure rather than a role check.
 */
class ClinicIsolationIntegrationTest extends PostgresIntegrationTest {

    private static final String B_PATIENT_NAME = "Bina Clinic-B-Only";

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;

    // Clinic A (the caller)
    Clinic clinicA;
    DoctorProfile doctorA;
    Patient patientA;
    String adminA;
    String receptionistA;
    String doctorUserA;

    // Clinic B (the target)
    Clinic clinicB;
    DoctorProfile doctorB;
    Patient patientB;
    Appointment confirmedB;
    QueueEntryResponse waitingEntryB;
    UserAccount receptionistB;

    @BeforeEach
    void setUp() {
        clinicA = clinic("Clinic A");
        doctorA = doctor(clinicA, "Dr. A");
        patientA = patient(clinicA, "Asha Clinic-A");
        adminA = login(staff(clinicA, Role.ADMIN, null, "admin@a.test"));
        receptionistA = login(staff(clinicA, Role.RECEPTIONIST, null, "desk@a.test"));
        doctorUserA = login(staff(clinicA, Role.DOCTOR, doctorA, "doctor@a.test"));

        clinicB = clinic("Clinic B");
        doctorB = doctor(clinicB, "Dr. B");
        patientB = patient(clinicB, B_PATIENT_NAME);
        confirmedB = appointmentOn(doctorB, patientB, TODAY, AppointmentStatus.CONFIRMED);
        confirmedB.setScheduledAt(TODAY.atTime(9, 0)); // in the past, so no-show would be allowed
        appointments.save(confirmedB);
        waitingEntryB = queue.join(frontDesk(clinicB), arrivedAppointment(doctorB, patientB).getId());
        receptionistB = staff(clinicB, Role.RECEPTIONIST, null, "desk@b.test");
    }

    private List<MockHttpServletRequestBuilder> requestsForClinicBResources() {
        UUID entry = waitingEntryB.id();
        UUID appointment = confirmedB.getId();
        return List.of(
                get("/api/v1/patients/{id}", patientB.getId()),
                get("/api/v1/appointments/{id}", appointment),
                get("/api/v1/appointments/{id}", waitingEntryB.appointmentId()),
                post("/api/v1/appointments/{id}/confirm", appointment),
                post("/api/v1/appointments/{id}/cancel", appointment),
                post("/api/v1/appointments/{id}/arrive", appointment),
                post("/api/v1/appointments/{id}/no-show", appointment),
                json(post("/api/v1/appointments"), """
                        {"patientId":"%s","doctorId":"%s","scheduledAt":"%sT17:00:00"}"""
                        .formatted(patientB.getId(), doctorA.getId(), TODAY)),
                json(post("/api/v1/appointments"), """
                        {"patientId":"%s","doctorId":"%s","scheduledAt":"%sT17:00:00"}"""
                        .formatted(patientA.getId(), doctorB.getId(), TODAY)),
                json(post("/api/v1/queue-entries"), "{\"appointmentId\":\"%s\"}".formatted(appointment)),
                get("/api/v1/queue-entries/{id}", entry),
                post("/api/v1/queue-entries/{id}/start", entry),
                post("/api/v1/queue-entries/{id}/complete", entry),
                post("/api/v1/queue-entries/{id}/skip", entry),
                post("/api/v1/queue-entries/{id}/requeue", entry),
                post("/api/v1/queue-entries/{id}/no-show", entry),
                json(post("/api/v1/queues/call-next"), "{\"doctorId\":\"%s\"}".formatted(doctorB.getId())),
                get("/api/v1/queues/today").param("doctorId", doctorB.getId().toString()),
                post("/api/v1/users/{id}/disable", receptionistB.getId()),
                json(post("/api/v1/users"), """
                        {"email":"new-doc@a.test","fullName":"New","password":"%s","role":"DOCTOR","doctorProfileId":"%s"}"""
                        .formatted(PASSWORD, doctorB.getId()))
        );
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.with(bearer(token))).andReturn().getResponse();
    }

    private static String describe(MockHttpServletRequestBuilder request) {
        var built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
        return built.getMethod() + " " + built.getRequestURI();
    }

    @Test
    void adminOfClinicAGets404ForEveryClinicBResource() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : requestsForClinicBResources()) {
            MockHttpServletResponse response = send(request, adminA);
            if (response.getStatus() != 404 || response.getContentAsString().contains(B_PATIENT_NAME)) {
                leaks.add(describe(request) + " -> " + response.getStatus() + " " + response.getContentAsString());
            }
        }
        assertThat(leaks).as("requests that did not behave as 'not found'").isEmpty();
        assertClinicBUntouched();
    }

    @Test
    void receptionistAndDoctorOfClinicANeverReachClinicBResources() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (String token : List.of(receptionistA, doctorUserA)) {
            for (MockHttpServletRequestBuilder request : requestsForClinicBResources()) {
                MockHttpServletResponse response = send(request, token);
                // 403 (role not allowed) or 404 (not in your clinic): both say nothing about Clinic B.
                if ((response.getStatus() != 403 && response.getStatus() != 404)
                        || response.getContentAsString().contains(B_PATIENT_NAME)) {
                    leaks.add(describe(request) + " -> " + response.getStatus());
                }
            }
        }
        assertThat(leaks).isEmpty();
        assertClinicBUntouched();
    }

    @Test
    void listingsAndSearchOnlyEverReturnTheCallersClinic() throws Exception {
        appointmentOn(doctorA, patientA, TODAY);
        List<MockHttpServletRequestBuilder> listings = List.of(
                get("/api/v1/patients").param("name", "Clinic"),
                get("/api/v1/appointments").param("date", TODAY.toString()),
                get("/api/v1/appointments").param("date", TODAY.toString()).param("doctorId", doctorB.getId().toString()),
                get("/api/v1/doctors"),
                get("/api/v1/users"));

        for (MockHttpServletRequestBuilder listing : listings) {
            MockHttpServletResponse response = send(listing, adminA);
            String body = response.getContentAsString();
            assertThat(response.getStatus()).as(describe(listing)).isEqualTo(200);
            assertThat(body).as(describe(listing))
                    .doesNotContain(clinicB.getId().toString())
                    .doesNotContain(B_PATIENT_NAME)
                    .doesNotContain(doctorB.getId().toString())
                    .doesNotContain("desk@b.test");
        }
        assertThat(send(get("/api/v1/patients").param("name", "Clinic"), adminA).getContentAsString())
                .contains("Asha Clinic-A");
    }

    @Test
    void clinicIdInRequestsIsIgnoredNotTrusted() throws Exception {
        // Old clients sent clinicId; it must not let anyone write into another clinic.
        MockHttpServletResponse response = send(json(post("/api/v1/patients"), """
                {"clinicId":"%s","fullName":"Smuggled","phone":"1"}""".formatted(clinicB.getId())), receptionistA);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select clinic_id from patients where full_name = 'Smuggled'", UUID.class))
                .isEqualTo(clinicA.getId());
    }

    private void assertClinicBUntouched() {
        assertThat(appointmentStatus(confirmedB.getId())).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(appointmentStatus(waitingEntryB.appointmentId())).isEqualTo(AppointmentStatus.WAITING);
        assertThat(queue.get(frontDesk(clinicB), waitingEntryB.id()).status()).isEqualTo(QueueStatus.WAITING);
        assertThat(users.findById(receptionistB.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from appointments where clinic_id = ?", Integer.class, clinicB.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'new-doc@a.test'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from appointments where clinic_id = ?", Integer.class, clinicA.getId()))
                .as("no cross-clinic appointment was created in A either").isZero();
    }
}

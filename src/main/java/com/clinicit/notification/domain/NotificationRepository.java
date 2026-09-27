package com.clinicit.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByClinicIdAndAppointmentIdOrderByCreatedAtAsc(UUID clinicId, UUID appointmentId);

    Optional<Notification> findByIdAndClinicId(UUID id, UUID clinicId);
}

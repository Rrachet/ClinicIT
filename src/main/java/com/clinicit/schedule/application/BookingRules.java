package com.clinicit.schedule.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.schedule.domain.DoctorSchedule;
import com.clinicit.schedule.domain.Unavailable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The scheduling checks for booking and rescheduling. Callers must hold the doctor row lock
 * ({@code DoctorProfileRepository.findByIdAndClinicIdForUpdate}) so two receptionists cannot
 * both take the last free slot: the lock serialises the check and the insert per doctor.
 */
@Component
public class BookingRules {

    /** Appointments that hold their slot. Cancelled and no-show ones free it again. */
    static final List<String> HOLDING_SLOT = List.of(
            "BOOKED", "CONFIRMED", "ARRIVED", "WAITING", "CALLED", "IN_CONSULTATION", "SKIPPED", "COMPLETED");

    private final DoctorScheduleService schedules;
    private final ClinicTime clinicTime;
    private final JdbcTemplate jdbc;

    public BookingRules(DoctorScheduleService schedules, ClinicTime clinicTime, JdbcTemplate jdbc) {
        this.schedules = schedules;
        this.clinicTime = clinicTime;
        this.jdbc = jdbc;
    }

    /**
     * A booked appointment at {@code start}: inside working hours, not in the break or time
     * off, not in the past, and not overlapping another booked appointment of the doctor.
     *
     * @param ignoring the appointment being rescheduled, or null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSlot(DoctorProfile lockedDoctor, LocalDateTime start, UUID ignoring) {
        DoctorSchedule schedule = schedules.forBooking(lockedDoctor);
        if (!schedule.isConfigured()) return;
        LocalDateTime now = clinicTime.now(lockedDoctor.getClinicId());
        schedule.checkSlot(start, now).ifPresent(BookingRules::refuse);
        if (overlapping(lockedDoctor, start, schedule.appointmentMinutes(), ignoring) > 0) {
            refuse(Unavailable.SLOT_TAKEN);
        }
    }

    /** A walk-in now: the doctor must still be working today (see DoctorSchedule#checkWalkIn). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWalkIn(DoctorProfile doctor, LocalDateTime now) {
        schedules.forBooking(doctor).checkWalkIn(now).ifPresent(BookingRules::refuse);
    }

    private long overlapping(DoctorProfile doctor, LocalDateTime start, int minutes, UUID ignoring) {
        LocalDateTime end = start.plusMinutes(minutes);
        Long count = jdbc.queryForObject("""
                select count(*) from appointments
                where doctor_id = ? and clinic_id = ? and not walk_in
                  and status in (%s)
                  and (?::uuid is null or id <> ?::uuid)
                  and scheduled_at < ? and scheduled_at + make_interval(mins => ?) > ?
                """.formatted(String.join(",", HOLDING_SLOT.stream().map(s -> "'" + s + "'").toList())),
                Long.class, doctor.getId(), doctor.getClinicId(), ignoring, ignoring, end, minutes, start);
        return count == null ? 0 : count;
    }

    private static void refuse(Unavailable reason) {
        throw new BusinessRuleException(reason.name(), reason.message());
    }
}

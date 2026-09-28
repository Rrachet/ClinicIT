package com.clinicit.schedule.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.schedule.api.AvailabilityResponse;
import com.clinicit.schedule.domain.DoctorSchedule;
import com.clinicit.schedule.domain.TimeOff;
import com.clinicit.schedule.domain.Unavailable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A doctor's bookable slots on one day, for the front desk's booking form. */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private final DoctorProfileRepository doctors;
    private final DoctorScheduleService schedules;
    private final ClinicTime clinicTime;
    private final JdbcTemplate jdbc;

    public AvailabilityService(
            DoctorProfileRepository doctors, DoctorScheduleService schedules, ClinicTime clinicTime, JdbcTemplate jdbc
    ) {
        this.doctors = doctors;
        this.schedules = schedules;
        this.clinicTime = clinicTime;
        this.jdbc = jdbc;
    }

    public AvailabilityResponse forDay(Actor actor, UUID doctorId, LocalDate date) {
        DoctorProfile doctor = doctors.findByIdAndClinicId(doctorId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        DoctorSchedule schedule = schedules.forBooking(doctor);
        LocalDateTime now = clinicTime.now(actor.clinicId());

        List<LocalDateTime> booked = jdbc.query("""
                select scheduled_at from appointments
                where doctor_id = ? and clinic_id = ? and not walk_in and status in (%s)
                  and scheduled_at >= ? and scheduled_at < ?
                """.formatted(String.join(",", BookingRules.HOLDING_SLOT.stream().map(s -> "'" + s + "'").toList())),
                (rs, i) -> rs.getObject(1, LocalDateTime.class),
                doctorId, actor.clinicId(), date.atStartOfDay().minusMinutes(schedule.appointmentMinutes()),
                date.plusDays(1).atStartOfDay());

        List<AvailabilityResponse.Slot> slots = new ArrayList<>();
        for (LocalDateTime start : schedule.slotStarts(date)) {
            LocalDateTime end = start.plus(schedule.appointmentLength());
            Unavailable reason = schedule.checkSlot(start, now).orElse(null);
            if (reason == null && booked.stream().anyMatch(b -> b.isBefore(end) && start.isBefore(b.plus(schedule.appointmentLength())))) {
                reason = Unavailable.SLOT_TAKEN;
            }
            slots.add(new AvailabilityResponse.Slot(start, end, reason == null, reason == null ? null : reason.name()));
        }

        List<TimeOff> timeOff = schedule.timeOff().stream()
                .filter(off -> off.overlaps(date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .toList();
        return new AvailabilityResponse(doctorId, date, schedule.isConfigured(), schedule.appointmentMinutes(),
                schedule.hoursOn(date).map(AvailabilityResponse.Hours::from).orElse(null),
                timeOff.stream().map(off -> new AvailabilityResponse.Period(off.startsAt(), off.endsAt())).toList(),
                slots);
    }
}

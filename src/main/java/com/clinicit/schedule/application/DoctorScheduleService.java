package com.clinicit.schedule.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.schedule.domain.DoctorSchedule;
import com.clinicit.schedule.domain.TimeOff;
import com.clinicit.schedule.domain.WorkingHours;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stores doctors' weekly hours, slot length and time off, always scoped to the caller's
 * clinic. Reading is open to all staff (reception books against it); changing it is for
 * admins.
 */
@Service
@Transactional
public class DoctorScheduleService {

    private static final int MAX_TIME_OFF_DAYS = 366;

    private final DoctorProfileRepository doctors;
    private final JdbcTemplate jdbc;
    private final ClinicTime clinicTime;

    public DoctorScheduleService(DoctorProfileRepository doctors, JdbcTemplate jdbc, ClinicTime clinicTime) {
        this.doctors = doctors;
        this.jdbc = jdbc;
        this.clinicTime = clinicTime;
    }

    @Transactional(readOnly = true)
    public DoctorSchedule get(Actor actor, UUID doctorId) {
        return forBooking(requireDoctor(actor.clinicId(), doctorId));
    }

    /**
     * The schedule as used for booking, with current and future time off (and the last 30
     * days, for context). The caller has already checked the doctor's clinic.
     */
    @Transactional(readOnly = true)
    public DoctorSchedule forBooking(DoctorProfile doctor) {
        return load(doctor, clinicTime.now(doctor.getClinicId()).minusDays(30), LocalDateTime.MAX);
    }

    /** The schedule with the time off that touches one day (analytics of past days included). */
    @Transactional(readOnly = true)
    public DoctorSchedule forDate(DoctorProfile doctor, LocalDate date) {
        return load(doctor, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    /** Replaces the weekly hours and slot length. An empty week removes the schedule. */
    public DoctorSchedule replaceWeek(Actor admin, UUID doctorId, int appointmentMinutes, List<WorkingHours> week) {
        if (appointmentMinutes < 5 || appointmentMinutes > 120) {
            throw new InvalidRequestException("INVALID_SCHEDULE", "Appointment length must be 5 to 120 minutes");
        }
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (WorkingHours day : week) {
            if (!seen.add(day.dayOfWeek())) {
                throw new InvalidRequestException("INVALID_SCHEDULE", day.dayOfWeek() + " is listed twice");
            }
        }
        // Locks the doctor row: bookings take the same lock, so none is checked against a
        // half-replaced week.
        DoctorProfile doctor = doctors.findByIdAndClinicIdForUpdate(doctorId, admin.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        doctor.setAppointmentMinutes(appointmentMinutes);

        jdbc.update("delete from doctor_working_hours where doctor_id = ? and clinic_id = ?", doctorId, admin.clinicId());
        for (WorkingHours day : week) {
            jdbc.update("""
                    insert into doctor_working_hours (clinic_id, doctor_id, day_of_week, start_time, end_time,
                                                      break_start, break_end)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, admin.clinicId(), doctorId, day.dayOfWeek().getValue(), day.start(),
                    day.end(), day.breakStart(), day.breakEnd());
        }
        return forBooking(doctor);
    }

    public TimeOff addTimeOff(Actor admin, UUID doctorId, LocalDateTime startsAt, LocalDateTime endsAt, String reason) {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            throw new InvalidRequestException("INVALID_TIME_OFF", "Time off needs a start before its end");
        }
        if (startsAt.plusDays(MAX_TIME_OFF_DAYS).isBefore(endsAt)) {
            throw new InvalidRequestException("INVALID_TIME_OFF", "Time off can be at most a year at once");
        }
        doctors.findByIdAndClinicIdForUpdate(doctorId, admin.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into doctor_time_off (id, clinic_id, doctor_id, starts_at, ends_at, reason)
                values (?, ?, ?, ?, ?, ?)
                """, id, admin.clinicId(), doctorId, startsAt, endsAt, note);
        return new TimeOff(id, doctorId, startsAt, endsAt, note);
    }

    /** Booked or confirmed appointments in [from, to): the ones to move after adding time off. */
    @Transactional(readOnly = true)
    public int bookedDuring(Actor actor, UUID doctorId, LocalDateTime from, LocalDateTime to) {
        Integer count = jdbc.queryForObject("""
                select count(*) from appointments
                where doctor_id = ? and clinic_id = ? and status in ('BOOKED', 'CONFIRMED')
                  and scheduled_at >= ? and scheduled_at < ?
                """, Integer.class, doctorId, actor.clinicId(), from, to);
        return count == null ? 0 : count;
    }

    public void removeTimeOff(Actor admin, UUID doctorId, UUID timeOffId) {
        int removed = jdbc.update("delete from doctor_time_off where id = ? and doctor_id = ? and clinic_id = ?",
                timeOffId, doctorId, admin.clinicId());
        if (removed == 0) throw new NotFoundException("Time off not found");
    }

    private DoctorProfile requireDoctor(UUID clinicId, UUID doctorId) {
        return doctors.findByIdAndClinicId(doctorId, clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
    }

    private DoctorSchedule load(DoctorProfile doctor, LocalDateTime timeOffEndsAfter, LocalDateTime timeOffStartsBefore) {
        List<WorkingHours> week = jdbc.query("""
                select day_of_week, start_time, end_time, break_start, break_end
                from doctor_working_hours where doctor_id = ? and clinic_id = ?
                order by day_of_week
                """, (rs, i) -> new WorkingHours(
                        DayOfWeek.of(rs.getInt("day_of_week")),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class),
                        rs.getObject("break_start", LocalTime.class),
                        rs.getObject("break_end", LocalTime.class)),
                doctor.getId(), doctor.getClinicId());
        List<TimeOff> timeOff = jdbc.query("""
                select id, starts_at, ends_at, reason from doctor_time_off
                where doctor_id = ? and clinic_id = ? and ends_at > ? and starts_at < ?
                order by starts_at
                """, (rs, i) -> new TimeOff(
                        rs.getObject("id", UUID.class), doctor.getId(),
                        rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class),
                        rs.getString("reason")),
                doctor.getId(), doctor.getClinicId(), timeOffEndsAfter,
                // LocalDateTime.MAX does not fit a PostgreSQL timestamp.
                timeOffStartsBefore.equals(LocalDateTime.MAX) ? LocalDateTime.of(9999, 1, 1, 0, 0) : timeOffStartsBefore);
        return new DoctorSchedule(doctor.getId(), doctor.getAppointmentMinutes(), week, timeOff);
    }
}

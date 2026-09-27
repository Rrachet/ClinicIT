package com.clinicit.analytics.api;

import com.clinicit.analytics.application.AnalyticsService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Operational analytics for the caller's clinic. The clinic always comes from the session,
 * never from the request. {@code date} is a clinic-local calendar day and defaults to the
 * clinic's today. Front desk may narrow to one doctor; a doctor always gets their own figures.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@AnyStaff
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/today")
    public DailySummaryResponse today(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.summary(actor, date, doctorId);
    }

    @GetMapping("/wait-times")
    public WaitTimesResponse waitTimes(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.waitTimes(actor, date, doctorId);
    }

    @GetMapping("/doctors")
    public DoctorAnalyticsResponse doctors(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return service.doctors(actor, date);
    }

    @GetMapping("/queue")
    public QueueAnalyticsResponse queue(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.queue(actor, date, doctorId);
    }

    @GetMapping("/no-shows")
    public NoShowResponse noShows(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.noShows(actor, from, to, doctorId);
    }
}

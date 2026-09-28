package com.clinicit.noshow.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AdminOnly;
import com.clinicit.identity.security.FrontDesk;
import com.clinicit.noshow.application.NoShowRiskService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only and advisory: nothing here changes an appointment, and no other endpoint looks
 * at these flags (docs/NO_SHOW_RISK.md).
 */
@RestController
@RequestMapping("/api/v1/no-show-risk")
public class NoShowRiskController {

    private final NoShowRiskService service;

    public NoShowRiskController(NoShowRiskService service) {
        this.service = service;
    }

    /** Flags for a day's booked and confirmed appointments (default: today), for reminder calls. */
    @GetMapping
    @FrontDesk
    public List<NoShowRiskResponse> forDay(
            Actor actor, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return service.forDay(actor, date);
    }

    /** How the flag would have done on past days (default: the 90 days before today). */
    @GetMapping("/evaluation")
    @AdminOnly
    public NoShowRiskEvaluation evaluation(
            Actor actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return service.evaluate(actor, from, to);
    }
}

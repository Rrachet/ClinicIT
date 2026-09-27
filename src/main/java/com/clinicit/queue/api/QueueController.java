package com.clinicit.queue.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.queue.application.QueueService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operations on a doctor's queue as a whole. Doctors may omit doctorId to mean themselves. */
@RestController
@RequestMapping("/api/v1/queues")
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    /** "Today" is resolved server-side in the clinic's timezone. */
    @GetMapping("/today")
    @AnyStaff
    public QueueBoardResponse today(Actor actor, @RequestParam(required = false) UUID doctorId) {
        return service.todayForDoctor(actor, doctorId);
    }

    @PostMapping("/call-next")
    @AnyStaff
    public QueueEntryResponse callNext(Actor actor, @RequestBody(required = false) CallNextRequest request) {
        return service.callNext(actor, request == null ? null : request.doctorId());
    }
}

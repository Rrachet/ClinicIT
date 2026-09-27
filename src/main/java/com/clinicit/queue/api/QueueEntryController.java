package com.clinicit.queue.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.identity.security.FrontDesk;
import com.clinicit.queue.application.QueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A single patient's place in the queue. Checking in and front-desk corrections are
 * front-desk only; running the consultation is open to the patient's doctor too.
 */
@RestController
@RequestMapping("/api/v1/queue-entries")
public class QueueEntryController {

    private final QueueService service;

    public QueueEntryController(QueueService service) {
        this.service = service;
    }

    /** Joining the queue creates a queue entry (and issues its token). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @FrontDesk
    public QueueEntryResponse join(Actor actor, @Valid @RequestBody JoinQueueRequest request) {
        return service.join(actor, request.appointmentId());
    }

    @GetMapping("/{id}")
    @AnyStaff
    public QueueEntryResponse get(Actor actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PostMapping("/{id}/start")
    @AnyStaff
    public QueueEntryResponse start(Actor actor, @PathVariable UUID id) {
        return service.startConsultation(actor, id);
    }

    @PostMapping("/{id}/complete")
    @AnyStaff
    public QueueEntryResponse complete(Actor actor, @PathVariable UUID id) {
        return service.complete(actor, id);
    }

    @PostMapping("/{id}/skip")
    @AnyStaff
    public QueueEntryResponse skip(Actor actor, @PathVariable UUID id) {
        return service.skip(actor, id);
    }

    @PostMapping("/{id}/requeue")
    @FrontDesk
    public QueueEntryResponse requeue(Actor actor, @PathVariable UUID id) {
        return service.requeue(actor, id);
    }

    @PostMapping("/{id}/no-show")
    @FrontDesk
    public QueueEntryResponse noShow(Actor actor, @PathVariable UUID id) {
        return service.markNoShow(actor, id);
    }
}

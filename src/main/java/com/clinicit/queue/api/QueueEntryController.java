package com.clinicit.queue.api;

import com.clinicit.queue.application.QueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** A single patient's place in the queue. */
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
    public QueueEntryResponse join(@Valid @RequestBody JoinQueueRequest request) {
        return service.join(request.appointmentId());
    }

    @GetMapping("/{id}")
    public QueueEntryResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/start")
    public QueueEntryResponse start(@PathVariable UUID id) {
        return service.startConsultation(id);
    }

    @PostMapping("/{id}/complete")
    public QueueEntryResponse complete(@PathVariable UUID id) {
        return service.complete(id);
    }

    @PostMapping("/{id}/skip")
    public QueueEntryResponse skip(@PathVariable UUID id) {
        return service.skip(id);
    }

    @PostMapping("/{id}/requeue")
    public QueueEntryResponse requeue(@PathVariable UUID id) {
        return service.requeue(id);
    }

    @PostMapping("/{id}/no-show")
    public QueueEntryResponse noShow(@PathVariable UUID id) {
        return service.markNoShow(id);
    }
}

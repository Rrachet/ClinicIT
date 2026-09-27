package com.clinicit.queue.api;

import com.clinicit.queue.application.QueueService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operations on a doctor's queue as a whole. */
@RestController
@RequestMapping("/api/v1/queues")
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    /** "Today" is resolved server-side in the clinic's timezone. */
    @GetMapping("/today")
    public QueueBoardResponse today(@RequestParam UUID doctorId) {
        return service.todayForDoctor(doctorId);
    }

    @PostMapping("/call-next")
    public QueueEntryResponse callNext(@Valid @RequestBody CallNextRequest request) {
        return service.callNext(request.doctorId());
    }
}

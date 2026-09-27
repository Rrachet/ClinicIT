package com.clinicit.notification.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.FrontDesk;
import com.clinicit.notification.application.StaffNotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final StaffNotificationService service;

    public NotificationController(StaffNotificationService service) {
        this.service = service;
    }

    /** Messages for one appointment (e.g. "was the queue link delivered?"). */
    @GetMapping
    @FrontDesk
    public List<NotificationResponse> forAppointment(Actor actor, @RequestParam UUID appointmentId) {
        return service.forAppointment(actor, appointmentId);
    }

    @PostMapping("/{id}/retry")
    @FrontDesk
    public NotificationResponse retry(Actor actor, @PathVariable UUID id) {
        return service.retry(actor, id);
    }
}

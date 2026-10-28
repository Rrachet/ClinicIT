package com.clinicit.notification.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.FrontDesk;
import com.clinicit.notification.application.NotificationProperties;
import com.clinicit.notification.application.StaffNotificationService;
import com.clinicit.notification.domain.NotificationChannel;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final StaffNotificationService service;
    private final NotificationProperties properties;

    public NotificationController(StaffNotificationService service, NotificationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * Whether patients are messaged automatically. When off, reception hands over the queue
     * link themselves, so the screen must not claim it is being sent.
     */
    @GetMapping("/settings")
    @FrontDesk
    public MessagingSettings settings() {
        return new MessagingSettings(properties.enabled(), properties.channel());
    }

    public record MessagingSettings(boolean patientMessaging, NotificationChannel channel) {}

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

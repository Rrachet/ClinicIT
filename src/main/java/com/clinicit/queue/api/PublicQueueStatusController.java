package com.clinicit.queue.api;

import com.clinicit.common.domain.NotFoundException;
import com.clinicit.queue.application.PublicQueueStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no login) patient status, for the link or QR code the front desk gives a patient.
 * Deliberately separate from the staff WebSocket topics: patients poll this endpoint.
 */
@RestController
@RequestMapping("/api/v1/public/queue-status")
public class PublicQueueStatusController {

    private final PublicQueueStatusService service;

    public PublicQueueStatusController(PublicQueueStatusService service) {
        this.service = service;
    }

    @GetMapping("/{code}")
    public ResponseEntity<PublicQueueStatusResponse> status(@PathVariable String code) {
        if (!code.matches("[A-Za-z0-9_-]{16,32}")) {
            throw new NotFoundException("Queue status not found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.status(code));
    }
}

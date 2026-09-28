package com.clinicit.notification.application;

import com.clinicit.appointment.domain.AppointmentConfirmed;
import com.clinicit.notification.domain.NotificationChannel;
import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.application.QueueEventRecorder;
import com.clinicit.queue.domain.QueueEventType;
import com.clinicit.queue.domain.QueueStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class NotificationUnitTest {

    @Test
    void aFailureWhilePlanningNeverPropagatesIntoTheClinicOperation() {
        NotificationService broken = mock(NotificationService.class);
        doThrow(new IllegalStateException("template bug")).when(broken).queueEventOccurred(any());
        doThrow(new IllegalStateException("template bug")).when(broken).appointmentConfirmed(any());
        NotificationTriggers triggers = new NotificationTriggers(broken);

        QueueEventMessage event = new QueueEventMessage(UUID.randomUUID(), 1, QueueEventType.PATIENT_CALLED, Instant.now(),
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), UUID.randomUUID(), UUID.randomUUID(), 7,
                QueueStatus.CALLED, QueueStatus.WAITING, 1);
        assertThatCode(() -> triggers.onQueueEvent(new QueueEventRecorder.Recorded(event))).doesNotThrowAnyException();
        assertThatCode(() -> triggers.onAppointmentConfirmed(new AppointmentConfirmed(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    @Test
    void providersAreChosenByChannel() {
        NotificationProvider sms = provider("sms", NotificationChannel.SMS);
        NotificationProvider whatsapp = provider("whatsapp", NotificationChannel.WHATSAPP);
        NotificationProviders registry = new NotificationProviders(List.of(sms, whatsapp), properties(5));

        assertThat(registry.forChannel(NotificationChannel.WHATSAPP)).contains(whatsapp);
        assertThat(registry.forChannel(NotificationChannel.SMS)).contains(sms);
        assertThat(registry.forChannel(NotificationChannel.EMAIL)).isEmpty();
    }

    @Test
    void retryDelayDoublesAndIsCapped() {
        NotificationProperties properties = properties(10);
        NotificationDispatcher dispatcher = new NotificationDispatcher(null, null, properties, null, null,
                new NotificationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null));
        assertThat(dispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(dispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(dispatcher.backoff(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(dispatcher.backoff(9)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void templatesContainOnlyClinicTokenTimeAndLink() {
        assertThat(NotificationTemplates.joinedQueue("  City   Clinic ", 24, "https://app.example/status/abc"))
                .isEqualTo("City Clinic: you're checked in. Your token is #24. Follow your place in the queue: https://app.example/status/abc");
        assertThat(NotificationTemplates.appointmentConfirmed("City Clinic", LocalDateTime.of(2026, 3, 10, 16, 0)))
                .isEqualTo("City Clinic: your appointment on 10 Mar at 16:00 is confirmed.");
        assertThat(NotificationTemplates.called("X".repeat(500), 1)).hasSizeLessThan(120);
    }

    @Test
    void statusLinksUseTheConfiguredPublicUrl() {
        NotificationProperties properties = new NotificationProperties(null, null, "https://app.clinicit.example/", null, null, null, null, null, null);
        assertThat(properties.statusLink("abc_DEF-123")).isEqualTo("https://app.clinicit.example/status/abc_DEF-123");
        assertThatThrownBy(() -> new NotificationProperties(null, null, "javascript:alert(1)", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outboundMessagesNeverPrintRecipientOrBody() {
        OutboundMessage message = new OutboundMessage("key-1", NotificationChannel.SMS, "+91 98765 43210", "token #3 https://x/status/secret");
        assertThat(message.toString()).doesNotContain("98765").doesNotContain("secret");
    }

    private static NotificationProperties properties(int maxAttempts) {
        return new NotificationProperties(true, NotificationChannel.SMS, "http://localhost:3000", 1, maxAttempts, null, null, false, null);
    }

    private static NotificationProvider provider(String name, NotificationChannel channel) {
        return new NotificationProvider() {
            public String name() { return name; }
            public boolean supports(NotificationChannel c) { return c == channel; }
            public String send(OutboundMessage message) { return name; }
        };
    }
}

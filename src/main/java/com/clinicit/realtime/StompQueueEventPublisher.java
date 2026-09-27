package com.clinicit.realtime;

import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.application.QueueEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Fans each queue event out to the clinic-wide topic and to the doctor's topic. */
@Component
public class StompQueueEventPublisher implements QueueEventPublisher {

    private final SimpMessagingTemplate broker;

    public StompQueueEventPublisher(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @Override
    public void publish(QueueEventMessage event) {
        broker.convertAndSend(QueueTopics.clinic(event.clinicId()), event);
        broker.convertAndSend(QueueTopics.doctor(event.clinicId(), event.doctorId()), event);
    }
}

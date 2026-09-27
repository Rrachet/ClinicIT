package com.clinicit.queue.application;

import com.clinicit.queue.api.QueueEventMessage;

/**
 * Port to whatever delivers queue events to clients (today: STOMP over WebSocket in the
 * realtime module). The queue module does not know about WebSockets.
 * Implementations may throw; the outbox will retry.
 */
public interface QueueEventPublisher {

    void publish(QueueEventMessage event);
}

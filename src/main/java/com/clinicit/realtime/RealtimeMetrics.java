package com.clinicit.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * {@code clinicit.websocket.connections{state=open|authenticated}}: WebSocket connections on
 * this instance (the simple broker keeps them in memory, so the figure is per instance).
 */
@Component
class RealtimeMetrics implements MeterBinder {

    private final RealtimeConnections connections;

    RealtimeMetrics(RealtimeConnections connections) {
        this.connections = connections;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("clinicit.websocket.connections", connections, RealtimeConnections::openCount)
                .tag("state", "open").description("Open WebSocket connections").register(registry);
        Gauge.builder("clinicit.websocket.connections", connections, RealtimeConnections::authenticatedCount)
                .tag("state", "authenticated").description("Connections with an authenticated STOMP session")
                .register(registry);
    }
}

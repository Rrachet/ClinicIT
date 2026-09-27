package com.clinicit.realtime;

import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.identity.application.AuthService;
import com.clinicit.identity.domain.Actor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * All WebSocket security, applied to every frame a client sends. Deny by default.
 *
 * <ul>
 *   <li>CONNECT must carry {@code Authorization: Bearer <token>} (a STOMP header: browsers
 *       cannot set headers on the WebSocket handshake). The connection is bound to that
 *       staff member.</li>
 *   <li>SUBSCRIBE is allowed only to {@link QueueTopics} of the caller's own clinic. The
 *       clinicId in the destination is compared with the authenticated clinic, never
 *       trusted. Doctors may only subscribe to their own doctor topic. The token is
 *       re-validated on every subscription.</li>
 *   <li>SEND (and ACK/NACK/transactions) is always rejected: the WebSocket only pushes
 *       notifications; queue operations go through the REST API.</li>
 * </ul>
 * A rejected frame makes Spring send a STOMP ERROR frame and close the connection.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final AuthService auth;
    private final DoctorProfileRepository doctors;
    private final RealtimeConnections connections;

    public StompSecurityInterceptor(AuthService auth, DoctorProfileRepository doctors, RealtimeConnections connections) {
        this.auth = auth;
        this.doctors = doctors;
        this.connections = connections;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeat
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> connect(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case UNSUBSCRIBE, DISCONNECT -> {
                // always allowed
            }
            default -> throw denied("Clients cannot send " + command + " frames; use the REST API");
        }
        return message;
    }

    private void connect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw denied("Authentication required");
        }
        String token = header.substring(BEARER.length()).trim();
        Actor actor = auth.authenticate(token).orElseThrow(() -> denied("Authentication required"));

        StompPrincipal principal = new StompPrincipal(actor, auth.tokenHash(token));
        accessor.setUser(principal);
        connections.bind(accessor.getSessionId(), principal);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw denied("Authentication required");
        }
        // Still valid? (revoked / expired / disabled since CONNECT)
        Actor actor = auth.authenticateTokenHash(principal.tokenHash())
                .orElseThrow(() -> denied("Authentication required"));

        QueueTopics.Target target = QueueTopics.parse(accessor.getDestination())
                .orElseThrow(() -> denied("Unknown destination"));

        if (!target.clinicId().equals(actor.clinicId())) {
            throw denied("Access denied");
        }
        if (target.doctorId() == null) {
            if (actor.isDoctor()) {
                throw denied("Doctors subscribe to their own queue only");
            }
            return;
        }
        if (actor.isDoctor() && !target.doctorId().equals(actor.doctorProfileId())) {
            throw denied("Doctors subscribe to their own queue only");
        }
        if (doctors.findByIdAndClinicId(target.doctorId(), actor.clinicId()).isEmpty()) {
            throw denied("Access denied");
        }
    }

    private static MessageDeliveryException denied(String reason) {
        return new MessageDeliveryException(reason);
    }
}

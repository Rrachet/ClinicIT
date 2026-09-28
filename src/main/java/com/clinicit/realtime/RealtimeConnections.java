package com.clinicit.realtime;

import com.clinicit.identity.application.AuthService;
import com.clinicit.identity.domain.SessionsRevoked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open WebSocket connections and who they belong to. A connection authenticated once at
 * CONNECT would otherwise outlive its token, so connections are closed:
 * <ul>
 *   <li>immediately after logout, user disable or password change ({@link SessionsRevoked}), and</li>
 *   <li>by a periodic sweep once their token expires.</li>
 * </ul>
 */
@Component
public class RealtimeConnections {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConnections.class);
    static final CloseStatus SESSION_ENDED = CloseStatus.POLICY_VIOLATION.withReason("Session ended");

    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, StompPrincipal> owners = new ConcurrentHashMap<>();
    private final AuthService auth;

    public RealtimeConnections(AuthService auth) {
        this.auth = auth;
    }

    void opened(WebSocketSession session) {
        sockets.put(session.getId(), session);
    }

    void closed(WebSocketSession session) {
        sockets.remove(session.getId());
        owners.remove(session.getId());
    }

    void bind(String sessionId, StompPrincipal principal) {
        if (sessionId != null) {
            owners.put(sessionId, principal);
        }
    }

    public int openCount() {
        return sockets.size();
    }

    /** Connections that completed an authenticated STOMP CONNECT. */
    public int authenticatedCount() {
        return owners.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionsRevoked(SessionsRevoked revoked) {
        owners.forEach((sessionId, owner) -> {
            if (revoked.covers(owner.actor().userId(), owner.tokenHash())) {
                close(sessionId);
            }
        });
    }

    @Scheduled(
            initialDelayString = "${clinicit.realtime.revalidate-interval:PT1M}",
            fixedDelayString = "${clinicit.realtime.revalidate-interval:PT1M}")
    public void closeExpired() {
        owners.forEach((sessionId, owner) -> {
            if (auth.authenticateTokenHash(owner.tokenHash()).isEmpty()) {
                close(sessionId);
            }
        });
    }

    private void close(String sessionId) {
        owners.remove(sessionId);
        WebSocketSession socket = sockets.remove(sessionId);
        if (socket != null && socket.isOpen()) {
            try {
                socket.close(SESSION_ENDED);
            } catch (IOException e) {
                log.debug("Closing WebSocket {} failed: {}", sessionId, e.toString());
            }
        }
    }
}

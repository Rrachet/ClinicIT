package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** All NotificationProvider beans; picks the one for a channel. */
@Component
public class NotificationProviders {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviders.class);

    private final List<NotificationProvider> providers;

    public NotificationProviders(List<NotificationProvider> providers, NotificationProperties properties) {
        this.providers = List.copyOf(providers);
        if (properties.enabled() && forChannel(properties.channel()).isEmpty()) {
            log.error("Notifications are enabled for {} but no provider supports it; messages will fail",
                    properties.channel());
        }
    }

    public Optional<NotificationProvider> forChannel(NotificationChannel channel) {
        return providers.stream().filter(p -> p.supports(channel)).findFirst();
    }
}

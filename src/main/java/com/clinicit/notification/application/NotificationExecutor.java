package com.clinicit.notification.application;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Small dedicated pool for the delivery fast path, so a slow vendor can't hold up request
 * threads. Deliberately not an {@code Executor} bean: that would replace Spring Boot's
 * default application executor. Runs inline when async delivery is off (tests).
 * If the pool is ever saturated, rows simply stay PENDING and the retry poller sends them.
 */
@Component
public class NotificationExecutor implements DisposableBean {

    private final ThreadPoolTaskExecutor pool;

    public NotificationExecutor(NotificationProperties properties) {
        if (properties.asyncDelivery()) {
            pool = new ThreadPoolTaskExecutor();
            pool.setThreadNamePrefix("notify-");
            pool.setCorePoolSize(2);
            pool.setMaxPoolSize(4);
            pool.setQueueCapacity(1000);
            pool.setRejectedExecutionHandler((task, executor) -> { });
            pool.setWaitForTasksToCompleteOnShutdown(true);
            pool.setAwaitTerminationSeconds(10);
            pool.initialize();
        } else {
            pool = null;
        }
    }

    public void submit(Runnable task) {
        if (pool == null) task.run();
        else pool.execute(task);
    }

    @Override
    public void destroy() {
        if (pool != null) pool.shutdown();
    }
}

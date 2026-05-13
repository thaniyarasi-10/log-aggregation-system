package com.kovanlabs.logcontroller.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated async executor for the email notification pipeline.
 *
 * <p>Using a named executor ({@code "notificationExecutor"}) rather than the default
 * Spring async executor ensures that email sending never competes with other async
 * work (e.g. WebSocket broadcasts) for threads.
 *
 * <h3>Sizing rationale</h3>
 * <ul>
 *   <li>Core pool = 2 — enough for normal alert bursts without idle thread overhead.</li>
 *   <li>Max pool  = 10 — handles spikes (e.g. mass alert storm) without unbounded growth.</li>
 *   <li>Queue capacity = 500 — absorbs bursts; alerts are low-latency-tolerant.</li>
 *   <li>Keep-alive = 60 s — extra threads are released quickly after a burst.</li>
 * </ul>
 *
 * <h3>Horizontal scaling / duplicate prevention</h3>
 * In a multi-instance deployment each instance will independently receive the
 * {@link com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent} (because it is
 * an in-process Spring event, not a Kafka message). To prevent duplicate emails:
 * <ol>
 *   <li>The {@code AlertNotificationLogRepository} deduplication query acts as a
 *       distributed idempotency check — the first instance to write a {@code SENT} row
 *       wins; subsequent instances will see the row and skip.</li>
 *   <li>For stronger guarantees, replace the in-process event with a Kafka topic
 *       (single consumer group) — see {@code FUTURE_EXTENSIBILITY.md}.</li>
 * </ol>
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig implements AsyncConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationAsyncConfig.class);

    @Value("${notification.async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${notification.async.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${notification.async.queue-capacity:500}")
    private int queueCapacity;

    @Value("${notification.async.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * Named executor used by {@code @Async("notificationExecutor")} in the notification listener.
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("notif-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        LOGGER.info("Notification thread pool initialized — core={} max={} queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * Global handler for uncaught exceptions thrown by {@code @Async} methods.
     * Logs the error so it is never silently swallowed.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                LOGGER.error("Uncaught async exception in method '{}': {}", method.getName(), ex.getMessage(), ex);
    }
}

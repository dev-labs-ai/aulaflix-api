package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * The drainer, the webhook worker, the expiry job and reconciliation run on their own in the API, each its own interval
 * after the end of its run before; never in admin mode, nor where scheduling is turned off. The jobs stand in for
 * themselves: what is checked is only when each is called.
 */
class ScheduledJobsTest {

    private static final Duration STARTUP_RUN = Duration.ofSeconds(10);

    private final EmailOutbox outbox = mock(EmailOutbox.class);

    private final WebhookWorker webhookWorker = mock(WebhookWorker.class);

    private final OrderExpiry expiry = mock(OrderExpiry.class);

    private final OrderReconciliation reconciliation = mock(OrderReconciliation.class);

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ScheduledJobs.class, PropertiesEnabled.class)
            .withBean(EmailOutbox.class, () -> outbox)
            .withBean(WebhookWorker.class, () -> webhookWorker)
            .withBean(OrderExpiry.class, () -> expiry)
            .withBean(OrderReconciliation.class, () -> reconciliation)
            .withPropertyValues("aulaflix.outbox.drain-interval=1h", "aulaflix.asaas.webhook-interval=2h",
                    "aulaflix.asaas.expiry-interval=3h", "aulaflix.asaas.reconciliation-interval=4h",
                    "aulaflix.asaas.api-key=key-of-the-test",
                    "aulaflix.asaas.webhook-token=webhook-token-of-the-test-0123456");

    @Test
    void drainsTheOutboxWithAFixedDelay() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            runTheJobEvery(context.getBeansOfType(ScheduledTaskHolder.class).values(), Duration.ofHours(1));
            verify(outbox).drain();
            verifyNoInteractions(webhookWorker, expiry, reconciliation);
        });
    }

    @Test
    void processesTheWebhookEventsWithAFixedDelay() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            runTheJobEvery(context.getBeansOfType(ScheduledTaskHolder.class).values(), Duration.ofHours(2));
            verify(webhookWorker).processPending();
            verifyNoInteractions(outbox, expiry, reconciliation);
        });
    }

    @Test
    void expiresOrdersWithAFixedDelay() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            runTheJobEvery(context.getBeansOfType(ScheduledTaskHolder.class).values(), Duration.ofHours(3));
            verify(expiry).expireDue();
            verifyNoInteractions(outbox, webhookWorker, reconciliation);
        });
    }

    @Test
    void reconcilesOrdersWithAFixedDelay() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            runTheJobEvery(context.getBeansOfType(ScheduledTaskHolder.class).values(), Duration.ofHours(4));
            verify(reconciliation).reconcile();
            verifyNoInteractions(outbox, webhookWorker, expiry);
        });
    }

    @Test
    void schedulesNothingInAdminMode() {
        application.withPropertyValues("spring.profiles.active=" + AdminModeConfiguration.PROFILE)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledJobs.class));
    }

    @Test
    void schedulesNothingWhenSchedulingIsTurnedOff() {
        application.withPropertyValues("aulaflix.scheduling.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledJobs.class));
    }

    /**
     * Runs, once, the only job scheduled with that fixed delay. Each job first runs as the context starts; once all
     * have, the next runs are hours away, so every call after that is this one's.
     */
    private void runTheJobEvery(Collection<ScheduledTaskHolder> holders, Duration interval) {
        verify(outbox, timeout(STARTUP_RUN.toMillis())).drain();
        verify(webhookWorker, timeout(STARTUP_RUN.toMillis())).processPending();
        verify(expiry, timeout(STARTUP_RUN.toMillis())).expireDue();
        verify(reconciliation, timeout(STARTUP_RUN.toMillis())).reconcile();
        clearInvocations(outbox, webhookWorker, expiry, reconciliation);
        List<ScheduledTask> tasks = holders.stream().flatMap(holder -> holder.getScheduledTasks().stream()).toList();
        assertThat(tasks).hasSize(4).filteredOn(scheduled -> scheduled.getTask() instanceof FixedDelayTask task
                        && task.getIntervalDuration().equals(interval))
                .singleElement()
                .satisfies(scheduled -> scheduled.getTask().getRunnable().run());
    }

    @EnableConfigurationProperties({OutboxProperties.class, AsaasProperties.class})
    static class PropertiesEnabled {
    }
}

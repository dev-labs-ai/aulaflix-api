package com.devlabs.aulaflix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * Every job the API runs on its own, each with a fixed delay, so that a run never overlaps the one before. None runs
 * in admin mode, next to the live API, nor where {@code aulaflix.scheduling.enabled} is false, as in the tests, which
 * call each job once, synchronously.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!" + AdminModeConfiguration.PROFILE)
@ConditionalOnBooleanProperty(name = "aulaflix.scheduling.enabled", matchIfMissing = true)
public class ScheduledJobs implements SchedulingConfigurer {

    private final EmailOutbox outbox;
    private final OutboxProperties outboxProperties;
    private final WebhookWorker webhookWorker;
    private final AsaasProperties asaasProperties;

    public ScheduledJobs(EmailOutbox outbox, OutboxProperties outboxProperties, WebhookWorker webhookWorker,
                         AsaasProperties asaasProperties) {
        this.outbox = outbox;
        this.outboxProperties = outboxProperties;
        this.webhookWorker = webhookWorker;
        this.asaasProperties = asaasProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar jobs) {
        jobs.addFixedDelayTask(outbox::drain, outboxProperties.drainInterval());
        jobs.addFixedDelayTask(webhookWorker::processPending, asaasProperties.webhookInterval());
    }
}

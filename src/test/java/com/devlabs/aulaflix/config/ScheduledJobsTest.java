package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

/**
 * The drainer runs on its own in the API, a drain-interval after the end of the drain before; never in admin mode,
 * nor where scheduling is turned off. The outbox stands in for itself: what is checked is only when it is called.
 */
class ScheduledJobsTest {

    private final EmailOutbox outbox = mock(EmailOutbox.class);

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ScheduledJobs.class, OutboxPropertiesEnabled.class)
            .withBean(EmailOutbox.class, () -> outbox)
            .withPropertyValues("aulaflix.outbox.drain-interval=1h");

    @Test
    void drainsTheOutboxWithAFixedDelay() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(scheduledTasks(context.getBeansOfType(ScheduledTaskHolder.class).values()))
                    .singleElement().satisfies(scheduled -> {
                        assertThat(scheduled.getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
                                task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1)));
                        scheduled.getTask().getRunnable().run();
                    });
            verify(outbox, atLeastOnce()).drain();
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

    private static List<ScheduledTask> scheduledTasks(Collection<ScheduledTaskHolder> holders) {
        return holders.stream().flatMap(holder -> holder.getScheduledTasks().stream()).toList();
    }

    @EnableConfigurationProperties(OutboxProperties.class)
    static class OutboxPropertiesEnabled {
    }
}

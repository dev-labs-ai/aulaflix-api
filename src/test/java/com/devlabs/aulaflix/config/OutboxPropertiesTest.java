package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.devlabs.aulaflix.service.OutboxSettings;

/** The outbox's defaults are the local ones; a From that is not one address, or a rate of nothing, stops the API. */
class OutboxPropertiesTest {

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OutboxConfiguration.class);

    @Test
    void sendsFromAulaFlixTenEmailsASecondByDefault() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            OutboxSettings settings = context.getBean(OutboxSettings.class);
            assertThat(settings.from().getPersonal()).isEqualTo("AulaFlix");
            assertThat(settings.from().getAddress()).isEqualTo("contato@aulaflix.com.br");
            assertThat(settings.emailsPerSpan()).isEqualTo(10);
            assertThat(settings.span()).isEqualTo(Duration.ofSeconds(1));
            assertThat(context.getBean(OutboxProperties.class).drainInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(context.getBean(WebProperties.class).baseUrl()).isEqualTo(URI.create("http://localhost:3001"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.outbox.from=AulaFlix", "aulaflix.outbox.from=a@b.com, c@d.com",
            "aulaflix.outbox.from=", "aulaflix.outbox.send-rate.emails=0", "aulaflix.outbox.send-rate.per=0s",
            "aulaflix.outbox.drain-interval=0s", "aulaflix.web.base-url="})
    void refusesToStartWithAnOutboxThatCannotSend(String property) {
        application.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}

package com.devlabs.aulaflix.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.devlabs.aulaflix.service.EmailTemplates;
import com.devlabs.aulaflix.service.OutboxSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxProperties.class, WebProperties.class})
public class OutboxConfiguration {

    /** A From that is not one address stops the API from starting, rather than every email from going out. */
    @Bean
    OutboxSettings outboxSettings(OutboxProperties outbox) {
        try {
            return new OutboxSettings(new InternetAddress(outbox.from(), true), outbox.sendRate().emails(),
                    outbox.sendRate().per());
        } catch (AddressException invalid) {
            throw new IllegalStateException("aulaflix.outbox.from is not an email address", invalid);
        }
    }

    @Bean
    EmailTemplates emailTemplates(WebProperties web) {
        return new EmailTemplates(web.baseUrl());
    }
}

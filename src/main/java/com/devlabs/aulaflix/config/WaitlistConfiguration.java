package com.devlabs.aulaflix.config;

import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.devlabs.aulaflix.service.UnsubscribeTokens;

/** The Waitlist's launch emails, whose unsubscribe links carry a token sealed under the secret key. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WaitlistProperties.class)
public class WaitlistConfiguration {

    @Bean
    UnsubscribeTokens unsubscribeTokens(WaitlistProperties waitlist) {
        return new UnsubscribeTokens(Base64.getDecoder().decode(waitlist.unsubscribeKey()));
    }
}

package com.devlabs.aulaflix.config;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.devlabs.aulaflix.service.CodeHmac;
import com.devlabs.aulaflix.service.PasswordResetSettings;

/** The 6-digit codes that reset or change a password, and how the reset answers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CodeProperties.class, PasswordResetProperties.class})
public class CodeConfiguration {

    @Bean
    CodeHmac codeHmac(CodeProperties codes) {
        return new CodeHmac(codes.hmacKey().getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    PasswordResetSettings passwordResetSettings(PasswordResetProperties reset) {
        return new PasswordResetSettings(reset.codeRequestTime());
    }
}

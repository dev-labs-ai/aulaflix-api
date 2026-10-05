package com.devlabs.aulaflix.config;

import java.net.http.HttpClient;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import com.devlabs.aulaflix.service.HibpPasswordChecker;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HibpProperties.class)
public class PasswordConfiguration {

    private static final String BCRYPT = "bcrypt";
    private static final int BCRYPT_COST = 10;

    /** Delegating, so that a later move to another algorithm still verifies the hashes stored before it. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(BCRYPT_COST)));
    }

    /**
     * Every new password, a Student's or an Admin's, must not be one HIBP has seen in a breach. The JDK's client, with
     * both timeouts set: Spring's defaults wait forever.
     */
    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker(HibpProperties hibp) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(hibp.timeout()).build());
        requests.setReadTimeout(hibp.timeout());
        return new HibpPasswordChecker(RestClient.builder()
                .baseUrl(hibp.baseUrl().toString())
                .requestFactory(requests)
                .build());
    }
}

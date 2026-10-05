package com.devlabs.aulaflix.config;

import java.net.URI;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Where the web is, {@code aulaflix.web.base-url}: {@code webBase}, which every link in an email starts with. */
@Validated
@ConfigurationProperties("aulaflix.web")
public record WebProperties(
        @NotNull
        URI baseUrl) {
}

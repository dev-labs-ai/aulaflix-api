package com.devlabs.aulaflix;

import org.testcontainers.containers.GenericContainer;

/** Mailpit, the SMTP server the API sends to locally: it keeps every email and shows them through its REST API. */
public class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final String IMAGE = "axllent/mailpit:v1.31.4";
    private static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;

    public MailpitContainer() {
        super(IMAGE);
        withExposedPorts(SMTP_PORT, API_PORT);
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    public String apiBaseUrl() {
        return "http://%s:%d".formatted(getHost(), getMappedPort(API_PORT));
    }
}

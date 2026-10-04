package com.devlabs.aulaflix.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminModeTest {

    @Test
    void isRequestedOnlyByAdminAsTheFirstArgument() {
        assertThat(AdminMode.isRequested(new String[] {"admin", "create"})).isTrue();
        assertThat(AdminMode.isRequested(new String[] {"admin"})).isTrue();

        assertThat(AdminMode.isRequested(new String[] {})).isFalse();
        assertThat(AdminMode.isRequested(new String[] {"--server.port=8081"})).isFalse();
        assertThat(AdminMode.isRequested(new String[] {"--debug", "admin"})).isFalse();
        assertThat(AdminMode.isRequested(new String[] {"Admin"})).isFalse();
    }
}

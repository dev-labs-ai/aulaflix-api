package com.devlabs.aulaflix.service;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.devlabs.aulaflix.Hibp;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;

/**
 * The breached-password check guards the quality of a password, not access: when HIBP cannot answer, the password is
 * taken unchecked, and a WARN says why, without the password or the URL that holds the start of its hash.
 */
@ExtendWith(OutputCaptureExtension.class)
class BreachedPasswordCheckTest extends IntegrationTest {

    @Autowired
    private Hibp hibp;

    private StudentApi students;

    @BeforeEach
    void useTheStudentApi() {
        students = new StudentApi(mvc);
    }

    @Test
    void takesAPasswordWhoseRangeListsOnlyOthersEvenOneDigitAway() {
        String password = "almost breached " + UUID.randomUUID();
        hibp.breachANeighbourOf(password);

        assertThat(students.signUp("Bia", newEmail(), password)).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void takesThePasswordUncheckedWithAWarningWhenHibpCannotAnswer(String description,
                                                                   ResponseDefinitionBuilder failure,
                                                                   String reason, CapturedOutput output) {
        String password = "unchecked " + UUID.randomUUID();
        hibp.answer(password, failure);
        int before = output.getAll().length();

        assertThat(students.signUp("Bia", newEmail(), password)).hasStatus(HttpStatus.CREATED);

        List<String> warnings = output.getAll().substring(before).lines()
                .filter(line -> line.contains("WARN"))
                .toList();
        assertThat(warnings).singleElement().asString()
                .contains("breached-password check", reason)
                .doesNotContain(password, "/range/");
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("connection reset", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        "Connection reset"),
                Arguments.of("503", serviceUnavailable(), "HTTP 503"),
                Arguments.of("answer after the timeout",
                        aResponse().withFixedDelay((int) Hibp.TIMEOUT.multipliedBy(2).toMillis()),
                        "HttpTimeoutException"));
    }
}

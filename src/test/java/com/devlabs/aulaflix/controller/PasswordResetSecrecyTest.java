package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.EmailedCodes;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.EmailOutbox;

/**
 * What a reset must keep secret, beyond its answers: the code, which the table holds only as an HMAC under the
 * server's key, and whether the email has an Account, which no answer's timing tells.
 */
class PasswordResetSecrecyTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String CODE_SUBJECT = "Seu código para redefinir a senha da AulaFlix";

    /** How far apart the two kinds of answer may be, as a share of the longer one. */
    private static final double SIMILAR = 0.1;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storesTheCodeOnlyAsItsHmacUnderTheSecretKey() throws GeneralSecurityException {
        new StoredOutboxEmails(jdbc).discardPending();
        String email = newEmail();
        StudentApi students = new StudentApi(mvc);
        students.signedUp(email, PASSWORD);
        long accountId = new StoredAccounts(jdbc).find(email).orElseThrow().id();

        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        String code = EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT).getFirst();

        List<String> rows = jdbc.queryForList(
                "select row_to_json(c)::text from verification_codes c where account_id = ?", String.class,
                accountId);
        assertThat(rows).singleElement().asString().doesNotContain(code);
        assertThat(jdbc.queryForObject("select code_hmac from verification_codes where account_id = ?",
                String.class, accountId))
                .isEqualTo(hmacSha256Hex(CODES_HMAC_KEY, accountId + ":RESET:" + code));
    }

    @Test
    void answersAStudentsEmailInTheTimeOfAnUnknownOne() {
        List<String> studentEmails = IntStream.range(0, 15).mapToObj(student -> newEmail()).toList();
        studentEmails.forEach(email -> new StudentApi(mvc).signedUp(email, PASSWORD));
        for (int warmUp = 0; warmUp < 5; warmUp++) {
            new StudentApi(mvc).requestResetCode(newEmail());
        }

        List<Long> forStudents = new ArrayList<>();
        List<Long> forUnknown = new ArrayList<>();
        for (String email : studentEmails) {
            forStudents.add(nanosOf(() -> new StudentApi(mvc).requestResetCode(email)));
            forUnknown.add(nanosOf(() -> new StudentApi(mvc).requestResetCode(newEmail())));
        }

        long student = median(forStudents);
        long unknown = median(forUnknown);
        assertThat((double) Math.abs(student - unknown) / Math.max(student, unknown))
                .as("median for Students' emails %s ns, for unknown emails %s ns", student, unknown)
                .isLessThan(SIMILAR);
    }

    private static long nanosOf(Supplier<MvcTestResult> request) {
        long start = System.nanoTime();
        MvcTestResult result = request.get();
        long elapsed = System.nanoTime() - start;
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        return elapsed;
    }

    private static long median(List<Long> nanos) {
        return nanos.stream().sorted().toList().get(nanos.size() / 2);
    }

    private static String hmacSha256Hex(String key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte digestByte : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
            hex.append("%02x".formatted(digestByte));
        }
        return hex.toString();
    }
}

package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredAccounts.StoredAccount;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

class AccountServiceTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAnAdminWithTheEmailAndNameNormalized() {
        String email = uniqueEmail();
        Instant now = Instant.parse("2026-10-04T12:30:00Z");
        clock.set(now);

        accounts.createAdmin("  " + email.toUpperCase() + " ", "  Ana \t Maria  Souza  ", PASSWORD);

        StoredAccount stored = new StoredAccounts(jdbc).find(email).orElseThrow();
        assertThat(stored.name()).isEqualTo("Ana Maria Souza");
        assertThat(stored.role()).isEqualTo("ADMIN");
        assertThat(stored.createdAt()).isEqualTo(now);
        assertThat(stored.id()).isPositive().isLessThanOrEqualTo(new StoredAccounts(jdbc).accountSequenceValue());
    }

    @Test
    void storesThePasswordAsABcryptHashOfCostTenOrMore() {
        String email = uniqueEmail();

        accounts.createAdmin(email, "Ana", PASSWORD);

        StoredAccount admin = new StoredAccounts(jdbc).find(email).orElseThrow();
        assertThat(admin.passwordHash()).startsWith("{bcrypt}$2a$");
        int cost = Integer.parseInt(admin.passwordHash().split("\\$")[2]);
        assertThat(cost).isGreaterThanOrEqualTo(10);
        assertThat(admin.hasBcryptHashOf(PASSWORD)).isTrue();
    }

    @Test
    void refusesAnEmailAlreadyTakenInAnyLetterCaseOrSpacing() {
        String email = uniqueEmail();
        accounts.createAdmin(email, "Ana", PASSWORD);

        assertThatThrownBy(() -> accounts.createAdmin(" " + email.toUpperCase() + "  ", "Bia", PASSWORD))
                .isInstanceOf(EmailTakenException.class);

        assertThat(new StoredAccounts(jdbc).find(email).orElseThrow().name()).isEqualTo("Ana");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFields")
    void refusesAnInvalidFieldAndCreatesNothing(String description, String email, String name, String password,
                                                FieldViolation violation) {
        StoredAccounts stored = new StoredAccounts(jdbc);
        long accountsBefore = stored.count();

        assertThatThrownBy(() -> accounts.createAdmin(email, name, password))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        refusal -> assertThat(refusal.violations()).containsExactly(violation));

        assertThat(stored.count()).isEqualTo(accountsBefore);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                invalid("empty email", "", "Ana", PASSWORD, "email", "required"),
                invalid("blank email", " \t ", "Ana", PASSWORD, "email", "required"),
                invalid("email without @", "ana.aulaflix.com.br", "Ana", PASSWORD, "email", "invalid-email"),
                invalid("email without a dot after @", "ana@aulaflix", "Ana", PASSWORD, "email", "invalid-email"),
                invalid("email with inner space", "ana maria@aulaflix.com.br", "Ana", PASSWORD, "email",
                        "invalid-email"),
                invalid("email of 255 characters", "a".repeat(239) + "@aulaflix.com.br", "Ana", PASSWORD, "email",
                        "too-long"),
                invalid("empty name", uniqueEmail(), "", PASSWORD, "name", "required"),
                invalid("blank name", uniqueEmail(), " \u00a0\t ", PASSWORD, "name", "required"),
                invalid("name of 81 code points", uniqueEmail(), "\uD83D\uDE00".repeat(81), PASSWORD, "name",
                        "too-long"),
                invalid("empty password", uniqueEmail(), "Ana", "", "password", "required"),
                invalid("password of 7 characters", uniqueEmail(), "Ana", "1234567", "password", "too-short"),
                invalid("password of 7 emoji", uniqueEmail(), "Ana", "\uD83D\uDE00".repeat(7), "password",
                        "too-short"),
                invalid("password of 73 UTF-8 bytes", uniqueEmail(), "Ana", "\u00e9".repeat(36) + "a", "password",
                        "too-long"));
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        assertThatThrownBy(() -> accounts.createAdmin("ana", " ", "short"))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        refusal -> assertThat(refusal.violations()).containsExactly(
                                new FieldViolation("email", "invalid-email"),
                                new FieldViolation("name", "required"),
                                new FieldViolation("password", "too-short")));
    }

    @Test
    void acceptsEveryFieldAtItsLimit() {
        String longestEmail = "admin-" + UUID.randomUUID() + "a".repeat(196) + "@aulaflix.com.br";
        String longestName = "\uD83D\uDE00".repeat(80);
        String longestPassword = "\u00e9".repeat(36);
        String shortestPassword = "\uD83D\uDE00".repeat(8);
        String otherEmail = uniqueEmail();

        accounts.createAdmin(longestEmail, longestName, longestPassword);
        accounts.createAdmin(otherEmail, "Ana", shortestPassword);

        StoredAccounts stored = new StoredAccounts(jdbc);
        assertThat(longestEmail).hasSize(254);
        assertThat(stored.find(longestEmail).orElseThrow().name()).isEqualTo(longestName);
        assertThat(stored.find(longestEmail).orElseThrow().hasBcryptHashOf(longestPassword)).isTrue();
        assertThat(stored.find(otherEmail).orElseThrow().hasBcryptHashOf(shortestPassword)).isTrue();
    }

    private static Arguments invalid(String description, String email, String name, String password, String field,
                                     String code) {
        return Arguments.of(description, email, name, password, new FieldViolation(field, code));
    }

    private static String uniqueEmail() {
        return "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
    }
}

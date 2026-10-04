package com.devlabs.aulaflix.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredAccounts.StoredAccount;
import com.devlabs.aulaflix.service.AccountService;

class AdminCommandTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountService accounts;

    @Test
    void createsAnAdminWithThePasswordTypedTwice() {
        String email = uniqueEmail();
        ScriptedTerminal terminal = new ScriptedTerminal(PASSWORD, PASSWORD);

        int exitCode = AdminRun.against(postgres)
                .run(terminal, "admin", "create", "--email", " " + email.toUpperCase(), "--name", "Ana  Souza");

        assertThat(exitCode).isZero();
        assertThat(terminal.prompts()).containsExactly("Password: ", "Repeat the password: ");
        assertThat(terminal.output()).contains("Created the Admin Account " + email);
        StoredAccount admin = new StoredAccounts(jdbc).find(email).orElseThrow();
        assertThat(admin.name()).isEqualTo("Ana Souza");
        assertThat(admin.role()).isEqualTo("ADMIN");
        assertThat(admin.hasBcryptHashOf(PASSWORD)).isTrue();
    }

    @Test
    void refusesAConfirmationThatDoesNotMatchAndCreatesNothing() {
        String email = uniqueEmail();
        ScriptedTerminal terminal = new ScriptedTerminal(PASSWORD, PASSWORD + " ");

        int exitCode = AdminRun.against(postgres).run(terminal, "admin", "create", "--email", email, "--name", "Ana");

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).isEqualTo("The passwords do not match. Nothing was created.");
        assertThat(new StoredAccounts(jdbc).find(email)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void explainsEveryInvalidFieldAndCreatesNothing(String email, String name, String password, String explanation) {
        long accountsBefore = countAccounts();
        ScriptedTerminal terminal = new ScriptedTerminal(password, password);

        int exitCode = AdminRun.against(postgres).run(terminal, "admin", "create", "--email", email, "--name", name);

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).isEqualTo(explanation + "\nNothing was created.");
        assertThat(countAccounts()).isEqualTo(accountsBefore);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("ana@aulaflix", " ", "short", """
                        The email must look like name@example.com, with no spaces.
                        The name is required.
                        The password must have at least 8 characters."""),
                Arguments.of("a".repeat(239) + "@aulaflix.com.br", "x".repeat(81), "\u00e9".repeat(36) + "a", """
                        The email must have at most 254 characters.
                        The name must have at most 80 characters.
                        The password must have at most 72 bytes in UTF-8; accented letters take 2, emoji 4."""),
                Arguments.of("", "Ana", "", """
                        The email is required.
                        The password is required."""));
    }

    @Test
    void refusesAnEmailAlreadyTakenInAnyLetterCase() {
        String email = uniqueEmail();
        accounts.createAdmin(email, "Ana", PASSWORD);
        ScriptedTerminal terminal = new ScriptedTerminal(PASSWORD, PASSWORD);

        int exitCode = AdminRun.against(postgres)
                .run(terminal, "admin", "create", "--email", email.toUpperCase(), "--name", "Bia");

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).isEqualTo("An Account with this email already exists. Nothing was created.");
        assertThat(new StoredAccounts(jdbc).find(email).orElseThrow().name()).isEqualTo("Ana");
    }

    @Test
    void refusesWithoutATerminalAndCreatesNothing() {
        assumeTrue(System.console() == null, "this test needs a JVM without a terminal");
        String email = uniqueEmail();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream standardError = System.err;

        int exitCode;
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        try {
            exitCode = AdminMode.run(new String[] {"admin", "create", "--email", email, "--name", "Ana"});
        } finally {
            System.setErr(standardError);
        }

        assertThat(exitCode).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("admin needs a terminal to read the password");
        assertThat(new StoredAccounts(jdbc).find(email)).isEmpty();
    }

    @Test
    void refusesAnEmptyDatabaseAndNeverMigratesIt() {
        String database = "pending_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create database " + database);
        String url = "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
        ScriptedTerminal terminal = new ScriptedTerminal(PASSWORD, PASSWORD);

        int exitCode = new AdminRun(url, postgres.getUsername(), postgres.getPassword())
                .run(terminal, "admin", "create", "--email", uniqueEmail(), "--name", "Ana");

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).startsWith("The database is not migrated to this version of the API, so"
                + " nothing was done.");
        assertThat(terminal.output().lines().skip(1).findFirst()).hasValueSatisfying(
                details -> assertThat(details).startsWith("Flyway: "));
        assertThat(terminal.prompts()).isEmpty();
        JdbcTemplate pending = new JdbcTemplate(
                new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        assertThat(pending.queryForList("select table_name from information_schema.tables"
                + " where table_schema = 'public'", String.class)).isEmpty();
    }

    @Test
    void refusesWhileANewerMigrationIsPendingAndNeverAppliesIt() {
        String email = uniqueEmail();
        ScriptedTerminal terminal = new ScriptedTerminal(PASSWORD, PASSWORD);

        int exitCode = AdminRun.against(postgres)
                .with("spring.flyway.locations", "classpath:db/migration,classpath:db/pending-migration")
                .run(terminal, "admin", "create", "--email", email, "--name", "Ana");

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).startsWith("The database is not migrated to this version of the API, so"
                + " nothing was done.");
        assertThat(jdbc.queryForObject("select to_regclass('public.pending_migration_probe') is null", Boolean.class))
                .isTrue();
        assertThat(new StoredAccounts(jdbc).find(email)).isEmpty();
    }

    @Test
    void startsNoWebServerAndNoScheduledTask() {
        AdminRun run = AdminRun.against(postgres);

        int exitCode = run.run(new ScriptedTerminal(PASSWORD, PASSWORD),
                "admin", "create", "--email", uniqueEmail(), "--name", "Ana");

        assertThat(exitCode).isZero();
        assertThat(run.started()).isTrue();
        assertThat(run.startedWebServer()).isFalse();
        assertThat(run.scheduledTasks()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("malformedCommands")
    void printsTheUsageForAMalformedCommandAndCreatesNothing(List<String> args) {
        long accountsBefore = countAccounts();
        ScriptedTerminal terminal = new ScriptedTerminal();

        int exitCode = AdminRun.against(postgres).run(terminal, args.toArray(String[]::new));

        assertThat(exitCode).isEqualTo(1);
        assertThat(terminal.output()).isEqualTo("Usage: admin create --email <email> --name <name>");
        assertThat(terminal.prompts()).isEmpty();
        assertThat(countAccounts()).isEqualTo(accountsBefore);
    }

    static Stream<List<String>> malformedCommands() {
        String email = uniqueEmail();
        return Stream.of(
                List.of("admin"),
                List.of("admin", "delete", "--email", email, "--name", "Ana"),
                List.of("admin", "create"),
                List.of("admin", "create", "--email", email),
                List.of("admin", "create", "--email", email, "--name"),
                List.of("admin", "create", "--email", email, "--nome", "Ana"),
                List.of("admin", "create", "--email", email, "--name", "Ana", "--name", "Bia"),
                List.of("admin", "create", "--email", email, "--email", email));
    }

    private long countAccounts() {
        return new StoredAccounts(jdbc).count();
    }

    private static String uniqueEmail() {
        return "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
    }
}

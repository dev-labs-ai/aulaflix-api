package com.devlabs.aulaflix.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.devlabs.aulaflix.config.AdminModeConfiguration;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.exception.AdminNotFoundException;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.service.AccountService;

/** The commands the owner runs with {@code admin} as the jar's first argument. */
@Component
@Profile(AdminModeConfiguration.PROFILE)
public class AdminCommand {

    static final int SUCCESS = 0;
    static final int FAILURE = 1;

    private static final String USAGE = """
            Usage: admin create --email <email> --name <name>
                   admin password --email <email>""";
    private static final String NOTHING_CREATED = "Nothing was created.";
    private static final String NOTHING_CHANGED = "Nothing was changed.";
    private static final Map<FieldViolation, String> EXPLANATIONS = Map.of(
            new FieldViolation("email", "required"), "The email is required.",
            new FieldViolation("email", "too-long"), "The email must have at most 254 characters.",
            new FieldViolation("email", "invalid-email"), "The email must look like name@example.com, with no spaces.",
            new FieldViolation("name", "required"), "The name is required.",
            new FieldViolation("name", "too-long"), "The name must have at most 80 characters.",
            new FieldViolation("password", "required"), "The password is required.",
            new FieldViolation("password", "too-short"), "The password must have at least 8 characters.",
            new FieldViolation("password", "too-long"),
            "The password must have at most 72 bytes in UTF-8; accented letters take 2, emoji 4.");

    private final AccountService accounts;

    public AdminCommand(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Runs the command named by the arguments that follow {@code admin}, and returns the process exit code. */
    public int run(List<String> args, Terminal terminal) {
        String command = args.isEmpty() ? "" : args.getFirst();
        List<String> tokens = args.isEmpty() ? List.of() : args.subList(1, args.size());
        Optional<Integer> exitCode = switch (command) {
            case "create" -> options(tokens, Set.of("--email", "--name"))
                    .map(options -> create(options.get("--email"), options.get("--name"), terminal));
            case "password" -> options(tokens, Set.of("--email"))
                    .map(options -> changePassword(options.get("--email"), terminal));
            default -> Optional.empty();
        };
        return exitCode.orElseGet(() -> {
            terminal.println(USAGE);
            return FAILURE;
        });
    }

    private int create(String email, String name, Terminal terminal) {
        Optional<String> password = readTwice("Password: ", "Repeat the password: ", NOTHING_CREATED, terminal);
        if (password.isEmpty()) {
            return FAILURE;
        }
        try {
            AccountSummary admin = accounts.createAdmin(email, name, password.get());
            terminal.println("Created the Admin Account %s (id %d).".formatted(admin.email(), admin.id()));
            return SUCCESS;
        } catch (InvalidRequestException refusal) {
            return explain(refusal, NOTHING_CREATED, terminal);
        } catch (EmailTakenException refusal) {
            terminal.println("An Account with this email already exists. " + NOTHING_CREATED);
            return FAILURE;
        }
    }

    /** Sets a new password and ends every session of the Admin, who signs in again with it. */
    private int changePassword(String email, Terminal terminal) {
        Optional<String> password = readTwice("New password: ", "Repeat the new password: ", NOTHING_CHANGED,
                terminal);
        if (password.isEmpty()) {
            return FAILURE;
        }
        try {
            AccountSummary admin = accounts.changeAdminPassword(email, password.get());
            terminal.println("Changed the password of the Admin Account %s (id %d) and ended all its sessions."
                    .formatted(admin.email(), admin.id()));
            return SUCCESS;
        } catch (InvalidRequestException refusal) {
            return explain(refusal, NOTHING_CHANGED, terminal);
        } catch (AdminNotFoundException refusal) {
            terminal.println("No Admin Account has this email. " + NOTHING_CHANGED);
            return FAILURE;
        }
    }

    /** The password typed twice, or nothing, after saying so, when the two differ. */
    private static Optional<String> readTwice(String prompt, String repeatPrompt, String nothingDone,
                                              Terminal terminal) {
        String password = terminal.readPassword(prompt);
        if (!password.equals(terminal.readPassword(repeatPrompt))) {
            terminal.println("The passwords do not match. " + nothingDone);
            return Optional.empty();
        }
        return Optional.of(password);
    }

    private static int explain(InvalidRequestException refusal, String nothingDone, Terminal terminal) {
        refusal.violations().forEach(violation -> terminal.println(explain(violation)));
        terminal.println(nothingDone);
        return FAILURE;
    }

    private static String explain(FieldViolation violation) {
        return EXPLANATIONS.getOrDefault(violation, "%s: %s".formatted(violation.field(), violation.code()));
    }

    /** Pairs each {@code --option} with its value, accepting exactly the given options, each once. */
    private static Optional<Map<String, String>> options(List<String> tokens, Set<String> names) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < tokens.size(); i += 2) {
            options.put(tokens.get(i), tokens.get(i + 1));
        }
        boolean exact = tokens.size() == 2 * names.size() && options.keySet().equals(names);
        return exact ? Optional.of(options) : Optional.empty();
    }
}

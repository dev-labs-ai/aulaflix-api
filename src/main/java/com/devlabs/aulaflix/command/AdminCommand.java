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

    private static final String USAGE = "Usage: admin create --email <email> --name <name>";
    private static final String NOTHING_CREATED = "Nothing was created.";
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
        Optional<Map<String, String>> createOptions = args.isEmpty() || !args.getFirst().equals("create")
                ? Optional.empty()
                : options(args.subList(1, args.size()), Set.of("--email", "--name"));
        if (createOptions.isEmpty()) {
            terminal.println(USAGE);
            return FAILURE;
        }
        return create(createOptions.get().get("--email"), createOptions.get().get("--name"), terminal);
    }

    private int create(String email, String name, Terminal terminal) {
        String password = terminal.readPassword("Password: ");
        if (!password.equals(terminal.readPassword("Repeat the password: "))) {
            terminal.println("The passwords do not match. " + NOTHING_CREATED);
            return FAILURE;
        }
        try {
            AccountSummary admin = accounts.createAdmin(email, name, password);
            terminal.println("Created the Admin Account %s (id %d).".formatted(admin.email(), admin.id()));
            return SUCCESS;
        } catch (InvalidRequestException refusal) {
            refusal.violations().forEach(violation -> terminal.println(explain(violation)));
            terminal.println(NOTHING_CREATED);
            return FAILURE;
        } catch (EmailTakenException refusal) {
            terminal.println("An Account with this email already exists. " + NOTHING_CREATED);
            return FAILURE;
        }
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

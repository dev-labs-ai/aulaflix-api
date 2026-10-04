package com.devlabs.aulaflix.command;

import java.io.Console;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.devlabs.aulaflix.AulaflixApiApplication;
import com.devlabs.aulaflix.config.AdminModeConfiguration;

/** The jar's command-line mode: {@code java -jar aulaflix-api.jar admin <command> …}. */
public final class AdminMode {

    private static final String FIRST_ARGUMENT = "admin";

    private AdminMode() {
    }

    public static boolean isRequested(String[] args) {
        return args.length > 0 && FIRST_ARGUMENT.equals(args[0]);
    }

    /** Runs the admin command from the terminal the jar was started from, and returns the process exit code. */
    public static int run(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("admin needs a terminal to read the password without echoing it, so nothing was"
                    + " done. Run it from an interactive shell; docker compose run allocates a terminal by default.");
            return AdminCommand.FAILURE;
        }
        return run(args, new ConsoleTerminal(console));
    }

    static int run(String[] args, Terminal terminal) {
        try (ConfigurableApplicationContext context = application().run()) {
            ValidateResult schema = context.getBean(Flyway.class).validateWithResult();
            if (!schema.validationSuccessful) {
                terminal.println("The database is not migrated to this version of the API, so nothing was done."
                        + " Start the API once so it applies its migrations, then run admin again.");
                terminal.println("Flyway: " + schema.getAllErrorMessages());
                return AdminCommand.FAILURE;
            }
            List<String> commandArgs = List.of(args).subList(1, args.length);
            return context.getBean(AdminCommand.class).run(commandArgs, terminal);
        }
    }

    /** The arguments stay out of the Spring environment: they belong to the command, not to configuration. */
    private static SpringApplication application() {
        SpringApplication application = new SpringApplication(AulaflixApiApplication.class);
        application.setAdditionalProfiles(AdminModeConfiguration.PROFILE);
        return application;
    }
}

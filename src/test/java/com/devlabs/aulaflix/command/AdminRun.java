package com.devlabs.aulaflix.command;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.devlabs.aulaflix.AistorContainer;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.Hibp;

/**
 * Runs the jar's admin mode as {@code main} does, pointed at a test database, and notes what the started
 * application contained.
 */
final class AdminRun {

    private final Map<String, Object> properties = new HashMap<>();
    private boolean started;
    private boolean webServer;
    private List<ScheduledTask> scheduledTasks = List.of();

    /**
     * The storage only has to be configured: the API refuses to start without it, in admin mode too. HIBP is the
     * tests' own, since a new password is checked against it. Asaas's key and webhook token only have to be there:
     * admin mode never calls Asaas, nor receives its webhook.
     */
    AdminRun(String url, String username, String password, AistorContainer storage, Hibp hibp) {
        properties.put("spring.datasource.url", url);
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", password);
        storage.applicationProperties().forEach((name, value) -> properties.put(name, value.get()));
        hibp.applicationProperties().forEach((name, value) -> properties.put(name, value.get()));
        properties.put("aulaflix.asaas.api-key", Asaas.API_KEY);
        properties.put("aulaflix.asaas.webhook-token", Asaas.WEBHOOK_TOKEN);
    }

    static AdminRun against(PostgreSQLContainer postgres, AistorContainer storage, Hibp hibp) {
        return new AdminRun(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), storage, hibp);
    }

    /** Sets one more property in the admin run's environment, above every other source. */
    AdminRun with(String property, String value) {
        properties.put(property, value);
        return this;
    }

    int run(Terminal terminal, String... args) {
        return SpringApplication.withHook(application -> new Listener(), () -> AdminMode.run(args, terminal));
    }

    boolean started() {
        return started;
    }

    boolean startedWebServer() {
        return webServer;
    }

    List<ScheduledTask> scheduledTasks() {
        return scheduledTasks;
    }

    private final class Listener implements SpringApplicationRunListener {

        @Override
        public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext,
                                        ConfigurableEnvironment environment) {
            environment.getPropertySources().addFirst(new MapPropertySource("admin-run", properties));
        }

        @Override
        public void started(ConfigurableApplicationContext context, Duration timeTaken) {
            started = true;
            webServer = context instanceof WebServerApplicationContext;
            scheduledTasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .toList();
        }
    }
}

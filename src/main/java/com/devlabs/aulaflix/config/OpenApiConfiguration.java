package com.devlabs.aulaflix.config;

import java.util.function.Consumer;
import java.util.stream.Stream;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The spec and Swagger UI are on in every environment: only the SSH tunnel reaches them, the same way in as the Admin
 * endpoints. "Authorize" takes the token that {@code POST /v1/admin/sessions} returns, and the BFF's key for the
 * {@code bff} group.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "AulaFlix API", version = "v1"))
@SecurityScheme(name = OpenApiConfiguration.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "The token from POST /v1/admin/sessions.")
@SecurityScheme(name = OpenApiConfiguration.BFF_KEY, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = BffRequestFilter.KEY_HEADER, description = "The secret the BFF shares with the API.")
public class OpenApiConfiguration {

    public static final String BEARER = "bearer";
    public static final String BFF_KEY = "bffKey";

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/v1/admin/**")
                .addOpenApiCustomizer(sharedRefusals())
                .build();
    }

    /** What the BFF calls: everything under {@code /v1} but the Admin's endpoints. */
    @Bean
    GroupedOpenApi bffApi() {
        return GroupedOpenApi.builder()
                .group("bff")
                .pathsToMatch("/v1/**")
                .pathsToExclude("/v1/admin/**")
                .addOpenApiCustomizer(bffRails())
                .addOpenApiCustomizer(sharedRefusals())
                .build();
    }

    /**
     * The refusals every endpoint shares, declared once. A token that is sent must be valid even where no session is
     * needed, so any endpoint can answer 401; only those that need a session can answer 403. Every refusal is a
     * ProblemDetail, so an endpoint's own refusals need only name their problem.
     */
    private static OpenApiCustomizer sharedRefusals() {
        return openApi -> operations(openApi).forEach(addSharedRefusals());
    }

    private static Consumer<Operation> addSharedRefusals() {
        return operation -> {
            operation.getResponses()
                    .addApiResponse("401", problem("No valid session token: missing, unknown, expired or revoked"))
                    .addApiResponse("500", problem("Unexpected failure"));
            if (requiresASession(operation)) {
                addRefusal(operation, "403", "A session of another role");
            }
            operation.getResponses().forEach((status, response) -> {
                if (status.startsWith("4") && !isProblem(response)) {
                    response.content(problemContent());
                }
            });
        };
    }

    /** Every BFF endpoint takes the BFF's key and the browser's IP, and refuses a request without either. */
    private static OpenApiCustomizer bffRails() {
        return openApi -> operations(openApi).forEach(operation -> {
            operation.addSecurityItem(new SecurityRequirement().addList(BFF_KEY));
            operation.addParametersItem(new HeaderParameter()
                    .name(BffRequestFilter.CLIENT_IP_HEADER)
                    .required(true)
                    .description("The browser's IP address, IPv4 or IPv6, as the BFF read it")
                    .schema(new StringSchema().example("203.0.113.7")));
            addRefusal(operation, "400", "`invalid-client-ip`: `AulaFlix-Client-IP` is missing or not an IP address");
            addRefusal(operation, "403", "`invalid-bff-key`: the request did not come from the BFF");
        });
    }

    /** Adds the refusal, or appends it to what the endpoint already answers with that status. */
    private static void addRefusal(Operation operation, String status, String description) {
        ApiResponse existing = operation.getResponses().get(status);
        operation.getResponses().addApiResponse(status, existing == null
                ? problem(description)
                : existing.description(existing.getDescription() + "; or " + description));
    }

    private static Stream<Operation> operations(OpenAPI openApi) {
        return openApi.getPaths().values().stream().flatMap(path -> path.readOperations().stream());
    }

    private static boolean requiresASession(Operation operation) {
        return operation.getSecurity() != null
                && operation.getSecurity().stream().anyMatch(requirement -> requirement.containsKey(BEARER));
    }

    /** springdoc gives a refusal declared without content the method's own return type, as if it were a success. */
    private static boolean isProblem(ApiResponse response) {
        return response.getContent() != null && response.getContent()
                .containsKey(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(problemContent());
    }

    private static Content problemContent() {
        return new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
    }
}

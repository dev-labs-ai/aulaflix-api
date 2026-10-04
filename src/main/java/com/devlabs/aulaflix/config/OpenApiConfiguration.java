package com.devlabs.aulaflix.config;

import java.util.function.Consumer;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The spec and Swagger UI are on in every environment: only the SSH tunnel reaches them, the same way in as the Admin
 * endpoints. "Authorize" takes the token that {@code POST /v1/admin/sessions} returns.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "AulaFlix API", version = "v1"))
@SecurityScheme(name = OpenApiConfiguration.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "The token from POST /v1/admin/sessions.")
public class OpenApiConfiguration {

    public static final String BEARER = "bearer";

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/v1/admin/**")
                .addOpenApiCustomizer(sharedRefusals())
                .build();
    }

    /**
     * The refusals every endpoint shares, declared once. A token that is sent must be valid even where no session is
     * needed, so any endpoint can answer 401; only those that need a session can answer 403.
     */
    private static OpenApiCustomizer sharedRefusals() {
        return openApi -> openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .forEach(addSharedRefusals());
    }

    private static Consumer<Operation> addSharedRefusals() {
        return operation -> {
            operation.getResponses()
                    .addApiResponse("401", problem("No valid session token: missing, unknown, expired or revoked"))
                    .addApiResponse("500", problem("Unexpected failure"));
            if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
                operation.getResponses().addApiResponse("403", problem("A session of another role"));
            }
        };
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }
}

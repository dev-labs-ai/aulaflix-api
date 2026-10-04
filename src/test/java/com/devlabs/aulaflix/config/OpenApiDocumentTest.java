package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.devlabs.aulaflix.IntegrationTest;

/** The Admin works through Swagger UI over the SSH tunnel, so the document and the UI need no session. */
class OpenApiDocumentTest extends IntegrationTest {

    @Test
    void servesTheAdminGroupWithTheBearerScheme() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "components": {
                            "securitySchemes": {"bearer": {"type": "http", "scheme": "bearer"}}
                          },
                          "paths": {
                            "/v1/admin/sessions": {
                              "post": {
                                "tags": ["Admin sessions"],
                                "responses": {
                                  "201": {
                                    "content": {
                                      "application/json": {"schema": {"$ref": "#/components/schemas/IssuedSession"}}
                                    }
                                  }
                                }
                              }
                            },
                            "/v1/admin/sessions/current": {
                              "delete": {"tags": ["Admin sessions"], "security": [{"bearer": []}]}
                            }
                          }
                        }""");
    }

    @Test
    void asksNoSessionToSignIn() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).hasStatusOk().bodyJson()
                .hasPath("$.paths['/v1/admin/sessions'].post")
                .doesNotHavePath("$.paths['/v1/admin/sessions'].post.security")
                .doesNotHavePath("$.paths['/v1/admin/sessions/current'].delete.parameters");
    }

    @Test
    void documentsEveryRefusalAsAProblemDetail() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/sessions/current'].delete.responses")
                .asMap()
                .containsOnlyKeys("204", "401", "403", "500");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/sessions'].post.responses")
                .asMap()
                .containsOnlyKeys("201", "400", "401", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/sessions'].post.responses['429'].content")
                .asMap()
                .containsOnlyKeys("application/problem+json");
    }

    @Test
    void servesSwaggerUiListingTheAdminGroup() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config")).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.urls[*].name").asArray().contains("admin");
    }
}

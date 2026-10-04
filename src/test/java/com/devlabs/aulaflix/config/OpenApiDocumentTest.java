package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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
    void documentsEveryStatusEachCourseEndpointCanAnswer() {
        assertResponses("/v1/admin/courses", "post", "201", "400", "401", "403", "409", "500");
        assertResponses("/v1/admin/courses", "get", "200", "401", "403", "500");
        assertResponses("/v1/admin/courses/{courseId}", "get", "200", "401", "403", "404", "500");
        assertResponses("/v1/admin/courses/{courseId}", "put", "200", "400", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/courses/{courseId}", "delete", "204", "401", "403", "404", "409", "500");
    }

    @Test
    void documentsTheCourseEndpointsAsAdminOnly() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/admin/courses": {
                      "post": {
                        "tags": ["Admin courses"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "201": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AdminCourse"}}}
                          }
                        }
                      },
                      "get": {"tags": ["Admin courses"], "security": [{"bearer": []}]}
                    },
                    "/v1/admin/courses/{courseId}": {
                      "get": {"tags": ["Admin courses"], "security": [{"bearer": []}]},
                      "put": {"tags": ["Admin courses"], "security": [{"bearer": []}]},
                      "delete": {"tags": ["Admin courses"], "security": [{"bearer": []}]}
                    }
                  }
                }""");
    }

    @Test
    void listsTheCodesEachCourseFieldTakes() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().isLenientlyEqualTo("""
                {
                  "components": {
                    "schemas": {
                      "CourseDocument": {
                        "properties": {
                          "area": {
                            "enum": ["BACKEND", "FRONTEND", "DATABASES", "DEVOPS", "AI", "QUALITY", "ARCHITECTURE"]
                          },
                          "icon": {
                            "enum": ["SERVER", "APP_WINDOW", "DATABASE", "CONTAINER", "BOT", "FLASK_CONICAL", "BLOCKS"]
                          },
                          "tone": {"enum": ["CORAL", "YELLOW", "SAGE"]},
                          "maxInstallments": {"minimum": 1, "maximum": 12}
                        }
                      },
                      "AdminCourse": {
                        "properties": {"status": {"enum": ["DRAFT", "COMING_SOON", "ON_SALE"]}}
                      }
                    }
                  }
                }""");
    }

    @Test
    void documentsEveryCourseRefusalAsAProblemDetail() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .hasPath("$.components.schemas.ProblemDetail.properties.type");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/courses/{courseId}'].put.responses[*].content")
                .asArray()
                .hasSize(7)
                .filteredOn(content -> ((Map<?, ?>) content).containsKey("application/problem+json"))
                .hasSize(6);
    }

    @Test
    void documentsEveryStatusEachOutlineEndpointCanAnswer() {
        assertResponses("/v1/admin/courses/{courseId}/outline", "get", "200", "401", "403", "404", "500");
        assertResponses("/v1/admin/courses/{courseId}/outline", "put",
                "200", "400", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/courses/{courseId}/modules", "post", "201", "400", "401", "403", "404", "500");
        assertResponses("/v1/admin/modules/{moduleId}", "put", "200", "400", "401", "403", "404", "500");
        assertResponses("/v1/admin/modules/{moduleId}", "delete", "204", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/modules/{moduleId}/lessons", "post",
                "201", "400", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/lessons/{lessonId}", "put", "200", "400", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/lessons/{lessonId}", "delete", "204", "401", "403", "404", "500");
    }

    @Test
    void documentsTheOutlineEndpointsAsAdminOnly() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/admin/courses/{courseId}/outline": {
                      "get": {
                        "tags": ["Admin outline"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {
                                "schema": {"type": "array", "items": {"$ref": "#/components/schemas/OutlineModule"}}
                              }
                            }
                          }
                        }
                      },
                      "put": {
                        "tags": ["Admin outline"],
                        "security": [{"bearer": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"type": "array", "items": {"$ref": "#/components/schemas/OutlineModule"}}
                            }
                          }
                        }
                      }
                    },
                    "/v1/admin/courses/{courseId}/modules": {
                      "post": {
                        "tags": ["Admin outline"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "201": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AdminModule"}}}
                          }
                        }
                      }
                    },
                    "/v1/admin/modules/{moduleId}": {
                      "put": {"tags": ["Admin outline"], "security": [{"bearer": []}]},
                      "delete": {"tags": ["Admin outline"], "security": [{"bearer": []}]}
                    },
                    "/v1/admin/modules/{moduleId}/lessons": {
                      "post": {
                        "tags": ["Admin outline"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "201": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AdminLesson"}}}
                          }
                        }
                      }
                    },
                    "/v1/admin/lessons/{lessonId}": {
                      "put": {"tags": ["Admin outline"], "security": [{"bearer": []}]},
                      "delete": {"tags": ["Admin outline"], "security": [{"bearer": []}]}
                    }
                  }
                }""");
    }

    @Test
    void documentsEveryOutlineRefusalAsAProblemDetail() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/courses/{courseId}/outline'].put.responses[*].content")
                .asArray()
                .hasSize(7)
                .filteredOn(content -> ((Map<?, ?>) content).containsKey("application/problem+json"))
                .hasSize(6);
    }

    private void assertResponses(String path, String method, String... statuses) {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['%s'].%s.responses".formatted(path, method))
                .asMap()
                .containsOnlyKeys(statuses);
    }

    @Test
    void servesSwaggerUiListingTheAdminGroup() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config")).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.urls[*].name").asArray().contains("admin");
    }
}

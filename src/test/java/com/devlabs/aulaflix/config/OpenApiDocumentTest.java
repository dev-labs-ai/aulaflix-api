package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.devlabs.aulaflix.IntegrationTest;

/**
 * The Admin works through Swagger UI over the SSH tunnel, so the documents and the UI need no session and no BFF key.
 */
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

    @Test
    void documentsEveryStatusEachVideoEndpointCanAnswer() {
        assertResponses("/v1/admin/lessons/{lessonId}/video-uploads", "post", "200", "401", "403", "404", "500");
        assertResponses("/v1/admin/lessons/{lessonId}/video", "put", "200", "400", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/lessons/{lessonId}/playback", "get", "200", "401", "403", "404", "500");
    }

    @Test
    void documentsEveryProblemLinkingAVideoCanAnswer() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/lessons/{lessonId}/video'].put.responses['409'].description")
                .asString()
                .contains("`video-not-found`", "`video-not-mp4`", "`video-not-faststart`", "`video-not-h264`",
                        "`audio-not-aac`", "`video-too-short`");
    }

    @Test
    void documentsTheVideoEndpointsAsAdminOnly() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/admin/lessons/{lessonId}/video-uploads": {
                      "post": {
                        "tags": ["Admin videos"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/VideoUpload"}}}
                          }
                        }
                      }
                    },
                    "/v1/admin/lessons/{lessonId}/video": {
                      "put": {
                        "tags": ["Admin videos"],
                        "security": [{"bearer": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/VideoLinkRequest"}}
                          }
                        },
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AdminLesson"}}}
                          }
                        }
                      }
                    },
                    "/v1/admin/lessons/{lessonId}/playback": {
                      "get": {
                        "tags": ["Admin videos"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {"schema": {"$ref": "#/components/schemas/VideoPlayback"}}
                            }
                          }
                        }
                      }
                    }
                  }
                }""");
    }

    @Test
    void documentsEveryStatusTheStatusEndpointCanAnswer() {
        assertResponses("/v1/admin/courses/{courseId}/status", "put",
                "200", "400", "401", "403", "404", "409", "500");
    }

    @Test
    void servesTheBffGroupWithTheCatalogAndNoAdminEndpoint() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).hasStatusOk().bodyJson()
                .extractingPath("$.paths").asMap()
                .containsOnlyKeys("/v1/courses", "/v1/courses/{slug}");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/courses": {
                      "get": {
                        "tags": ["Catalog"],
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CourseList"}}}
                          }
                        }
                      }
                    },
                    "/v1/courses/{slug}": {
                      "get": {
                        "tags": ["Catalog"],
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CourseDetail"}}}
                          }
                        }
                      }
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().doesNotHavePath("$.paths['/v1/courses']");
    }

    @Test
    void asksEveryBffEndpointForTheKeyAndTheBrowsersIp() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "components": {
                    "securitySchemes": {"bffKey": {"type": "apiKey", "in": "header", "name": "AulaFlix-BFF-Key"}}
                  },
                  "paths": {
                    "/v1/courses": {
                      "get": {
                        "security": [{"bffKey": []}],
                        "parameters": [{"name": "AulaFlix-Client-IP", "in": "header", "required": true}]
                      }
                    },
                    "/v1/courses/{slug}": {
                      "get": {
                        "security": [{"bffKey": []}],
                        "parameters": [
                          {"name": "slug", "in": "path", "required": true},
                          {"name": "AulaFlix-Client-IP", "in": "header", "required": true}
                        ]
                      }
                    }
                  }
                }""");
    }

    @Test
    void documentsEveryStatusEachCatalogEndpointCanAnswer() {
        assertResponsesIn("bff", "/v1/courses", "get", "200", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/courses/{slug}", "get", "200", "400", "401", "403", "404", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/courses'].get.responses['403'].description").asString()
                .contains("invalid-bff-key")
                .doesNotContain("role");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/courses'].get.responses['400'].description").asString()
                .contains("invalid-request", "invalid-client-ip");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/courses/{slug}'].get.responses[*].content")
                .asArray()
                .hasSize(7)
                .filteredOn(content -> ((Map<?, ?>) content).containsKey("application/problem+json"))
                .hasSize(6);
    }

    @Test
    void tellsTheBffWhenToRetryARateLimitedRequest() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/courses'].get.responses['429']").isEqualTo(Map.of(
                        "description", "`rate-limited`: past a limit on requests; retry after Retry-After seconds",
                        "headers", Map.of("Retry-After", Map.of(
                                "description", "Seconds until the limit lets this request through",
                                "schema", Map.of("type", "integer"))),
                        "content", Map.of("application/problem+json",
                                Map.of("schema", Map.of("$ref", "#/components/schemas/ProblemDetail")))));
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths[*][*].responses['429'].description").asArray()
                .isNotEmpty()
                .noneSatisfy(description -> assertThat(description).asString().contains("rate-limited"));
    }

    private void assertResponses(String path, String method, String... statuses) {
        assertResponsesIn("admin", path, method, statuses);
    }

    private void assertResponsesIn(String group, String path, String method, String... statuses) {
        assertThat(mvc.get().uri("/v3/api-docs/" + group)).bodyJson()
                .extractingPath("$.paths['%s'].%s.responses".formatted(path, method))
                .asMap()
                .containsOnlyKeys(statuses);
    }

    @Test
    void servesSwaggerUiListingTheAdminAndBffGroups() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config")).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.urls[*].name").asArray().contains("admin", "bff");
    }
}

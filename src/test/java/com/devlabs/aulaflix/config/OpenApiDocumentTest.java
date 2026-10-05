package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertResponses("/v1/admin/lessons/{lessonId}", "delete", "204", "401", "403", "404", "409", "500");
        assertResponses("/v1/admin/lessons/{lessonId}/status", "put",
                "200", "400", "401", "403", "404", "409", "500");
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
                    },
                    "/v1/admin/lessons/{lessonId}/status": {
                      "put": {
                        "tags": ["Admin outline"],
                        "security": [{"bearer": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/LessonStatusChange"}}
                          }
                        },
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AdminLesson"}}}
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "LessonStatusChange": {"properties": {"status": {"enum": ["PUBLISHED"]}}}
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
    void servesTheBffGroupWithEveryEndpointButTheAdmins() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).hasStatusOk().bodyJson()
                .extractingPath("$.paths").asMap()
                .containsOnlyKeys("/v1/courses", "/v1/courses/{slug}", "/v1/lessons/{lessonId}/playback",
                        "/v1/account-lookups", "/v1/accounts", "/v1/account", "/v1/sessions",
                        "/v1/sessions/current", "/v1/email-confirmations", "/v1/account/confirmation-emails",
                        "/v1/account/enrollments", "/v1/account/enrollments/{courseId}",
                        "/v1/account/completed-lessons/{lessonId}", "/v1/account/lesson-visits",
                        "/v1/account/orders", "/v1/account/orders/{code}", "/v1/password-reset-codes",
                        "/v1/password-resets", "/v1/account/password-change-codes", "/v1/account/password",
                        "/v1/waitlist-entries", "/v1/account/waitlists/{courseId}");
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
    void documentsThePricingAndTheSyllabusOfAnOnSaleCourse() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "components": {
                    "schemas": {
                      "CourseListItem": {
                        "properties": {
                          "pricing": {"$ref": "#/components/schemas/CoursePricing"},
                          "lessonCount": {"type": "integer"}
                        }
                      },
                      "CourseDetail": {
                        "properties": {
                          "pricing": {"$ref": "#/components/schemas/CoursePricing"},
                          "freeLessonId": {"type": "integer"},
                          "modules": {"type": "array", "items": {"$ref": "#/components/schemas/SyllabusModule"}}
                        }
                      },
                      "SyllabusModule": {
                        "properties": {
                          "lessons": {"type": "array", "items": {"$ref": "#/components/schemas/SyllabusLesson"}}
                        }
                      },
                      "SyllabusLesson": {
                        "properties": {
                          "published": {"type": "boolean"},
                          "slug": {"type": "string"},
                          "durationSeconds": {"type": "integer"}
                        }
                      }
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.CoursePricing.properties").asMap()
                .containsOnlyKeys("priceCents", "pixDiscountPercent", "pixPriceCents", "maxInstallments",
                        "installmentCents");
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
    void documentsPlaybackWithASessionOrWithout() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/lessons/{lessonId}/playback": {
                      "get": {
                        "tags": ["Playback"],
                        "parameters": [
                          {"name": "lessonId", "in": "path", "required": true},
                          {"name": "AulaFlix-Client-IP", "in": "header", "required": true}
                        ],
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
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.parameters").asArray().hasSize(2);
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.security").asArray()
                .containsExactlyInAnyOrder(Map.of("bffKey", List.of()),
                        Map.of("bffKey", List.of(), "bearer", List.of()));
    }

    @Test
    void documentsEveryStatusPlaybackCanAnswer() {
        assertResponsesIn("bff", "/v1/lessons/{lessonId}/playback", "get",
                "200", "400", "401", "403", "404", "409", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.responses['403'].description")
                .asString().contains("`forbidden`", "Admin", "`invalid-bff-key`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.responses['404'].description")
                .asString().contains("`lesson-not-found`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.responses['409'].description")
                .asString().contains("`enrollment-required`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/lessons/{lessonId}/playback'].get.responses[*].content")
                .asArray()
                .hasSize(8)
                .filteredOn(content -> ((Map<?, ?>) content).containsKey("application/problem+json"))
                .hasSize(7);
    }

    @Test
    void documentsEveryStatusEachEnrollmentEndpointCanAnswer() {
        assertResponses("/v1/admin/enrollments", "post", "201", "400", "401", "403", "409", "500");
        assertResponses("/v1/admin/enrollments", "get", "200", "400", "401", "403", "500");
        assertResponses("/v1/admin/enrollments/{enrollmentId}", "get", "200", "401", "403", "404", "500");
        assertResponses("/v1/admin/enrollments/{enrollmentId}/status", "put",
                "200", "400", "401", "403", "404", "409", "500");
    }

    @Test
    void documentsTheEnrollmentListsQueryParameters() {
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.paths['/v1/admin/enrollments'].get.parameters[*].name").asArray()
                .containsExactlyInAnyOrder("email", "courseId", "active", "page", "size");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/admin/enrollments": {
                      "get": {
                        "tags": ["Admin enrollments"],
                        "security": [{"bearer": []}],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {
                                "schema": {"$ref": "#/components/schemas/PageResponseAdminEnrollment"}
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }""");
    }

    @Test
    void documentsTheStudentsAccountAndSessionsWithASessionWhereTheyNeedOne() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/account-lookups": {
                      "post": {
                        "tags": ["Accounts"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/AccountLookupRequest"}}
                          }
                        },
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AccountLookup"}}}
                          }
                        }
                      }
                    },
                    "/v1/accounts": {
                      "post": {
                        "tags": ["Accounts"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {"application/json": {"schema": {"$ref": "#/components/schemas/SignUpRequest"}}}
                        },
                        "responses": {
                          "201": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/IssuedSession"}}}
                          }
                        }
                      }
                    },
                    "/v1/account": {
                      "get": {
                        "tags": ["Accounts"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "responses": {
                          "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Account"}}}}
                        }
                      },
                      "put": {
                        "tags": ["Accounts"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "requestBody": {
                          "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AccountDocument"}}}
                        },
                        "responses": {
                          "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Account"}}}}
                        }
                      }
                    },
                    "/v1/sessions": {
                      "post": {
                        "tags": ["Sessions"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {"application/json": {"schema": {"$ref": "#/components/schemas/SignInRequest"}}}
                        },
                        "responses": {
                          "201": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/IssuedSession"}}}
                          }
                        }
                      }
                    },
                    "/v1/sessions/current": {
                      "delete": {"tags": ["Sessions"], "security": [{"bearer": [], "bffKey": []}]}
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.Account.properties").asMap()
                .containsOnlyKeys("name", "email", "emailConfirmed");
    }

    @Test
    void documentsEveryStatusEachAccountAndSessionEndpointCanAnswer() {
        assertResponsesIn("bff", "/v1/account-lookups", "post", "200", "400", "401", "403", "429", "500", "503");
        assertResponsesIn("bff", "/v1/accounts", "post", "201", "400", "401", "403", "409", "429", "500", "503");
        assertResponsesIn("bff", "/v1/account", "get", "200", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/account", "put", "200", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/sessions", "post", "201", "400", "401", "403", "429", "500", "503");
        assertResponsesIn("bff", "/v1/sessions/current", "delete", "204", "400", "401", "403", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/sessions'].post.responses['429'].description").asString()
                .contains("`sign-in-blocked`", "`rate-limited`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/sessions'].post.responses['400'].description").asString()
                .contains("`invalid-request`", "`invalid-credentials`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/accounts'].post.responses['409'].description").asString()
                .contains("`email-taken`");
    }

    @Test
    void documentsTheConfirmationLinkWithoutASessionAndItsResendWithOne() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/email-confirmations": {
                      "post": {
                        "tags": ["Email confirmation"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/EmailConfirmationRequest"}}
                          }
                        }
                      }
                    },
                    "/v1/account/confirmation-emails": {
                      "post": {"tags": ["Email confirmation"], "security": [{"bearer": [], "bffKey": []}]}
                    }
                  }
                }""");
        assertResponsesIn("bff", "/v1/email-confirmations", "post", "204", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/account/confirmation-emails", "post", "204", "400", "401", "403", "409", "429",
                "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/email-confirmations'].post.responses['400'].description").asString()
                .contains("`invalid-request`", "`invalid-confirmation-link`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/confirmation-emails'].post.responses['409'].description")
                .asString().contains("`email-already-confirmed`");
    }

    @Test
    void documentsTheLearningEndpointsWithASession() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/account/enrollments": {
                      "get": {
                        "tags": ["Learning"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {"schema": {"$ref": "#/components/schemas/StudentEnrollmentList"}}
                            }
                          }
                        }
                      }
                    },
                    "/v1/account/enrollments/{courseId}": {
                      "get": {
                        "tags": ["Learning"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {"schema": {"$ref": "#/components/schemas/StudentEnrollmentDetail"}}
                            }
                          }
                        }
                      }
                    },
                    "/v1/account/completed-lessons/{lessonId}": {
                      "put": {"tags": ["Learning"], "security": [{"bearer": [], "bffKey": []}]},
                      "delete": {"tags": ["Learning"], "security": [{"bearer": [], "bffKey": []}]}
                    },
                    "/v1/account/lesson-visits": {
                      "post": {
                        "tags": ["Learning"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "requestBody": {
                          "content": {"application/json": {"schema": {"$ref": "#/components/schemas/LessonVisitRequest"}}}
                        }
                      }
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.StudentEnrollmentList.properties").asMap()
                .containsOnlyKeys("items", "highlightedCourseId");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.StudentEnrollment.properties").asMap()
                .containsOnlyKeys("course", "progress", "resumeLesson");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.StudentEnrollmentDetail.properties").asMap()
                .containsOnlyKeys("course", "progress", "resumeLesson", "completedLessonIds");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.ResumeLesson.properties").asMap()
                .containsOnlyKeys("id", "slug", "number", "title");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.Progress.properties").asMap()
                .containsOnlyKeys("completed", "published", "total", "percent", "standing");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.Progress.properties.standing.enum")
                .isEqualTo(List.of("NOT_STARTED", "IN_PROGRESS", "CAUGHT_UP", "FINISHED"));
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.EnrolledCourse.properties").asMap()
                .containsOnlyKeys("id", "slug", "title", "area", "icon", "tone", "status");
    }

    /** Each operation with a soft limit takes the token, and may ask for it, or fail to verify it. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/account-lookups", "/v1/accounts", "/v1/sessions", "/v1/password-reset-codes",
            "/v1/waitlist-entries"})
    void documentsTheCaptchaTokenWhereASoftLimitAsksForIt(String path) {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "%s": {
                      "post": {
                        "parameters": [
                          {"name": "AulaFlix-Captcha-Token", "in": "header"},
                          {"name": "AulaFlix-Client-IP", "in": "header", "required": true}
                        ],
                        "responses": {
                          "503": {
                            "headers": {"Retry-After": {"schema": {"type": "integer"}}},
                            "content": {
                              "application/problem+json": {"schema": {"$ref": "#/components/schemas/ProblemDetail"}}
                            }
                          }
                        }
                      }
                    }
                  }
                }""".formatted(path));
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['%s'].post.parameters[?(@.name == 'AulaFlix-Captcha-Token' && @.required)]"
                        .formatted(path)).asArray().isEmpty();
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['%s'].post.responses['429'].description".formatted(path)).asString()
                .contains("`captcha-required`", "`rate-limited`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['%s'].post.responses['503'].description".formatted(path)).asString()
                .contains("`captcha-unavailable`");
    }

    @Test
    void documentsTheCaptchaTokenNowhereElse() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths[*][*].parameters[?(@.name == 'AulaFlix-Captcha-Token')]").asArray()
                .hasSize(5);
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/password-resets'].post.responses").asMap().doesNotContainKey("503");
    }

    @Test
    void documentsThePasswordResetWithoutASession() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/password-reset-codes": {
                      "post": {
                        "tags": ["Password reset"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/PasswordResetCodeRequest"}}
                          }
                        }
                      }
                    },
                    "/v1/password-resets": {
                      "post": {
                        "tags": ["Password reset"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/PasswordResetRequest"}}
                          }
                        },
                        "responses": {
                          "200": {
                            "content": {
                              "application/json": {"schema": {"$ref": "#/components/schemas/IssuedSession"}}
                            }
                          }
                        }
                      }
                    }
                  }
                }""");
        assertResponsesIn("bff", "/v1/password-reset-codes", "post",
                "204", "400", "401", "403", "429", "500", "503");
        assertResponsesIn("bff", "/v1/password-resets", "post", "200", "400", "401", "403", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/password-resets'].post.responses['400'].description").asString()
                .contains("`invalid-request`", "`breached`", "`invalid-code`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.PasswordResetRequest.properties").asMap()
                .containsOnlyKeys("email", "code", "newPassword");
    }

    @Test
    void documentsThePasswordChangeWithASession() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/account/password-change-codes": {
                      "post": {"tags": ["Password change"], "security": [{"bearer": [], "bffKey": []}]}
                    },
                    "/v1/account/password": {
                      "put": {
                        "tags": ["Password change"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/PasswordChangeRequest"}}
                          }
                        }
                      }
                    }
                  }
                }""");
        assertResponsesIn("bff", "/v1/account/password-change-codes", "post", "204", "400", "401", "403", "429",
                "500");
        assertResponsesIn("bff", "/v1/account/password", "put", "204", "400", "401", "403", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/password'].put.responses['400'].description").asString()
                .contains("`invalid-request`", "`breached`", "`invalid-code`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/password-change-codes'].post.responses['429'].description")
                .asString().contains("`rate-limited`", "60 seconds", "10 codes");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.PasswordChangeRequest.properties").asMap()
                .containsOnlyKeys("code", "newPassword");
    }

    @Test
    void documentsEveryStatusEachLearningEndpointCanAnswer() {
        assertResponsesIn("bff", "/v1/account/enrollments", "get", "200", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/account/enrollments/{courseId}", "get",
                "200", "400", "401", "403", "404", "429", "500");
        for (String method : List.of("put", "delete")) {
            assertResponsesIn("bff", "/v1/account/completed-lessons/{lessonId}", method,
                    "204", "400", "401", "403", "404", "409", "429", "500");
            assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                    .extractingPath("$.paths['/v1/account/completed-lessons/{lessonId}'].%s.responses['404'].description"
                            .formatted(method))
                    .asString().contains("`lesson-not-found`");
            assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                    .extractingPath("$.paths['/v1/account/completed-lessons/{lessonId}'].%s.responses['409'].description"
                            .formatted(method))
                    .asString().contains("`enrollment-required`");
        }
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/enrollments/{courseId}'].get.responses['404'].description")
                .asString().contains("`enrollment-not-found`");
        assertResponsesIn("bff", "/v1/account/lesson-visits", "post",
                "204", "400", "401", "403", "404", "409", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/lesson-visits'].post.responses['404'].description")
                .asString().contains("`lesson-not-found`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/lesson-visits'].post.responses['409'].description")
                .asString().contains("`enrollment-required`");
    }

    @Test
    void documentsTheStudentsOrdersWithASession() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/account/orders": {
                      "post": {
                        "tags": ["Orders"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "requestBody": {
                          "content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderRequest"}}}
                        },
                        "responses": {
                          "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Order"}}}},
                          "201": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Order"}}}}
                        }
                      },
                      "get": {
                        "tags": ["Orders"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "responses": {
                          "200": {
                            "content": {"application/json": {"schema": {"$ref": "#/components/schemas/OrderList"}}}
                          }
                        }
                      }
                    },
                    "/v1/account/orders/{code}": {
                      "get": {
                        "tags": ["Orders"],
                        "security": [{"bearer": [], "bffKey": []}],
                        "responses": {
                          "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Order"}}}}
                        }
                      }
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.Order.properties").asMap()
                .containsOnlyKeys("code", "status", "method", "course", "amountCents", "createdAt", "paidAt",
                        "duplicatePayment", "installments", "pix", "checkout");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.CheckoutPayment.properties").asMap()
                .containsOnlyKeys("url", "expiresAt");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.OrderRequest.properties").asMap()
                .containsOnlyKeys("courseId", "method", "cpf");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.OrderRequest.properties.method.enum")
                .isEqualTo(List.of("PIX", "CARD"));
    }

    @Test
    void documentsEveryStatusEachOrderEndpointCanAnswer() {
        assertResponsesIn("bff", "/v1/account/orders", "post",
                "200", "201", "400", "401", "403", "409", "429", "500", "502", "503");
        assertResponsesIn("bff", "/v1/account/orders", "get", "200", "400", "401", "403", "429", "500");
        assertResponsesIn("bff", "/v1/account/orders/{code}", "get", "200", "400", "401", "403", "404", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders'].post.responses['400'].description").asString()
                .contains("`invalid-request`", "`required`", "`invalid-cpf`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders'].post.responses['409'].description").asString()
                .contains("`course-not-for-sale`", "`already-enrolled`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders'].post.responses['502'].description").asString()
                .contains("`payment-provider-error`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders'].post.responses['502'].content").asMap()
                .containsOnlyKeys("application/problem+json");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders'].post.responses['503']").isEqualTo(Map.of(
                        "description", "`payment-unavailable`: Asaas is down, too slow, or busy; the Order is "
                                + "cancelled, and a new one may be placed after Retry-After seconds",
                        "headers", Map.of("Retry-After", Map.of(
                                "description", "Seconds to wait before trying again",
                                "style", "simple",
                                "schema", Map.of("type", "integer"))),
                        "content", Map.of("application/problem+json",
                                Map.of("schema", Map.of("$ref", "#/components/schemas/ProblemDetail")))));
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/orders/{code}'].get.responses['404'].description").asString()
                .contains("`order-not-found`");
    }

    @Test
    void documentsTheWaitlistByEmailWithoutASessionAndTheStudentsWithOne() {
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson().isLenientlyEqualTo("""
                {
                  "paths": {
                    "/v1/waitlist-entries": {
                      "post": {
                        "tags": ["Waitlist"],
                        "security": [{"bffKey": []}],
                        "requestBody": {
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/WaitlistEntryRequest"}}
                          }
                        }
                      }
                    },
                    "/v1/account/waitlists/{courseId}": {
                      "get": {"tags": ["Waitlist"], "security": [{"bearer": [], "bffKey": []}]},
                      "put": {"tags": ["Waitlist"], "security": [{"bearer": [], "bffKey": []}]},
                      "delete": {"tags": ["Waitlist"], "security": [{"bearer": [], "bffKey": []}]}
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.components.schemas.WaitlistEntryRequest.properties").asMap()
                .containsOnlyKeys("courseId", "email");
        assertThat(mvc.get().uri("/v3/api-docs/admin")).bodyJson()
                .extractingPath("$.components.schemas.AdminCourse.properties").asMap()
                .containsKey("waitlistCount");
    }

    @Test
    void documentsEveryStatusEachWaitlistEndpointCanAnswer() {
        assertResponsesIn("bff", "/v1/waitlist-entries", "post",
                "204", "400", "401", "403", "409", "429", "500", "503");
        assertResponsesIn("bff", "/v1/account/waitlists/{courseId}", "get",
                "204", "400", "401", "403", "404", "429", "500");
        assertResponsesIn("bff", "/v1/account/waitlists/{courseId}", "put",
                "204", "400", "401", "403", "409", "429", "500");
        assertResponsesIn("bff", "/v1/account/waitlists/{courseId}", "delete",
                "204", "400", "401", "403", "429", "500");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/waitlist-entries'].post.responses['409'].description").asString()
                .contains("`waitlist-closed`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/waitlists/{courseId}'].put.responses['409'].description")
                .asString().contains("`waitlist-closed`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/account/waitlists/{courseId}'].get.responses['404'].description")
                .asString().contains("`not-on-waitlist`");
        assertThat(mvc.get().uri("/v3/api-docs/bff")).bodyJson()
                .extractingPath("$.paths['/v1/waitlist-entries'].post.responses['403'].description").asString()
                .contains("`invalid-bff-key`", "Admin");
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
    void servesSwaggerUiListingTheAdminBffAndWebhookGroups() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML);
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config")).hasStatus(HttpStatus.OK)
                .bodyJson().extractingPath("$.urls[*].name").asArray().contains("admin", "bff", "webhooks");
    }

    /** Asaas calls it with its token, never with the BFF's key and the browser's IP. */
    @Test
    void servesTheWebhookInAGroupOfItsOwnWithAsaassToken() {
        assertThat(mvc.get().uri("/v3/api-docs/webhooks")).hasStatusOk().bodyJson()
                .extractingPath("$.paths").asMap().containsOnlyKeys("/v1/webhooks/asaas");
        assertThat(mvc.get().uri("/v3/api-docs/webhooks")).bodyJson().isLenientlyEqualTo("""
                {
                  "components": {
                    "securitySchemes": {
                      "webhookToken": {"type": "apiKey", "in": "header", "name": "asaas-access-token"}
                    }
                  },
                  "paths": {
                    "/v1/webhooks/asaas": {
                      "post": {"tags": ["Webhooks"], "security": [{"webhookToken": []}]}
                    }
                  }
                }""");
        assertThat(mvc.get().uri("/v3/api-docs/webhooks")).bodyJson()
                .doesNotHavePath("$.paths['/v1/webhooks/asaas'].post.parameters");
        assertResponsesIn("webhooks", "/v1/webhooks/asaas", "post", "200", "401", "403", "413", "500");
        assertThat(mvc.get().uri("/v3/api-docs/webhooks")).bodyJson()
                .extractingPath("$.paths['/v1/webhooks/asaas'].post.responses['403'].description").asString()
                .contains("`invalid-webhook-token`");
        assertThat(mvc.get().uri("/v3/api-docs/webhooks")).bodyJson()
                .extractingPath("$.paths['/v1/webhooks/asaas'].post.responses['413'].content").asMap()
                .containsOnlyKeys("application/problem+json");
    }
}

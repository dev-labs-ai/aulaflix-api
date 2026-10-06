package com.devlabs.aulaflix;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;

import com.devlabs.aulaflix.service.AccountService;

/**
 * storage-init runs on every {@code docker compose up}, against storage that kept its bucket, policies and users from
 * the run before. The AIStor container ran {@code storage/init.sh} once as it started; a second run must succeed too,
 * and leave the API's users working.
 */
class StorageInitTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AistorContainer storage;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private AccountService accounts;

    @Test
    void runsAgainOnStorageItSetUpAlreadyAndLeavesTheApisUsersWorking() {
        ExecResult again = storage.runInit();

        assertThat(again.getExitCode()).as(again.getStderr()).isZero();
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        long course = courses.onSale(newSlug());
        assertThat(new BffApi(mvc).get("/v1/lessons/%d/playback".formatted(courses.freeLessonOf(course))).exchange())
                .hasStatusOk();
    }
}

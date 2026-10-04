package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the BFF's key,
 * nor a wrong one sent in its place, which could be the right one mistyped.
 */
@ExtendWith(OutputCaptureExtension.class)
class BffRequestFilterLogsTest extends IntegrationTest {

    @Test
    void logsNeitherTheKeyNorAWrongOne(CapturedOutput output) {
        String wrongKey = BffApi.KEY.substring(0, BffApi.KEY.length() - 1) + "!";

        new BffApi(mvc).get("/v1/courses").exchange();
        new BffApi(mvc).get("/v1/courses/no-such-course").exchange();
        new BffApi(mvc).get("/v1/courses?area=BACKEND").exchange();
        mvc.get().uri("/v1/courses").header("AulaFlix-BFF-Key", wrongKey).exchange();
        mvc.get().uri("/v1/courses").header("AulaFlix-BFF-Key", BffApi.KEY).exchange();
        mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", BffApi.KEY)
                .header("AulaFlix-Client-IP", "x")
                .exchange();

        assertThat(output.getAll()).isNotBlank().doesNotContain(BffApi.KEY, wrongKey);
    }
}

package com.devlabs.aulaflix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.json.JsonMapper;

/** A Student's Orders, placed and read through the HTTP contract the way the BFF does for one browser. */
public final class StudentOrders {

    private final BffApi bff;
    private final String bearer;

    public StudentOrders(BffApi bff, String token) {
        this.bff = bff;
        this.bearer = "Bearer " + token;
    }

    /** A Pix Order for the Course, with the CPF when it is not null. */
    public MvcTestResult placePix(long courseId, String cpf) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", courseId);
        body.put("method", "PIX");
        if (cpf != null) {
            body.put("cpf", cpf);
        }
        return place(JsonMapper.shared().writeValueAsString(body));
    }

    /** An Order whose body is exactly this JSON. */
    public MvcTestResult place(String body) {
        return bff.post("/v1/account/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** The new Order's code, failing the test unless the Order is placed. */
    public String placedPix(long courseId, String cpf) {
        MvcTestResult placed = placePix(courseId, cpf);
        assertThat(placed).hasStatus(HttpStatus.CREATED);
        return codeOf(placed);
    }

    public MvcTestResult list() {
        return bff.get("/v1/account/orders").header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    public MvcTestResult get(String code) {
        return bff.get("/v1/account/orders/" + code).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    public static String codeOf(MvcTestResult order) {
        return JsonPath.read(AdminApi.body(order), "$.code");
    }
}

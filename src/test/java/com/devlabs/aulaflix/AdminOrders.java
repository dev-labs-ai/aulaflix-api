package com.devlabs.aulaflix;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Finds, reads and refunds Orders through the Admin endpoints, the way the Admin does with curl. */
public final class AdminOrders {

    private final MockMvcTester mvc;
    private final String bearer;

    public AdminOrders(MockMvcTester mvc, String adminToken) {
        this.mvc = mvc;
        this.bearer = "Bearer " + adminToken;
    }

    /** The list, with the query string as given: {@code "email=…&status=PAID"}, or empty. */
    public MvcTestResult list(String query) {
        return mvc.get().uri("/v1/admin/orders" + (query.isEmpty() ? "" : "?" + query))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
    }

    public MvcTestResult get(String code) {
        return mvc.get().uri("/v1/admin/orders/" + code).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    /** A refund, with no body, as the endpoint takes it. */
    public MvcTestResult refund(String code) {
        return mvc.post().uri("/v1/admin/orders/%s/refund".formatted(code))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
    }
}

package com.devlabs.aulaflix.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.dto.AdminOrder;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.exception.QueryParametersNotAllowedException;
import com.devlabs.aulaflix.service.AdminOrderService;

/** Every refusal is a ProblemDetail; a code of any shape answers like an unknown one. */
@RestController
@RequestMapping("/v1/admin/orders")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Admin orders", description = "Finding any Order, in every state, and refunding one")
public class AdminOrderController {

    /** The filters, then the page: the order is fixed, so {@code sort} is refused like any other. */
    private static final List<String> LIST_PARAMETERS = List.of("status", "courseId", "email", "duplicatePayment",
            "page", "size");

    private final AdminOrderService orders;

    public AdminOrderController(AdminOrderService orders) {
        this.orders = orders;
    }

    /** The answer's type is generic, so its media type is declared here for springdoc to describe it. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List every Order", description = """
            Every state, newest first, each as its own read shows it. Paginated, 20 to a page by default and at \
            most 100. Each filter is optional, and they combine.""")
    @Parameter(name = "status", in = ParameterIn.QUERY, description = "The Order's status",
            schema = @Schema(implementation = OrderStatus.class))
    @Parameter(name = "courseId", in = ParameterIn.QUERY, description = "The Course's id",
            schema = @Schema(type = "integer", format = "int64"))
    @Parameter(name = "email", in = ParameterIn.QUERY,
            description = "The Student's email, matched trimmed and lower-cased")
    @Parameter(name = "duplicatePayment", in = ParameterIn.QUERY,
            description = "`true` for Duplicate payments only, `false` for the others only",
            schema = @Schema(type = "boolean"))
    @Parameter(name = "page", in = ParameterIn.QUERY, description = "The page, from 0",
            schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
    @Parameter(name = "size", in = ParameterIn.QUERY, description = "How many to a page",
            schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100"))
    @ApiResponse(responseCode = "200", description = "One page of Orders")
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`: `status` is no Order status, or `duplicatePayment` is neither `true` nor `false`, \
            with its code in `errors`; or the request carries any other query parameter""")
    public PageResponse<AdminOrder> list(HttpServletRequest request,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String courseId,
                                         @RequestParam(required = false) String email,
                                         @RequestParam(required = false) String duplicatePayment,
                                         @Parameter(hidden = true) @PageableDefault(size = 20) Pageable page) {
        if (!LIST_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw new QueryParametersNotAllowedException(LIST_PARAMETERS);
        }
        return orders.list(status, courseId, email, duplicatePayment, page);
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read an Order", description = """
            In any state, with its Student, the prices it was placed at, the days since its payment, the Enrollment \
            its payment granted, its Asaas charge and its refund.""")
    @ApiResponse(responseCode = "200", description = "The Order",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminOrder.class)))
    @ApiResponse(responseCode = "404", description = "`order-not-found`")
    public AdminOrder get(@PathVariable String code) {
        return orders.get(code);
    }

    @PostMapping("/{code}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Refund an Order", description = """
            Refunds a paid Order in full through Asaas, with no time limit: decide from `daysSincePayment`. Once \
            Asaas takes it, the Order is `REFUNDING`, the Enrollment its payment granted ends with `REFUND`, and the \
            Student is emailed; the Order becomes `REFUNDED` when Asaas reports the refund done. A Duplicate payment \
            granted nothing, so the Student keeps their access. An Order already refunding or refunded answers as \
            it is, and Asaas is not asked again. No body.""")
    @ApiResponse(responseCode = "200", description = "The Order as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminOrder.class)))
    @ApiResponse(responseCode = "404", description = "`order-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `order-not-paid`: the Order was never paid, or was reversed; or `refund-refused`: Asaas refused the \
            refund, for the `reasons` it gives, each with its `code` and `description`. Nothing changed""")
    @ApiResponse(responseCode = "503", description = """
            `payment-unavailable`: Asaas is down, too slow, or busy; nothing changed: try again after Retry-After \
            seconds""",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again",
                    schema = @Schema(type = "integer")))
    public AdminOrder refund(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String code) {
        return orders.refund(admin.accountId(), code);
    }
}

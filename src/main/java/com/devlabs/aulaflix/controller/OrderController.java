package com.devlabs.aulaflix.controller;

import java.net.URI;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.dto.OrderList;
import com.devlabs.aulaflix.dto.OrderRequest;
import com.devlabs.aulaflix.dto.PlacedOrder;
import com.devlabs.aulaflix.service.OrderService;

/** The Student's own Orders, under the singleton Account, so there is no Student id to authorize. */
@RestController
@RequestMapping("/v1/account/orders")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Orders", description = "Placing Orders, paid by Pix on AulaFlix's page, and reading the Student's own")
public class OrderController {

    private static final String ORDERS = "/v1/account/orders/";

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Place an Order", description = """
            Buys an On sale Course by Pix, at its current Pix price: Asaas makes a charge, whose QR code and \
            copy-and-paste code the page shows until the Order expires, 30 minutes later. Asking again while that \
            Order awaits payment answers it again and makes no new charge. The Student's first Pix needs their CPF, \
            which makes their Asaas customer and is never stored; later ones need none. Each placement counts \
            against the Student's limit, the IP's and everyone's.""")
    @ApiResponse(responseCode = "201", description = "Placed; `Location` is the new Order's address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Order.class)))
    @ApiResponse(responseCode = "200", description = "The Order already awaiting payment by this method",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Order.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`, with a code for each field in `errors`: `cpf` is `required` on the Student's first \
            Pix, and `invalid-cpf` when it is not 11 digits, its check digits are wrong, it repeats one digit, or \
            Asaas refuses it""")
    @ApiResponse(responseCode = "409", description = """
            `course-not-for-sale`: no Course has the id, or it is a Draft or Coming soon; or `already-enrolled`: \
            the Student already has an active Enrollment in the Course""")
    @ApiResponse(responseCode = "502", description = """
            `payment-provider-error`: Asaas refused the payment; the Order is cancelled, and a new one may be placed""")
    @ApiResponse(responseCode = "503", description = """
            `payment-unavailable`: Asaas is down, too slow, or busy; the Order is cancelled, and a new one may be \
            placed after Retry-After seconds""",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again",
                    schema = @Schema(type = "integer")))
    public ResponseEntity<Order> place(@AuthenticationPrincipal AuthenticatedAccount student,
                                       @Valid @RequestBody OrderRequest request) {
        PlacedOrder placed = orders.place(student.accountId(), request);
        if (!placed.created()) {
            return ResponseEntity.ok(placed.order());
        }
        return ResponseEntity.created(URI.create(ORDERS + placed.order().code())).body(placed.order());
    }

    @GetMapping
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "List the Student's Orders", description = """
            Newest first, awaiting payment, paid, refunding, refunded or reversed; never-paid ones (expired, \
            cancelled, declined) are left out. The list never carries the means of payment: read the Order for it.""")
    @ApiResponse(responseCode = "200", description = "The Student's Orders",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OrderList.class)))
    public OrderList list(@AuthenticationPrincipal AuthenticatedAccount student) {
        return orders.list(student.accountId());
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Read one of the Student's Orders", description = """
            In any state, with the Pix QR code while it awaits payment; the page polls it to notice the payment.""")
    @ApiResponse(responseCode = "200", description = "The Order",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Order.class)))
    @ApiResponse(responseCode = "404", description = "`order-not-found`: the Student has no Order with the code")
    public Order get(@AuthenticationPrincipal AuthenticatedAccount student, @PathVariable String code) {
        return orders.get(student.accountId(), code);
    }
}

package com.devlabs.aulaflix.dto;

import java.util.Optional;

import com.devlabs.aulaflix.domain.OrderStatus;

/**
 * The Admin's filters on the Orders, each optional, once read from the query: the status; the Course's id; the
 * Student's email, as typed; and whether the Order is a Duplicate payment.
 */
public record OrderSearch(Optional<OrderStatus> status, Optional<Long> courseId, Optional<String> email,
                          Optional<Boolean> duplicatePayment) {
}

package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.dto.AdminOrder;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.exception.AsaasRefusedException;
import com.devlabs.aulaflix.exception.AsaasUnavailableException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.exception.OrderNotFoundException;
import com.devlabs.aulaflix.exception.PaymentUnavailableException;
import com.devlabs.aulaflix.exception.RefundRefusedException;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The Orders module, as the Admin meets it: finding any Order to answer a Student, and refunding one in full, as the
 * 7-day guarantee promises. A refund asks Asaas first, outside any transaction, and changes nothing unless Asaas takes
 * it; reconciliation then follows it until Asaas reports it done.
 */
@Service
public class AdminOrderService {

    private static final String INVALID_FORMAT = "invalid-format";

    private final OrderRepository repository;
    private final AdminOrderViews views;
    private final OrderRefunds refunds;
    private final AsaasGateway asaas;

    public AdminOrderService(OrderRepository repository, AdminOrderViews views, OrderRefunds refunds,
                             AsaasGateway asaas) {
        this.repository = repository;
        this.views = views;
        this.refunds = refunds;
        this.asaas = asaas;
    }

    /**
     * Every state, newest first. Each filter is optional: the status; the Course's id, of any shape, which matches
     * nothing unless some Course could have it; the Student's email, matched trimmed and lower-cased; and whether the
     * Order is a Duplicate payment, {@code true} or {@code false}. Only the page and its size are taken from the
     * request: the order is fixed.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrder> list(String status, String courseId, String email, String duplicatePayment,
                                         Pageable pageable) {
        OrderStatus onlyStatus = parseStatus(status);
        Boolean onlyDuplicates = parseBoolean("duplicatePayment", duplicatePayment);
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Optional<Long> course = Optional.ofNullable(courseId).flatMap(PathIds::parse);
        if (courseId != null && course.isEmpty()) {
            return views.page(Page.empty(page));
        }
        String student = email == null ? null : AccountInputRules.normalizeEmail(email);
        return views.page(repository.search(onlyStatus, course.orElse(null), student, onlyDuplicates, page));
    }

    /** Any Order, by its code, in any state. */
    @Transactional(readOnly = true)
    public AdminOrder get(String code) {
        return repository.findWithPartiesByCode(code).map(views::view).orElseThrow(OrderNotFoundException::new);
    }

    /**
     * Refunds the paid Order in full. Once Asaas takes the refund the Order is refunding, the Enrollment its payment
     * granted ends, and the Student is emailed. An Order refunding or refunded already answers as it is, without
     * asking Asaas again. When Asaas refuses, or cannot be reached, nothing changes.
     */
    public AdminOrder refund(long adminId, String code) {
        OrderRefunds.RefundStart start = refunds.refundable(code);
        if (start.already() != null) {
            return start.already();
        }
        try {
            if (start.installmentId() != null) {
                asaas.refundInstallmentPlan(start.installmentId());
            } else {
                asaas.refundCharge(start.chargeId());
            }
        } catch (AsaasUnavailableException failure) {
            throw new PaymentUnavailableException("Order %s not refunded".formatted(code), failure);
        } catch (AsaasRefusedException refusal) {
            throw new RefundRefusedException(code, refusal);
        }
        return refunds.requested(adminId, start.orderId(), start.studentId());
    }

    /** One of the statuses, by its name, or no filter at all. */
    private static OrderStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(List.of(new FieldViolation("status", INVALID_FORMAT)));
        }
    }

    /** Only {@code true} or {@code false}, or no filter at all. */
    private static Boolean parseBoolean(String field, String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidRequestException(List.of(new FieldViolation(field, INVALID_FORMAT)));
        };
    }
}

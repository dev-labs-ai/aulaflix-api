package com.devlabs.aulaflix.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.dto.AdminOrder;
import com.devlabs.aulaflix.dto.OrderSearch;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.exception.AsaasRefusedException;
import com.devlabs.aulaflix.exception.AsaasUnavailableException;
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
     * Every state, newest first, matching every filter given; the Student's email is matched trimmed and lower-cased.
     * Only the page and its size are taken from the request: the order is fixed.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrder> list(OrderSearch search, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return views.page(repository.search(search.status().orElse(null), search.courseId().orElse(null),
                search.email().map(AccountInputRules::normalizeEmail).orElse(null),
                search.duplicatePayment().orElse(null), page));
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
        return switch (refunds.refundable(code)) {
            case OrderRefunds.RefundStart.Started started -> started.order();
            case OrderRefunds.RefundStart.Refundable refundable -> refund(adminId, code, refundable);
        };
    }

    private AdminOrder refund(long adminId, String code, OrderRefunds.RefundStart.Refundable start) {
        try {
            switch (start.payment()) {
                case OrderRefunds.RefundStart.Charge charge -> asaas.refundCharge(charge.id());
                case OrderRefunds.RefundStart.InstallmentPlan plan -> asaas.refundInstallmentPlan(plan.id());
            }
        } catch (AsaasUnavailableException failure) {
            throw new PaymentUnavailableException("Order %s not refunded".formatted(code), failure);
        } catch (AsaasRefusedException refusal) {
            throw new RefundRefusedException(code, refusal);
        }
        return refunds.requested(adminId, start.orderId(), start.studentId());
    }
}

package com.devlabs.aulaflix.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;

/**
 * A Student's request to buy one Course by one payment method, at the prices of the moment it was placed. It is written
 * before Asaas is called, so its charge arrives afterwards.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    /** The states a confirmed payment moves to {@code PAID}. */
    private static final Set<OrderStatus> PAYABLE = EnumSet.of(OrderStatus.AWAITING_PAYMENT, OrderStatus.EXPIRED,
            OrderStatus.CANCELLED);

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_order")
    @SequenceGenerator(name = "seq_order", sequenceName = "seq_order", allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 8, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private AccountEntity student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private CourseEntity course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "list_price_cents", nullable = false, updatable = false)
    private int listPriceCents;

    @Column(name = "pix_discount_percent", nullable = false, updatable = false)
    private int pixDiscountPercent;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private int amountCents;

    @Column(name = "asaas_payment_id", length = 64)
    private String asaasPaymentId;

    @Column(name = "pix_qr_code_png")
    private String pixQrCodePng;

    @Column(name = "pix_copy_paste_code")
    private String pixCopyPasteCode;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "duplicate_payment", nullable = false)
    private boolean duplicatePayment;

    @Column(name = "charges_to_delete", nullable = false)
    private boolean chargesToDelete;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "asaas_checkout_id", length = 64)
    private String asaasCheckoutId;

    @Column(name = "checkout_url")
    private String checkoutUrl;

    @Column(name = "installments")
    private Integer installments;

    @Column(name = "asaas_installment_id", length = 64)
    private String asaasInstallmentId;

    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_requested_by")
    private AccountEntity refundRequestedBy;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /** When reconciliation last re-read the paid Order's charge; written only by its own update, never by a save. */
    @Column(name = "charge_checked_at", insertable = false, updatable = false)
    private Instant chargeCheckedAt;

    protected OrderEntity() {
    }

    /**
     * A Pix Order for the Pix price, awaiting payment until it expires; its charge comes once Asaas makes it. The
     * discount is the Course's at the moment, none being 0.
     */
    public static OrderEntity pix(String code, AccountEntity student, CourseEntity course, int pixDiscountPercent,
                                  int amountCents, Instant createdAt, Instant expiresAt) {
        return awaiting(PaymentMethod.PIX, code, student, course, pixDiscountPercent, amountCents, createdAt,
                expiresAt);
    }

    /**
     * A card Order for the Course's price, awaiting payment until it expires; its Checkout comes once Asaas makes it.
     * It keeps the Pix discount of the moment, none being 0, though a card never takes it.
     */
    public static OrderEntity card(String code, AccountEntity student, CourseEntity course, int pixDiscountPercent,
                                   Instant createdAt, Instant expiresAt) {
        return awaiting(PaymentMethod.CARD, code, student, course, pixDiscountPercent, course.getPriceCents(),
                createdAt, expiresAt);
    }

    private static OrderEntity awaiting(PaymentMethod method, String code, AccountEntity student, CourseEntity course,
                                        int pixDiscountPercent, int amountCents, Instant createdAt,
                                        Instant expiresAt) {
        OrderEntity order = new OrderEntity();
        order.code = code;
        order.student = student;
        order.course = course;
        order.method = method;
        order.status = OrderStatus.AWAITING_PAYMENT;
        order.listPriceCents = course.getPriceCents();
        order.pixDiscountPercent = pixDiscountPercent;
        order.amountCents = amountCents;
        order.createdAt = createdAt;
        order.expiresAt = expiresAt;
        return order;
    }

    /**
     * Whether an Asaas charge is this Order's to act on: made under the Order's code, its external reference, or, as
     * Asaas may not copy a Checkout's reference onto the charges its payer makes, under none, on the Order's Checkout;
     * and for the Order's amount, the sale's value in reais.
     */
    public boolean matchesCharge(String externalReference, String checkoutId, BigDecimal value) {
        boolean underTheOrder = externalReference != null ? code.equals(externalReference)
                : asaasCheckoutId != null && asaasCheckoutId.equals(checkoutId);
        return underTheOrder && BigDecimal.valueOf(amountCents, 2).compareTo(value) == 0;
    }

    /** The Pix charge Asaas made under the Order's code, and the QR code that pays it. */
    public void recordPixCharge(String paymentId, String qrCodePng, String copyPasteCode) {
        this.asaasPaymentId = paymentId;
        this.pixQrCodePng = qrCodePng;
        this.pixCopyPasteCode = copyPasteCode;
    }

    /** The Checkout Asaas made under the Order's code, and the link the Student pays it at. */
    public void recordCheckout(String checkoutId, String url) {
        this.asaasCheckoutId = checkoutId;
        this.checkoutUrl = url;
    }

    /**
     * The card charge that paid the Order, the first of the sale's installments Asaas showed; the installment plan it
     * is part of, null for a single payment, which a refund of the whole sale goes through; and how many installments
     * the Student chose. An Order that has its charge keeps it.
     */
    public void recordCardPayment(String paymentId, String installmentId, int installmentCount) {
        if (asaasPaymentId == null) {
            asaasPaymentId = paymentId;
            asaasInstallmentId = installmentId;
            installments = installmentCount;
        }
    }

    /**
     * Declined while it awaited payment, as Asaas's risk analysis rejected the card, and answers whether it was; any
     * other state stays, since a payment wins.
     */
    public boolean decline() {
        if (status != OrderStatus.AWAITING_PAYMENT) {
            return false;
        }
        status = OrderStatus.DECLINED;
        return true;
    }

    /** Cancelled while it awaited payment; any other state stays, since a payment wins. */
    public void cancel() {
        if (status == OrderStatus.AWAITING_PAYMENT) {
            status = OrderStatus.CANCELLED;
        }
    }

    /**
     * Cancelled after a failed placement had asked Asaas for a charge, which may be there under the Order's code,
     * whether or not Asaas answered: reconciliation deletes it. Any other state stays, since a payment wins.
     */
    public void cancelLeavingChargesToDelete() {
        if (status == OrderStatus.AWAITING_PAYMENT) {
            status = OrderStatus.CANCELLED;
            chargesToDelete = true;
        }
    }

    /** No charge of the cancelled Order is left at Asaas. */
    public void chargesDeleted() {
        chargesToDelete = false;
    }

    /** Expired while it awaited payment, and answers whether it was; any other state stays, since a payment wins. */
    public boolean expire() {
        if (status != OrderStatus.AWAITING_PAYMENT) {
            return false;
        }
        status = OrderStatus.EXPIRED;
        return true;
    }

    /**
     * Paid at the moment given, if it awaited payment, expired or was cancelled, since a payment wins over either and
     * money taken must be recorded for an Admin to refund it, and answers whether it was; an Order already paid keeps
     * the moment it was paid first.
     */
    public boolean pay(Instant at) {
        if (!PAYABLE.contains(status)) {
            return false;
        }
        status = OrderStatus.PAID;
        paidAt = at;
        return true;
    }

    /** Paid while its Student already had the Course: it grants nothing, and an Admin refunds it. */
    public void markDuplicatePayment() {
        duplicatePayment = true;
    }

    /**
     * Refunding from the moment given, if it was paid, at the Admin's request, or at nobody's when the refund was made
     * in the Asaas UI, and answers whether it was; any other state stays, since only money taken is refunded.
     */
    public boolean refunding(Instant at, AccountEntity admin) {
        if (status != OrderStatus.PAID) {
            return false;
        }
        status = OrderStatus.REFUNDING;
        refundRequestedAt = at;
        refundRequestedBy = admin;
        return true;
    }

    /** Refunded at the moment given, once Asaas reports its refund done, and answers whether it was refunding. */
    public boolean refunded(Instant at) {
        if (status != OrderStatus.REFUNDING) {
            return false;
        }
        status = OrderStatus.REFUNDED;
        refundedAt = at;
        return true;
    }

    /**
     * Reversed, if it was paid, by a chargeback or an upheld Pix cautionary block, and answers whether it was; any
     * other state stays: a refunding Order's money is going back already, and a Reversal is final.
     */
    public boolean reverse() {
        if (status != OrderStatus.PAID) {
            return false;
        }
        status = OrderStatus.REVERSED;
        return true;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public AccountEntity getStudent() {
        return student;
    }

    public CourseEntity getCourse() {
        return course;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public int getListPriceCents() {
        return listPriceCents;
    }

    public int getPixDiscountPercent() {
        return pixDiscountPercent;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public String getAsaasPaymentId() {
        return asaasPaymentId;
    }

    public String getPixQrCodePng() {
        return pixQrCodePng;
    }

    public String getPixCopyPasteCode() {
        return pixCopyPasteCode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isDuplicatePayment() {
        return duplicatePayment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getAsaasCheckoutId() {
        return asaasCheckoutId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public Integer getInstallments() {
        return installments;
    }

    public String getAsaasInstallmentId() {
        return asaasInstallmentId;
    }

    public Instant getRefundRequestedAt() {
        return refundRequestedAt;
    }

    public AccountEntity getRefundRequestedBy() {
        return refundRequestedBy;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}

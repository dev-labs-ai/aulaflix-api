package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;

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

import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.EnrollmentOrigin;

/**
 * A Student's right to watch a Course, from {@code startedAt} until it ends. The row stays once it ends, and the ending
 * is final: access comes back only through a new Enrollment.
 */
@Entity
@Table(name = "enrollments")
public class EnrollmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_enrollment")
    @SequenceGenerator(name = "seq_enrollment", sequenceName = "seq_enrollment", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private AccountEntity student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private CourseEntity course;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private EnrollmentOrigin origin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by", updatable = false)
    private AccountEntity grantedBy;

    @Column(name = "grant_note", length = 500, updatable = false)
    private String grantNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", updatable = false)
    private OrderEntity order;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 16)
    private EnrollmentEndReason endReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by")
    private AccountEntity endedBy;

    @Column(name = "end_note", length = 500)
    private String endNote;

    protected EnrollmentEntity() {
    }

    private EnrollmentEntity(AccountEntity student, CourseEntity course, Instant startedAt, EnrollmentOrigin origin) {
        this.student = student;
        this.course = course;
        this.startedAt = startedAt;
        this.origin = origin;
    }

    /** Granted by hand by the Admin, with the note that is the only record of why. */
    public static EnrollmentEntity grantedManually(AccountEntity student, CourseEntity course, Instant startedAt,
                                                   AccountEntity admin, String note) {
        EnrollmentEntity enrollment = new EnrollmentEntity(student, course, startedAt, EnrollmentOrigin.MANUAL);
        enrollment.grantedBy = admin;
        enrollment.grantNote = note;
        return enrollment;
    }

    /** Granted by the Order once paid, to its Student in its Course. */
    public static EnrollmentEntity grantedByOrder(OrderEntity order, Instant startedAt) {
        EnrollmentEntity enrollment = new EnrollmentEntity(order.getStudent(), order.getCourse(), startedAt,
                EnrollmentOrigin.ORDER);
        enrollment.order = order;
        return enrollment;
    }

    /** Ended by hand by the Admin, with a note. */
    public void endManually(Instant at, AccountEntity admin, String note) {
        end(at, EnrollmentEndReason.MANUAL);
        this.endedBy = admin;
        this.endNote = note;
    }

    /**
     * Ended with its Order, which money leaving undid: a Refund, a chargeback, or an upheld Pix cautionary block. Only
     * an Enrollment an Order granted ends this way, and never for {@code MANUAL}, which needs an Admin and a note.
     */
    public void endWithItsOrder(Instant at, EnrollmentEndReason reason) {
        if (origin != EnrollmentOrigin.ORDER || reason == EnrollmentEndReason.MANUAL) {
            throw new IllegalArgumentException("Enrollment %d does not end with its Order for %s"
                    .formatted(id, reason));
        }
        end(at, reason);
    }

    private void end(Instant at, EnrollmentEndReason reason) {
        if (!isActive()) {
            throw new IllegalStateException("Enrollment %d has already ended".formatted(id));
        }
        this.endedAt = at;
        this.endReason = reason;
    }

    public boolean isActive() {
        return endedAt == null;
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getStudent() {
        return student;
    }

    public CourseEntity getCourse() {
        return course;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public EnrollmentOrigin getOrigin() {
        return origin;
    }

    public AccountEntity getGrantedBy() {
        return grantedBy;
    }

    public String getGrantNote() {
        return grantNote;
    }

    public OrderEntity getOrder() {
        return order;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public EnrollmentEndReason getEndReason() {
        return endReason;
    }

    public AccountEntity getEndedBy() {
        return endedBy;
    }

    public String getEndNote() {
        return endNote;
    }
}

package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Someone waiting for a Coming soon Course to go On sale, known only by their normalized email, with no link to an
 * Account. An entry is never changed, only made or deleted; {@code WaitlistEntryRepository} makes it, so that joining
 * again leaves the first entry as it is.
 */
@Entity
@Immutable
@Table(name = "waitlist_entries")
public class WaitlistEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_waitlist_entry")
    @SequenceGenerator(name = "seq_waitlist_entry", sequenceName = "seq_waitlist_entry", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private CourseEntity course;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected WaitlistEntryEntity() {
    }

    public Long getId() {
        return id;
    }

    public CourseEntity getCourse() {
        return course;
    }

    public String getEmail() {
        return email;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}

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
 * A Lesson a Student marked as completed. It belongs to the Student, not to an Enrollment, so it outlives one. A mark
 * is never changed, only made or taken back; {@code CompletedLessonRepository} makes it, so that a repeated mark leaves
 * the first one as it is.
 */
@Entity
@Immutable
@Table(name = "completed_lessons")
public class CompletedLessonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_completed_lesson")
    @SequenceGenerator(name = "seq_completed_lesson", sequenceName = "seq_completed_lesson", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private AccountEntity student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false, updatable = false)
    private LessonEntity lesson;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    protected CompletedLessonEntity() {
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getStudent() {
        return student;
    }

    public LessonEntity getLesson() {
        return lesson;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

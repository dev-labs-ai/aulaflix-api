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

/**
 * The last Lesson a Student opened in a Course, and when: where "Continuar" resumes from. It belongs to the Student,
 * not to an Enrollment, so it outlives one. {@code LessonVisitRepository} records it, overwriting the Course's last
 * visit in one statement.
 */
@Entity
@Table(name = "lesson_visits")
public class LessonVisitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_lesson_visit")
    @SequenceGenerator(name = "seq_lesson_visit", sequenceName = "seq_lesson_visit", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private AccountEntity student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private CourseEntity course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false)
    private LessonEntity lesson;

    @Column(name = "visited_at", nullable = false)
    private Instant visitedAt;

    protected LessonVisitEntity() {
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

    public LessonEntity getLesson() {
        return lesson;
    }

    public Instant getVisitedAt() {
        return visitedAt;
    }
}

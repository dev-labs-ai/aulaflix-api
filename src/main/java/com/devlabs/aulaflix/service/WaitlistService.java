package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.exception.NotOnWaitlistException;
import com.devlabs.aulaflix.exception.SessionRequiredException;
import com.devlabs.aulaflix.exception.WaitlistClosedException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.WaitlistEntryRepository;

/**
 * The Waitlists of Coming soon Courses, whose entries hold a normalized email and nothing else: a Visitor's, or a
 * Student's Account email, so a Student is on a Waitlist whenever an entry holds their email, however it got there.
 * Joining is single opt-in and idempotent, sends no email, and answers alike whoever is listed already.
 */
@Service
public class WaitlistService {

    private final WaitlistEntryRepository entries;
    private final CourseRepository courses;
    private final AccountRepository accounts;
    private final Clock clock;

    public WaitlistService(WaitlistEntryRepository entries, CourseRepository courses, AccountRepository accounts,
                           Clock clock) {
        this.entries = entries;
        this.courses = courses;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** A Visitor joins with an email, which is checked once normalized, before the Course. */
    @Transactional
    public void joinAsVisitor(long courseId, String email) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.emailViolations(normalizedEmail));
        add(courseId, normalizedEmail);
    }

    /** The Student joins with the Account's email. */
    @Transactional
    public void joinAsStudent(long studentId, String courseId) {
        add(PathIds.parse(courseId).orElseThrow(WaitlistClosedException::new), emailOf(studentId));
    }

    @Transactional(readOnly = true)
    public void requireOn(long studentId, String courseId) {
        Optional<Long> course = PathIds.parse(courseId);
        if (course.isEmpty() || !entries.existsByCourseIdAndEmail(course.get(), emailOf(studentId))) {
            throw new NotOnWaitlistException();
        }
    }

    /** Leaving a Waitlist the Student is not on, or a Course that does not exist, changes nothing. */
    @Transactional
    public void leave(long studentId, String courseId) {
        PathIds.parse(courseId).ifPresent(course -> entries.deleteByCourseIdAndEmail(course, emailOf(studentId)));
    }

    /**
     * Under a shared lock on the Course's row, so that the launch, which takes the row lock, either waits for the
     * entry or finds the Course On sale already, and never leaves an entry behind.
     */
    private void add(long courseId, String normalizedEmail) {
        courses.findSharedLockedById(courseId)
                .filter(course -> course.getStatus() == CourseStatus.COMING_SOON)
                .orElseThrow(WaitlistClosedException::new);
        entries.insertIfAbsent(courseId, normalizedEmail, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** Account emails are stored normalized. */
    private String emailOf(long studentId) {
        return accounts.findById(studentId).map(AccountEntity::getEmail).orElseThrow(SessionRequiredException::new);
    }
}

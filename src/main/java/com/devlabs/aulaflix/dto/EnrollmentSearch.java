package com.devlabs.aulaflix.dto;

import java.util.Optional;

/**
 * The Admin's filters on the Enrollments, each optional, once read from the query: the Student's email, as typed; the
 * Course's id; and whether the Enrollment is active.
 */
public record EnrollmentSearch(Optional<String> email, Optional<Long> courseId, Optional<Boolean> active) {
}

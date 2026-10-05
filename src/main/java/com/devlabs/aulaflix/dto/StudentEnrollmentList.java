package com.devlabs.aulaflix.dto;

import java.util.List;

/** "Meus cursos": the Student's active Enrollments, unpaginated, oldest first. */
public record StudentEnrollmentList(List<StudentEnrollment> items) {
}

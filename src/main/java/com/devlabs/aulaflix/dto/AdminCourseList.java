package com.devlabs.aulaflix.dto;

import java.util.List;

/** Every Course, in every state and unpaginated, in the order they were created. */
public record AdminCourseList(List<AdminCourse> items) {
}

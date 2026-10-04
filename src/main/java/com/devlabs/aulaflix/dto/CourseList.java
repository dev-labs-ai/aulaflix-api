package com.devlabs.aulaflix.dto;

import java.util.List;

/** The whole public catalog, unpaginated: On sale first, then the newest launch or announcement first. */
public record CourseList(List<CourseListItem> items) {
}

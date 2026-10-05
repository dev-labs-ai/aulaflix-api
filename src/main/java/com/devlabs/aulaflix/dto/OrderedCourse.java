package com.devlabs.aulaflix.dto;

/** The Course an Order buys, as the Student's page names and links it. */
public record OrderedCourse(long id, String slug, String title) {
}

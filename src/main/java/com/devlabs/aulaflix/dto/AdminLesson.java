package com.devlabs.aulaflix.dto;

/** A Lesson as Admins see it. Its place in the Course, and so its number, comes with the outline. */
public record AdminLesson(long id, long moduleId, String title, String slug) {
}

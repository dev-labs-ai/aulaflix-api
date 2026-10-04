package com.devlabs.aulaflix.dto;

/** A Module as Admins see it. Its place in the Course comes with the outline. */
public record AdminModule(long id, long courseId, String title) {
}

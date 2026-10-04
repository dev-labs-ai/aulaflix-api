package com.devlabs.aulaflix.domain.entity;

/** One question of a Course's own FAQ, stored inside the Course's row with the others, in order. */
public record CourseFaqEntry(String question, String answer) {
}

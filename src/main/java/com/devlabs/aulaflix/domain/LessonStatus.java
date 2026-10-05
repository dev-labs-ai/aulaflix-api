package com.devlabs.aulaflix.domain;

/**
 * The state an Admin moves a Lesson to. A Lesson starts unpublished, showing as "Em breve", and once published it is
 * never unpublished, so published is the only state a request can move it to.
 */
public enum LessonStatus {
    PUBLISHED
}

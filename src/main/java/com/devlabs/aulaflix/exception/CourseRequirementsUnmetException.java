package com.devlabs.aulaflix.exception;

import java.util.List;

/** The Course lacks fields that a state needs: the one it would move to, or the one it must stay fit for. */
public class CourseRequirementsUnmetException extends RuntimeException {

    private final List<String> missing;

    public CourseRequirementsUnmetException(List<String> missing) {
        super("The Course lacks " + missing);
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }
}

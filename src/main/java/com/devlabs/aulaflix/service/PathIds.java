package com.devlabs.aulaflix.service;

import java.util.Optional;
import java.util.regex.Pattern;

/** Ids as the path carries them, so that an id of any shape answers like an unknown one. */
final class PathIds {

    /** Every id a sequence hands out, and nothing that would overflow a {@code long}. */
    private static final Pattern ID_SHAPE = Pattern.compile("[1-9][0-9]{0,17}");

    private PathIds() {
    }

    /** The id, or nothing when no row could ever have it. */
    static Optional<Long> parse(String id) {
        return ID_SHAPE.matcher(id).matches() ? Optional.of(Long.parseLong(id)) : Optional.empty();
    }
}

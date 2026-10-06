package com.devlabs.aulaflix.service;

import java.util.List;

import com.devlabs.aulaflix.dto.ManualEnrollmentRequest;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

/**
 * The note an Admin writes when granting or ending an Enrollment, the only record of why. The request already requires
 * it; here it is trimmed, then counted in characters, as the web counts them, not in UTF-16 units.
 */
final class EnrollmentNotes {

    private EnrollmentNotes() {
    }

    /** The note, trimmed, refused when longer than the limit once trimmed. */
    static String trimmed(String note) {
        String trimmed = note.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > ManualEnrollmentRequest.NOTE_MAX_CHARACTERS) {
            throw new InvalidRequestException(List.of(new FieldViolation("note", "too-long")));
        }
        return trimmed;
    }
}

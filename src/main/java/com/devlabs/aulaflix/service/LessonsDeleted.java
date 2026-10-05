package com.devlabs.aulaflix.service;

import java.util.List;

/** Published by the transaction that deletes the Lessons, whose videos go once it commits. */
record LessonsDeleted(List<Long> lessonIds) {
}

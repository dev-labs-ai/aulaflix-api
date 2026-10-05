package com.devlabs.aulaflix.service;

/** Published by the transaction that links a video to the Lesson, whose other objects go once it commits. */
record VideoLinked(long lessonId) {
}

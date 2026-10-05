package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.devlabs.aulaflix.domain.entity.LessonEntity;
import com.devlabs.aulaflix.dto.AdminLesson;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.VideoLinkRequest;
import com.devlabs.aulaflix.dto.VideoPlayback;
import com.devlabs.aulaflix.dto.VideoUpload;
import com.devlabs.aulaflix.exception.EnrollmentRequiredException;
import com.devlabs.aulaflix.exception.LessonNotFoundException;
import com.devlabs.aulaflix.exception.SessionRequiredException;
import com.devlabs.aulaflix.exception.VideoNotFoundException;
import com.devlabs.aulaflix.exception.VideoNotLinkedException;
import com.devlabs.aulaflix.repository.LessonRepository;

/**
 * The Video module: each Lesson's video, uploaded straight to the storage, linked once there, and played from it
 * (ADR 0007). The bytes never pass through the API. Only this module reaches the storage, and every object of a Lesson
 * lives under its own prefix, {@code lessons/{lessonId}/}.
 */
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    /** Each key the API issues: a new UUID, in canonical form, under the Lesson's prefix. */
    private static final Pattern ISSUED_KEY = Pattern.compile(
            "lessons/([0-9]+)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4");

    private final LessonRepository lessons;
    private final CatalogLocks locks;
    private final VideoStorage storage;
    private final ApplicationEventPublisher events;
    private final EnrollmentService enrollments;

    public VideoService(LessonRepository lessons, CatalogLocks locks, VideoStorage storage,
                        ApplicationEventPublisher events, EnrollmentService enrollments) {
        this.lessons = lessons;
        this.locks = locks;
        this.storage = storage;
        this.events = events;
        this.enrollments = enrollments;
    }

    /** For a Lesson in any state. Nothing is stored: the key only names the upload to come. */
    @Transactional(readOnly = true)
    public VideoUpload requestUpload(long adminId, String lessonId) {
        long id = PathIds.parse(lessonId).filter(lessons::existsById).orElseThrow(LessonNotFoundException::new);
        String objectKey = prefixOf(id) + UUID.randomUUID() + ".mp4";
        VideoStorage.SignedUrl upload = storage.signUpload(objectKey);
        log.info("Admin {} requested an upload URL for Lesson {}", adminId, id);
        return new VideoUpload(upload.url(), objectKey, upload.expiresAt(),
                Map.of("Content-Type", VideoStorage.CONTENT_TYPE));
    }

    /**
     * Makes the uploaded object the Lesson's video, with the duration read from its header, in place of any other.
     * Linking never publishes. Once it commits, every other object under the Lesson's prefix is deleted. The header is
     * read before the Course's lock is taken, so that no other change to the Course waits on the storage.
     */
    @Transactional
    public AdminLesson link(long adminId, String lessonId, VideoLinkRequest request) {
        long id = PathIds.parse(lessonId).filter(lessons::existsById).orElseThrow(LessonNotFoundException::new);
        String objectKey = request.objectKey();
        if (!wasIssuedFor(id, objectKey)) {
            throw new VideoNotFoundException();
        }
        long size = storage.sizeOf(objectKey).orElseThrow(VideoNotFoundException::new);
        Mp4Header header = Mp4Header.read(new StoredFile(storage, objectKey, size));
        LessonEntity lesson = locks.lesson(lessonId);
        lesson.linkVideo(objectKey, header.durationSeconds());
        events.publishEvent(new VideoLinked(lesson.getId()));
        log.info("Admin {} linked a video to Lesson {}", adminId, lesson.getId());
        return OutlineService.lessonView(lesson);
    }

    /** Any linked video, whatever the state of the Lesson and of its Course, so the Admin checks it first. */
    @Transactional(readOnly = true)
    public VideoPlayback adminPlayback(String lessonId) {
        LessonEntity lesson = PathIds.parse(lessonId).flatMap(lessons::findById)
                .orElseThrow(LessonNotFoundException::new);
        return Optional.ofNullable(lesson.getVideoObjectKey())
                .map(this::playbackOf)
                .orElseThrow(VideoNotLinkedException::new);
    }

    /**
     * A published Lesson of an On sale Course, for anyone but an Admin, whom the security chain refuses first: the Free
     * lesson plays without a session, and any other Lesson needs a Student's session and an active Enrollment in its
     * Course. A published Lesson always has its video.
     */
    @Transactional(readOnly = true)
    public VideoPlayback playback(String lessonId, Optional<AuthenticatedAccount> viewer) {
        LessonEntity lesson = PathIds.parse(lessonId).flatMap(lessons::findPublishedInOnSaleCourse)
                .orElseThrow(LessonNotFoundException::new);
        if (lesson.isFreeLesson()) {
            return playbackOf(lesson.getVideoObjectKey());
        }
        long studentId = viewer.orElseThrow(SessionRequiredException::new).accountId();
        if (!enrollments.isActivelyEnrolled(studentId, lesson.getCourse().getId())) {
            throw new EnrollmentRequiredException();
        }
        return playbackOf(lesson.getVideoObjectKey());
    }

    /** Signed afresh with the read-only key, so that the URL only ever reads the one object. */
    private VideoPlayback playbackOf(String objectKey) {
        VideoStorage.SignedUrl playback = storage.signPlayback(objectKey);
        return new VideoPlayback(playback.url(), playback.expiresAt());
    }

    /**
     * Reads the Lesson's video, and deletes the rest, under its Course's lock, so that a link still in flight is kept:
     * without it, the key read could be the one this link replaced, while another link commits a key of its own.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteAllButTheLinkedVideo(VideoLinked linked) {
        locks.findLesson(linked.lessonId()).ifPresent(lesson -> deleteObjectsUnder(lesson.getId(),
                key -> !key.equals(lesson.getVideoObjectKey())));
    }

    @TransactionalEventListener
    public void deleteTheVideosOf(LessonsDeleted deleted) {
        deleted.lessonIds().forEach(lessonId -> deleteObjectsUnder(lessonId, key -> true));
    }

    /**
     * A delete that fails leaves an orphan and a log line, and undoes nothing: the change it follows has committed.
     */
    private void deleteObjectsUnder(long lessonId, Predicate<String> deletable) {
        List<String> keys;
        try {
            keys = storage.keysUnder(prefixOf(lessonId));
        } catch (RuntimeException failure) {
            log.warn("Could not list the objects of Lesson {}; any it had are left as orphans", lessonId, failure);
            return;
        }
        keys.stream().filter(deletable).forEach(key -> {
            try {
                storage.delete(key);
            } catch (RuntimeException failure) {
                log.warn("Could not delete {} of Lesson {}; it is left as an orphan", key, lessonId, failure);
            }
        });
    }

    /** Only the API issues keys, so a key's shape tells whether it was issued for the Lesson. */
    private static boolean wasIssuedFor(long lessonId, String objectKey) {
        Matcher issued = ISSUED_KEY.matcher(objectKey);
        return issued.matches() && issued.group(1).equals(Long.toString(lessonId));
    }

    private static String prefixOf(long lessonId) {
        return "lessons/%d/".formatted(lessonId);
    }

    /** The stored object, read range by range: its header never needs the whole file. */
    private record StoredFile(VideoStorage storage, String key, long size) implements Mp4Header.Source {

        @Override
        public byte[] read(long offset, int length) {
            return storage.read(key, offset, length);
        }
    }
}

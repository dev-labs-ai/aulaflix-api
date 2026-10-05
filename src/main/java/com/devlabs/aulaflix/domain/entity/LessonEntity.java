package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "lessons")
public class LessonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_lesson")
    @SequenceGenerator(name = "seq_lesson", sequenceName = "seq_lesson", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private CourseEntity course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private ModuleEntity module;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "video_object_key", length = 100)
    private String videoObjectKey;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected LessonEntity() {
    }

    /** A new Lesson at the given position of its Module, in the Module's Course. */
    public LessonEntity(ModuleEntity module, int position, String title, String slug) {
        this.course = module.getCourse();
        this.module = module;
        this.position = position;
        this.title = title;
        this.slug = slug;
    }

    public Long getId() {
        return id;
    }

    public CourseEntity getCourse() {
        return course;
    }

    public ModuleEntity getModule() {
        return module;
    }

    public void setModule(ModuleEntity module) {
        this.module = module;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    /** The object key of the Lesson's video, or null while it has none. */
    public String getVideoObjectKey() {
        return videoObjectKey;
    }

    /** Read from the video's file, or null while the Lesson has no video. */
    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    /** Makes the object the Lesson's video, in place of any other, with the duration read from its file. */
    public void linkVideo(String videoObjectKey, int durationSeconds) {
        this.videoObjectKey = videoObjectKey;
        this.durationSeconds = durationSeconds;
    }

    /** Whether this is its Course's Free lesson, the one anyone may watch once the Course is On sale. */
    public boolean isFreeLesson() {
        return id.equals(course.getFreeLessonId());
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /** When the Lesson was published, or null while it shows as "Em breve". */
    public Instant getPublishedAt() {
        return publishedAt;
    }

    /** Publishes the Lesson as of the instant, for good; whether it may be published is the service's call. */
    public void publish(Instant at) {
        this.publishedAt = at;
    }
}

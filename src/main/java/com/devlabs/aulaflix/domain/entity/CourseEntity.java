package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.CourseTone;

@Entity
@Table(name = "courses")
public class CourseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_course")
    @SequenceGenerator(name = "seq_course", sequenceName = "seq_course", allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 300)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Area area;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CourseIcon icon;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CourseTone tone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> about;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> learn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> audience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "planned_topics", nullable = false)
    private List<String> plannedTopics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<CourseFaqEntry> faq;

    @Column(name = "price_cents")
    private Integer priceCents;

    @Column(name = "pix_discount_percent")
    private Integer pixDiscountPercent;

    @Column(name = "max_installments")
    private Integer maxInstallments;

    @Column(name = "free_lesson_id")
    private Long freeLessonId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CourseStatus status;

    @Column(name = "coming_soon_at")
    private Instant comingSoonAt;

    @Column(name = "on_sale_at")
    private Instant onSaleAt;

    @Column(name = "notified_count")
    private Integer notifiedCount;

    protected CourseEntity() {
    }

    /** A new Draft, with nothing but its slug and title. */
    public CourseEntity(String slug, String title) {
        this.slug = slug;
        this.title = title;
        this.about = List.of();
        this.learn = List.of();
        this.audience = List.of();
        this.plannedTopics = List.of();
        this.faq = List.of();
        this.status = CourseStatus.DRAFT;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Area getArea() {
        return area;
    }

    public void setArea(Area area) {
        this.area = area;
    }

    public CourseIcon getIcon() {
        return icon;
    }

    public void setIcon(CourseIcon icon) {
        this.icon = icon;
    }

    public CourseTone getTone() {
        return tone;
    }

    public void setTone(CourseTone tone) {
        this.tone = tone;
    }

    public List<String> getAbout() {
        return about;
    }

    public void setAbout(List<String> about) {
        this.about = List.copyOf(about);
    }

    public List<String> getLearn() {
        return learn;
    }

    public void setLearn(List<String> learn) {
        this.learn = List.copyOf(learn);
    }

    public List<String> getAudience() {
        return audience;
    }

    public void setAudience(List<String> audience) {
        this.audience = List.copyOf(audience);
    }

    public List<String> getPlannedTopics() {
        return plannedTopics;
    }

    public void setPlannedTopics(List<String> plannedTopics) {
        this.plannedTopics = List.copyOf(plannedTopics);
    }

    public List<CourseFaqEntry> getFaq() {
        return faq;
    }

    public void setFaq(List<CourseFaqEntry> faq) {
        this.faq = List.copyOf(faq);
    }

    public Integer getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Integer priceCents) {
        this.priceCents = priceCents;
    }

    public Integer getPixDiscountPercent() {
        return pixDiscountPercent;
    }

    public void setPixDiscountPercent(Integer pixDiscountPercent) {
        this.pixDiscountPercent = pixDiscountPercent;
    }

    public Integer getMaxInstallments() {
        return maxInstallments;
    }

    public void setMaxInstallments(Integer maxInstallments) {
        this.maxInstallments = maxInstallments;
    }

    /** The id of the one published Lesson of this Course that anyone may watch, or null while it has none. */
    public Long getFreeLessonId() {
        return freeLessonId;
    }

    public void setFreeLessonId(Long freeLessonId) {
        this.freeLessonId = freeLessonId;
    }

    public CourseStatus getStatus() {
        return status;
    }

    /** Moves the Course forward to the state, as of the instant; whether it may go there is the service's call. */
    public void moveTo(CourseStatus status, Instant at) {
        switch (status) {
            case DRAFT -> throw new IllegalArgumentException("A Course never moves back to Draft");
            case COMING_SOON -> comingSoonAt = at;
            case ON_SALE -> onSaleAt = at;
        }
        this.status = status;
    }

    public Instant getComingSoonAt() {
        return comingSoonAt;
    }

    public Instant getOnSaleAt() {
        return onSaleAt;
    }

    /** How many launch emails the launch from Coming soon queued, or null if the Course never launched from there. */
    public Integer getNotifiedCount() {
        return notifiedCount;
    }

    public void setNotifiedCount(Integer notifiedCount) {
        this.notifiedCount = notifiedCount;
    }
}

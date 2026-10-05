package com.devlabs.aulaflix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts Courses straight into the {@code courses} table: Coming soon ones announced at a chosen time, so tests can
 * show how the endpoints treat a Course that is no longer a Draft.
 */
public final class StoredCourses {

    private final JdbcTemplate jdbc;

    public StoredCourses(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A Coming soon Course with everything that state needs, and nothing more. */
    public long insertComingSoon(String slug, Instant comingSoonAt) {
        long id = jdbc.queryForObject("select nextval('seq_course')", Long.class);
        jdbc.update("""
                        insert into courses (id, slug, title, summary, area, icon, tone, about, learn, audience,
                                             planned_topics, faq, status, coming_soon_at)
                        values (?, ?, 'DevOps na Prática', 'Do commit à produção.', 'DEVOPS',
                                'CONTAINER', 'SAGE', '["Por que DevOps."]', '["Docker."]', '["Devs."]',
                                '["Pipelines."]', '[]', 'COMING_SOON', ?)""",
                id, slug, OffsetDateTime.ofInstant(comingSoonAt, ZoneOffset.UTC));
        return id;
    }
}

package com.devlabs.aulaflix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts Courses straight into the {@code courses} table, in states no endpoint can reach yet, so tests can show how
 * the Admin endpoints treat a Course that is no longer a Draft.
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

    /** An On sale Course that went there straight from Draft, so it was never Coming soon. */
    public long insertOnSale(String slug, Instant onSaleAt) {
        long id = jdbc.queryForObject("select nextval('seq_course')", Long.class);
        jdbc.update("""
                        insert into courses (id, slug, title, summary, area, icon, tone, about, learn, audience,
                                             planned_topics, faq, price_cents, pix_discount_percent,
                                             max_installments, status, on_sale_at)
                        values (?, ?, 'Testes Automatizados', 'Testes que dão confiança.', 'QUALITY',
                                'FLASK_CONICAL', 'YELLOW', '["Por que testar."]', '["Unidade."]', '["Devs."]',
                                '[]', '[]', 39700, 10, 10, 'ON_SALE', ?)""",
                id, slug, OffsetDateTime.ofInstant(onSaleAt, ZoneOffset.UTC));
        return id;
    }
}

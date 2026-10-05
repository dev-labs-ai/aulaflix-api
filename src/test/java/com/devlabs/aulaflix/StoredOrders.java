package com.devlabs.aulaflix;

import java.security.SecureRandom;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts Orders straight into the {@code orders} table, for the races the HTTP contract cannot set up on its own: a
 * placement is refused while the Student has the Course, so a second Order of that Course can only be the leftover of a
 * race, such as a Pix paid after the Student switched to card.
 */
public final class StoredOrders {

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public StoredOrders(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A new Pix Order of the same Student, Course and amount as the Order given, awaiting payment, whose charge is the
     * one {@link Asaas#chargeOf} names for its code; answers that code.
     */
    public String insertAwaitingCopyOf(String code) {
        String copy = newCode();
        jdbc.update("""
                        insert into orders (id, code, student_id, course_id, method, status, list_price_cents,
                                            pix_discount_percent, amount_cents, asaas_payment_id, expires_at,
                                            created_at)
                        select nextval('seq_order'), ?, student_id, course_id, method, 'AWAITING_PAYMENT',
                               list_price_cents, pix_discount_percent, amount_cents, ?, expires_at, created_at
                        from orders where code = ?""",
                copy, Asaas.chargeOf(copy), code);
        return copy;
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}

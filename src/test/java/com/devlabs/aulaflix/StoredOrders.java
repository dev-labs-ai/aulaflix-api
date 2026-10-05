package com.devlabs.aulaflix;

import java.security.SecureRandom;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes the {@code orders} table directly, for the states the HTTP contract cannot set up on its own. A placement is
 * refused while the Student has the Course, so a second Order of that Course can only be the leftover of a race, such
 * as a Pix paid after the Student switched to card; and an Order without its charge's id is one the API stopped placing
 * between making the charge at Asaas and keeping its id.
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

    /**
     * Cancels the Order with its charge left payable at Asaas, as the switch to the other method leaves a Pix whose
     * Student paid it all the same.
     */
    public void cancel(String code) {
        jdbc.update("update orders set status = 'CANCELLED' where code = ?", code);
    }

    /** Leaves the Order as if its charge's id and QR code had never come back from Asaas. */
    public void forgetCharge(String code) {
        jdbc.update("""
                update orders set asaas_payment_id = null, pix_qr_code_png = null, pix_copy_paste_code = null
                where code = ?""", code);
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}

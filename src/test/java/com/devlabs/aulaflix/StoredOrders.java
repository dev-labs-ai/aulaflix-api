package com.devlabs.aulaflix;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes the {@code orders} table directly, for the one state no endpoint can reach: an Order the API stopped placing
 * between making its charge at Asaas and keeping the charge's id.
 */
public final class StoredOrders {

    private final JdbcTemplate jdbc;

    public StoredOrders(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Leaves the Order as if its charge's id and QR code had never come back from Asaas. */
    public void forgetCharge(String code) {
        jdbc.update("""
                update orders set asaas_payment_id = null, pix_qr_code_png = null, pix_copy_paste_code = null
                where code = ?""", code);
    }
}
